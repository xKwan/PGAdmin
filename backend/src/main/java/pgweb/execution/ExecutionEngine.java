package pgweb.execution;

import org.postgresql.core.BaseConnection;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.config.Config;
import pgweb.dbsession.DbSession;
import pgweb.dbsession.DbSessionManager;
import pgweb.dbsession.StatementRunner;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs one user statement on a DB session (plan §3, Phase 7). Every control lives here, on the server:
 * <ul>
 *   <li>Kill switch: {@code execution.enabled} (EXE-01).</li>
 *   <li>One statement at a time per session (EXE-08): a second request gets 409.</li>
 *   <li>The single-statement gate before anything is sent ({@link StatementGate}).</li>
 *   <li>Audit intent committed <b>before</b> execution; fail-closed refuses execution when it can't be written
 *       (AUD-01/02). Outcome after; an unknown result is recorded as {@code indeterminate}.</li>
 *   <li>Bounded retrieval: at most {@code maxRows} rows (the driver asks the server for {@code maxRows + 1} to detect
 *       truncation), cells and the whole response capped in bytes. SQL is never rewritten (EXE-05).</li>
 *   <li>The app's own wall-clock timer (a user's {@code SET statement_timeout} can't defeat it, EXE-06), cancel, and
 *       escalation to {@code pg_terminate_backend} on the companion after the grace period; the session is then
 *       quarantined (closed, never reused, EXE-07).</li>
 * </ul>
 */
public final class ExecutionEngine implements StatementRunner, AutoCloseable {

    /** Test hook: how a running statement is cancelled. Production: {@link Statement#cancel()}. */
    public interface Canceller {
        void cancel(Statement st) throws SQLException;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionEngine.class);
    private static final String EV_OUTCOME = "exec_outcome";
    private static final String CONFIRMED = "confirmed";
    private static final String ESCALATED = "escalated_terminated";
    private static final String UNCONFIRMED = "unconfirmed";
    private static final String INDETERMINATE = "indeterminate";
    private static final String ERROR = "error";
    /** admin_shutdown, crash_shutdown, cannot_connect_now: the backend is gone. */
    private static final Set<String> TERMINATED = Set.of("57P01", "57P02", "57P03");

    private final Config config;
    private final DbSessionManager sessions;
    private final StatementGate gate;
    private final AuditService audit;
    private final Clock clock;
    private final Canceller canceller;
    private final ScheduledExecutorService timers;
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    public ExecutionEngine(Config config, DbSessionManager sessions, StatementGate gate, AuditService audit, Clock clock, Canceller canceller) {
        this.config = config;
        this.sessions = sessions;
        this.gate = gate;
        this.audit = audit;
        this.clock = clock;
        this.canceller = canceller == null ? Statement::cancel : canceller;
        this.timers = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "pgweb-execution-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Per-execution state shared with the timer and the cancel endpoint. */
    static final class Running {
        final UUID executionId = UUID.randomUUID();
        final Instant startedAt;
        final Statement statement;
        volatile String stopReason; // "timeout" or "cancelled"
        volatile String cancelOutcome; // confirmed | escalated_terminated | unconfirmed
        final Object lock = new Object();
        boolean finished; // guarded by lock
        boolean escalating; // guarded by lock
        final CountDownLatch escalated = new CountDownLatch(1);

        Running(Statement statement, Instant startedAt) {
            this.statement = statement;
            this.startedAt = startedAt;
        }
    }

    /** Executes {@code sql} on the user's session and returns the result document (see {@link ResultBuilder}). */
    @Override
    public Map<String, Object> execute(UserContext user, String sessionId, String sql, String requestId) {
        if (!config.executionEnabled()) {
            throw ApiException.forbidden("EXECUTION_DISABLED", "Running SQL is switched off by the administrator");
        }
        DbSession s = sessions.get(user, sessionId);
        if (!s.lock().tryLock()) {
            throw ApiException.conflict("SESSION_BUSY", "A statement is already running in this session; wait for it or cancel it");
        }
        try {
            return executeLocked(user, s, sql, requestId);
        } finally {
            s.lock().unlock();
        }
    }

    private Map<String, Object> executeLocked(UserContext user, DbSession s, String sql, String requestId) {
        Connection main = s.main();
        boolean scs = standardConformingStrings(main);
        StatementGate.Verdict v = gate.check(sql, scs);
        String hmac = sql == null ? null : audit.sqlHmac(sql);
        int length = sql == null ? 0 : sql.length();
        if (!v.approved()) {
            throw rejected(user, s, v, hmac, length, requestId);
        }
        String txBefore = s.transactionState();
        String normalised = scs ? gateText(v.statement().text()) : null;
        Statement st = newStatement(s);
        Running run = new Running(st, clock.instant());
        Instant start = run.startedAt;
        // AUD-01: the intent is committed before anything is sent. Fail-closed throws here (503).
        try {
            audit.require(AuditEvent.of("exec_intent").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                    .execution(run.executionId, null).statement(v.statementClass(), normalised, hmac, length, scs ? "ok" : "ok_no_text")
                    .transaction(txBefore, null).times(start, null).detail("requestId", requestId));
        } catch (RuntimeException e) {
            closeQuietly(st);
            throw e;
        }
        ResultBuilder result = new ResultBuilder(config.limits(), run.executionId, v.statementClass());
        Ended ended = runStatement(s, run, sql, result);
        Instant end = clock.instant();
        String txAfter = s.transactionState();
        sessions.transactionObserved(s, !"IDLE".equals(txAfter));
        result.finish(txAfter, Duration.between(start, end).toMillis(), end);
        audit.record(AuditEvent.of(EV_OUTCOME).user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .execution(run.executionId, run.executionId).statement(v.statementClass(), null, hmac, length, null)
                .outcome(ended.outcome(), safeSqlstate(ended.sqlstate()), result.rowCountForAudit(), result.truncated(),
                        Duration.between(start, end).toMillis())
                .cancel(run.cancelOutcome).transaction(txBefore, txAfter).times(start, end));
        boolean lost = INDETERMINATE.equals(ended.outcome());
        if (lost || ESCALATED.equals(run.cancelOutcome) || UNCONFIRMED.equals(run.cancelOutcome)) {
            sessions.quarantine(s, lost ? "connection_lost" : "terminated_after_" + run.stopReason);
            result.sessionEnded();
        }
        return result.document();
    }

    /** How a statement ended: success, error, timeout, cancelled or indeterminate, with the SQLSTATE if any. */
    private record Ended(String outcome, String sqlstate) {
    }

    private ApiException rejected(UserContext user, DbSession s, StatementGate.Verdict v, String hmac, int length, String requestId) {
        audit.record(AuditEvent.of("exec_rejected").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .statement(null, null, hmac, length, v.rejection().name().toLowerCase(Locale.ROOT)).detail("requestId", requestId));
        int status = v.rejection() == StatementGate.Rejection.TOO_LARGE ? 413 : 400;
        Map<String, Object> err = v.position() > 0 ? Map.of("position", v.position()) : Map.of();
        return new ApiException(status, v.rejection().name(), v.message(), err);
    }

    private Statement newStatement(DbSession s) {
        try {
            Statement st = s.main().createStatement();
            st.setEscapeProcessing(false); // ADR-0009 J4
            st.setMaxRows(config.limits().maxRows() + 1);
            return st;
        } catch (SQLException e) {
            throw sessionLost(s);
        }
    }

    /** Sends the statement with the timer armed and reads its results; never throws for a failed statement. */
    private Ended runStatement(DbSession s, Running run, String sql, ResultBuilder result) {
        running.put(s.id(), run);
        Duration limit = config.profiles().get(s.profile()).statementTimeout();
        ScheduledFuture<?> timer = timers.schedule(() -> stop(s, run, "timeout"), limit.toMillis(), TimeUnit.MILLISECONDS);
        Ended ended;
        try {
            boolean hasRows = run.statement.execute(sql);
            readAll(run.statement, hasRows, result);
            ended = new Ended("success", null);
        } catch (SQLException e) {
            if (run.stopReason != null) {
                ended = new Ended(run.stopReason, e.getSQLState()); // timeout or cancelled
            } else if (connectionBroken(e, s.main())) {
                ended = new Ended(INDETERMINATE, e.getSQLState());
                result.indeterminate();
            } else {
                ended = new Ended(ERROR, e.getSQLState());
                result.error(e);
            }
        } finally {
            timer.cancel(false);
            running.remove(s.id());
            settle(run);
            closeQuietly(run.statement);
        }
        if (ended.outcome().equals(run.stopReason)) {
            result.stopped(run.stopReason, run.cancelOutcome);
        }
        return ended;
    }

    private static String safeSqlstate(String sqlstate) {
        return sqlstate != null && sqlstate.matches("[0-9A-Z]{5}") ? sqlstate : null;
    }

    /** Cancel requested by the user (the cancel endpoint; doesn't need the session lock). */
    @Override
    public Map<String, Object> cancel(UserContext user, String sessionId) {
        DbSession s = sessions.get(user, sessionId);
        Running run = running.get(s.id());
        if (run == null) {
            throw ApiException.conflict("NOT_RUNNING", "No statement is running in this session");
        }
        audit.record(AuditEvent.of("cancel_requested").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .execution(run.executionId, null));
        stop(s, run, "cancelled");
        return Map.of("executionId", run.executionId.toString(), "status", "cancel_requested");
    }

    /** Cancel, then terminate through the companion if the statement is still running after the grace period. */
    private void stop(DbSession s, Running run, String reason) {
        synchronized (run.lock) {
            if (run.finished || run.stopReason != null) {
                return;
            }
            run.stopReason = reason;
        }
        try {
            canceller.cancel(run.statement);
        } catch (SQLException e) {
            LOG.warn("cancel request failed for session {}: {}", s.id(), e.getSQLState());
        }
        timers.schedule(() -> escalate(s, run), config.limits().cancelGrace().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Marks the execution finished. If an escalation is already under way, waits for it so the result reports how the
     * statement was stopped; if not, the cancel worked by itself.
     */
    private static void settle(Running run) {
        boolean wait;
        synchronized (run.lock) {
            run.finished = true;
            wait = run.escalating;
            if (run.stopReason != null && !wait) {
                run.cancelOutcome = CONFIRMED;
            }
        }
        if (wait) {
            try {
                if (!run.escalated.await(30, TimeUnit.SECONDS)) {
                    LOG.warn("escalation of execution {} did not report back in time", run.executionId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void escalate(DbSession s, Running run) {
        boolean escalating;
        synchronized (run.lock) {
            escalating = !run.finished;
            run.escalating = escalating;
        }
        if (!escalating) {
            auditCancel(s, run, CONFIRMED);
            return;
        }
        try {
            run.cancelOutcome = terminate(s) ? ESCALATED : UNCONFIRMED;
            auditCancel(s, run, run.cancelOutcome);
        } finally {
            run.escalated.countDown();
        }
    }

    private void auditCancel(DbSession s, Running run, String outcome) {
        audit.record(AuditEvent.of("cancel_result").user(s.ownerAccount(), s.ownerSid(), null).session(s.appSessionHash())
                .target(s.profile(), s.database(), s.role(), s.mainPid(), s.id()).execution(run.executionId, null).cancel(outcome));
    }

    /**
     * Ends the backend through the companion connection. If that isn't possible (degraded session, or it failed), the
     * main connection is closed so the request thread is released; the session is quarantined either way.
     */
    private static boolean terminate(DbSession s) {
        Connection companion = s.companion();
        boolean terminated = false;
        if (companion != null) {
            try (PreparedStatement ps = companion.prepareStatement("SELECT pg_catalog.pg_terminate_backend(?)")) {
                ps.setInt(1, s.mainPid());
                try (ResultSet rs = ps.executeQuery()) {
                    terminated = rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException e) {
                LOG.warn("escalation failed for session {}: {}", s.id(), e.getSQLState());
            }
        }
        if (!terminated) {
            try {
                s.main().close();
            } catch (SQLException e) {
                LOG.warn("closing the main connection of session {} failed: {}", s.id(), e.getSQLState());
            }
        }
        return terminated;
    }

    private static void readAll(Statement st, boolean hasRows, ResultBuilder result) throws SQLException {
        boolean rows = hasRows;
        while (true) {
            if (rows) {
                try (ResultSet rs = st.getResultSet()) {
                    result.rows(rs);
                }
            } else {
                int count = st.getUpdateCount();
                if (count == -1) {
                    break;
                }
                result.command(count);
            }
            rows = st.getMoreResults();
        }
    }

    /** pgJDBC tracks standard_conforming_strings from the server's ParameterStatus messages (S2 P4). */
    static boolean standardConformingStrings(Connection c) {
        try {
            return c.unwrap(BaseConnection.class).getStandardConformingStrings();
        } catch (SQLException e) {
            return true;
        }
    }

    private String gateText(String statement) {
        try {
            return gate.auditText(statement);
        } catch (RuntimeException e) {
            return null; // audit keeps the HMAC and metadata only
        }
    }

    static boolean connectionBroken(SQLException e, Connection c) {
        String state = e.getSQLState();
        if (state != null && (state.startsWith("08") || TERMINATED.contains(state))) {
            return true;
        }
        try {
            return c.isClosed();
        } catch (SQLException ignored) {
            return true;
        }
    }

    private ApiException sessionLost(DbSession s) {
        sessions.quarantine(s, "connection_lost");
        return new ApiException(410, "SESSION_LOST", "The connection to the database was lost.");
    }

    private static void closeQuietly(Statement st) {
        try {
            st.close();
        } catch (SQLException ignored) {
            // connection may be gone
        }
    }

    @Override
    public void close() {
        timers.shutdownNow();
    }

    /** Builds the result document within the limits. */
    static final class ResultBuilder {
        private final Config.Limits limits;
        private final Map<String, Object> doc = new LinkedHashMap<>();
        private final List<Map<String, Object>> results = new ArrayList<>();
        private long bytes;
        private boolean truncated;
        private Long auditRows;

        ResultBuilder(Config.Limits limits, UUID executionId, String statementClass) {
            this.limits = limits;
            doc.put("executionId", executionId.toString());
            doc.put("statementClass", statementClass);
        }

        void rows(ResultSet rs) throws SQLException {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<Map<String, Object>> cols = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                cols.add(Map.of("name", md.getColumnLabel(i), "type", md.getColumnTypeName(i)));
                bytes += md.getColumnLabel(i).length() + 16L;
            }
            List<List<String>> rows = new ArrayList<>();
            List<List<Integer>> cutCells = new ArrayList<>();
            String reason = null;
            while (reason == null && rs.next()) {
                if (rows.size() >= limits.maxRows()) {
                    reason = "rows"; // the driver fetched maxRows + 1: there is more
                } else {
                    reason = keep(rs, n, rows, cutCells);
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "rows");
            r.put("columns", cols);
            r.put("rows", rows);
            r.put("rowCount", rows.size());
            r.put("truncated", reason != null);
            if (reason != null) {
                r.put("truncatedBy", reason);
                truncated = true;
            }
            if (!cutCells.isEmpty()) {
                r.put("truncatedCells", cutCells);
            }
            auditRows = (long) rows.size();
            results.add(r);
        }

        /** Reads the current row and keeps it if it fits the response limit; returns "bytes" when it doesn't. */
        private String keep(ResultSet rs, int n, List<List<String>> rows, List<List<Integer>> cutCells) throws SQLException {
            List<Integer> cutHere = new ArrayList<>();
            List<String> row = readRow(rs, n, cutHere);
            long rowBytes = estimatedBytes(row);
            if (bytes + rowBytes > limits.maxResponseBytes()) {
                return "bytes";
            }
            bytes += rowBytes;
            for (int c : cutHere) {
                cutCells.add(List.of(rows.size(), c));
            }
            rows.add(row);
            return null;
        }

        /** One row as PostgreSQL's text; values over the cell limit are cut and their column added to {@code cut}. */
        private List<String> readRow(ResultSet rs, int n, List<Integer> cut) throws SQLException {
            List<String> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                String v = rs.getString(i);
                if (v != null && v.length() * 3L > limits.maxCellBytes() && v.getBytes(StandardCharsets.UTF_8).length > limits.maxCellBytes()) {
                    v = cut(v, limits.maxCellBytes());
                    cut.add(i - 1);
                }
                row.add(v);
            }
            return row;
        }

        /** Rough size of a row in the JSON response (UTF-16 characters, as the limit is checked before encoding). */
        private static long estimatedBytes(List<String> row) {
            long b = 0;
            for (String v : row) {
                b += v == null ? 4 : v.length() * 2L + 2;
            }
            return b;
        }

        void command(int count) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "command");
            if (count >= 0) {
                r.put("rowCount", count);
                auditRows = (long) count;
            }
            results.add(r);
        }

        void error(SQLException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("sqlstate", e.getSQLState());
            String message = e.getMessage();
            if (e instanceof PSQLException p && p.getServerErrorMessage() != null) {
                ServerErrorMessage m = p.getServerErrorMessage();
                message = m.getMessage();
                if (m.getPosition() > 0) {
                    err.put("position", m.getPosition());
                }
                putIfPresent(err, "detail", m.getDetail());
                putIfPresent(err, "hint", m.getHint());
                putIfPresent(err, "severity", m.getSeverity());
            }
            err.put("message", message);
            doc.put("kind", ERROR);
            doc.put(ERROR, err);
        }

        void stopped(String reason, String cancelOutcome) {
            doc.put("kind", reason);
            if (cancelOutcome != null) {
                doc.put("cancelOutcome", cancelOutcome);
            }
        }

        void indeterminate() {
            doc.put("kind", INDETERMINATE);
            doc.put("message", "The connection was lost while the statement ran. It may or may not have taken effect.");
        }

        void sessionEnded() {
            doc.put("sessionEnded", true);
        }

        void finish(String transaction, long millis, Instant at) {
            if (!doc.containsKey("kind")) {
                doc.put("kind", results.isEmpty() ? "command" : results.get(results.size() - 1).get("kind"));
            }
            doc.put("results", results);
            doc.put("transaction", transaction);
            doc.put("durationMs", millis);
            doc.put("retrievedAt", at.toString());
        }

        boolean truncated() {
            return truncated;
        }

        Long rowCountForAudit() {
            return auditRows;
        }

        Map<String, Object> document() {
            return doc;
        }

        private static void putIfPresent(Map<String, Object> m, String k, String v) {
            if (v != null && !v.isBlank()) {
                m.put(k, v);
            }
        }

        private static int utf8Length(int codePoint) {
            if (codePoint < 0x80) {
                return 1;
            }
            if (codePoint < 0x800) {
                return 2;
            }
            return codePoint < 0x10000 ? 3 : 4;
        }

        /** The longest prefix of {@code v} that fits in {@code maxBytes} UTF-8 bytes, without splitting a character. */
        static String cut(String v, int maxBytes) {
            int bytes = 0;
            int i = 0;
            while (i < v.length()) {
                int cp = v.codePointAt(i);
                int len = utf8Length(cp);
                if (bytes + len > maxBytes) {
                    break;
                }
                bytes += len;
                i += Character.charCount(cp);
            }
            return v.substring(0, i);
        }
    }
}
