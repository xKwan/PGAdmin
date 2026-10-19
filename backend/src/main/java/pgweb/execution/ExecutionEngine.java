package pgweb.execution;

import org.postgresql.core.BaseConnection;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.audit.AuditUnavailableException;
import pgweb.config.Config;
import pgweb.dbsession.DbSession;
import pgweb.dbsession.DbSessionManager;
import pgweb.dbsession.StatementRunner;
import pgweb.security.UserContext;
import pgweb.sqlparse.SqlParser;
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
import java.util.LinkedHashSet;
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
 * Runs user SQL on a DB session (plan §3, Phase 7; admin Phase 2). Every control lives here, on the server:
 * <ul>
 *   <li>Kill switch: {@code execution.enabled} (EXE-01).</li>
 *   <li>One statement at a time per session (EXE-08): a second request gets 409.</li>
 *   <li>The single-statement gate before anything is sent ({@link StatementGate}). A script (EXE-03) is split by
 *       PostgreSQL's parser and every statement passes the same gate; they are sent one at a time and the script
 *       stops at the first statement that doesn't succeed.</li>
 *   <li>Audit intent committed <b>before</b> each statement (and before each further page of rows); fail-closed
 *       refuses it when it can't be written (AUD-01/02). Outcome after; an unknown result is recorded as
 *       {@code indeterminate}.</li>
 *   <li>Bounded retrieval: at most {@code maxRows} rows per response, cells and the whole response capped in bytes.
 *       Inside a transaction the rest of a large result stays on a server-side cursor and is fetched a page at a
 *       time, up to {@code maxFetchedRows} in all (P9-3). SQL is never rewritten (EXE-05).</li>
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
    private static final String EV_REJECTED = "exec_rejected";
    private static final String CONFIRMED = "confirmed";
    private static final String ESCALATED = "escalated_terminated";
    private static final String UNCONFIRMED = "unconfirmed";
    private static final String INDETERMINATE = "indeterminate";
    private static final String SUCCESS = "success";
    private static final String ERROR = "error";
    private static final String EXECUTION_ID = "executionId";
    private static final String REASON = "reason";
    private static final String CANCEL_REQUESTED = "cancel_requested";
    private static final String REQUEST_ID = "requestId";
    private static final String SCRIPT_ID = "scriptId";
    private static final String STATEMENT_CLASS = "statementClass";
    private static final String OPEN = "OPEN";
    private static final String IDLE = "IDLE";
    private static final String KIND = "kind";
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
    private final Map<String, ScriptRun> scripts = new ConcurrentHashMap<>();

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

    /** A running script: cancel stops the current statement and keeps the rest from starting. */
    static final class ScriptRun {
        final UUID scriptId = UUID.randomUUID();
        volatile int total;
        volatile int current;
        volatile boolean cancelled;
    }

    /**
     * The rest of a row-returning statement's result, still on the server (P9-3). Exists only while the session's
     * transaction is open; closed by the next statement, the end of the rows, the row cap or the session's end.
     */
    static final class Cursor implements AutoCloseable {
        final UUID executionId;
        final Statement statement;
        final ResultSet rows;
        final String statementClass;
        final String hmac;
        final int length;
        long fetched;
        int pages = 1;

        Cursor(UUID executionId, Statement statement, ResultSet rows, String statementClass, String hmac, int length, long fetched) {
            this.executionId = executionId;
            this.statement = statement;
            this.rows = rows;
            this.statementClass = statementClass;
            this.hmac = hmac;
            this.length = length;
            this.fetched = fetched;
        }

        @Override
        public void close() {
            try {
                rows.close();
            } catch (SQLException ignored) {
                // the connection may be gone
            }
            closeQuietly(statement);
        }
    }

    /**
     * Executes {@code sql} on the user's session and returns the result document (see {@link ResultBuilder}).
     * @param confirmed the user confirmed a destructive statement (see {@link StatementGate#destructiveReasons})
     */
    @Override
    public Map<String, Object> execute(UserContext user, String sessionId, String sql, boolean confirmed, String requestId) {
        DbSession s = lockedSession(user, sessionId);
        try {
            closeCursor(s);
            return executeLocked(user, s, sql, confirmed, requestId);
        } finally {
            s.lock().unlock();
        }
    }

    /** The caller's session with its statement lock held, after the kill switch; the caller unlocks. */
    private DbSession lockedSession(UserContext user, String sessionId) {
        if (!config.executionEnabled()) {
            throw ApiException.forbidden("EXECUTION_DISABLED", "Running SQL is switched off by the administrator");
        }
        DbSession s = sessions.use(user, sessionId);
        if (!s.lock().tryLock()) {
            throw ApiException.conflict("SESSION_BUSY", "A statement is already running in this session; wait for it or cancel it");
        }
        return s;
    }

    /**
     * Begin (with an isolation level and read-only option), commit or rollback: the plan's manual-transaction
     * controls (section 4). The statement text is built here from allow-listed values only, and then runs like any
     * other statement: gate, audit, limits.
     */
    @Override
    public Map<String, Object> transaction(UserContext user, String sessionId, String action, String isolation, boolean readOnly, String requestId) {
        String sql = switch (action == null ? "" : action) {
            case "begin" -> "BEGIN" + isolationClause(isolation) + (readOnly ? " READ ONLY" : "");
            case "commit" -> "COMMIT";
            case "rollback" -> "ROLLBACK";
            default -> throw ApiException.badRequest("INVALID_ACTION", "action must be begin, commit or rollback");
        };
        return execute(user, sessionId, sql, true, requestId);
    }

    static String isolationClause(String isolation) {
        if (isolation == null || isolation.isBlank()) {
            return "";
        }
        return switch (isolation.toLowerCase(Locale.ROOT)) {
            case "read committed" -> " ISOLATION LEVEL READ COMMITTED";
            case "repeatable read" -> " ISOLATION LEVEL REPEATABLE READ";
            case "serializable" -> " ISOLATION LEVEL SERIALIZABLE";
            default -> throw ApiException.badRequest("INVALID_ISOLATION", "isolation must be read committed, repeatable read or serializable");
        };
    }

    /**
     * Before a session is closed while a statement runs: cancel it (and the rest of a running script) and wait (up to
     * the cancel grace period plus a second) for it to end, so the close doesn't cut the connection under a running
     * statement.
     */
    @Override
    public void stopIfRunning(UserContext user, String sessionId) {
        DbSession s = sessions.get(user, sessionId);
        ScriptRun script = scripts.get(s.id());
        if (script != null) {
            script.cancelled = true;
        }
        Running run = running.get(s.id());
        if (run == null) {
            return;
        }
        audit.record(AuditEvent.of(CANCEL_REQUESTED).user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .execution(run.executionId, null).detail(REASON, "session_close"));
        stop(s, run, "cancelled");
        try {
            if (s.lock().tryLock(config.limits().cancelGrace().toMillis() + 1000, TimeUnit.MILLISECONDS)) {
                s.lock().unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> executeLocked(UserContext user, DbSession s, String sql, boolean confirmed, String requestId) {
        boolean scs = standardConformingStrings(s.main());
        StatementGate.Verdict v = gate.check(sql, scs);
        String hmac = sql == null ? null : audit.sqlHmac(sql);
        int length = sql == null ? 0 : sql.length();
        if (!v.approved()) {
            throw rejected(user, s, v, hmac, length, requestId);
        }
        List<String> reasons = confirmationReasons(s, confirmed, List.of(v.statement()));
        if (!reasons.isEmpty()) {
            throw confirmationRequired(user, s, v.statementClass(), hmac, length, reasons, requestId, Map.of());
        }
        return runApproved(user, s, sql, v, scs, hmac, requestId, Map.of(), config.limits().maxResponseBytes(), null).result().document();
    }

    /** Destructive-statement reasons that still need the user's confirmation (empty when confirmed or not required). */
    private List<String> confirmationReasons(DbSession s, boolean confirmed, List<SqlParser.Statement> statements) {
        if (confirmed || !config.profiles().get(s.profile()).confirmDestructive()) {
            return List.of();
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (SqlParser.Statement st : statements) {
            reasons.addAll(StatementGate.destructiveReasons(st));
        }
        return List.copyOf(reasons);
    }

    private ApiException confirmationRequired(UserContext user, DbSession s, String statementClass, String hmac, int length,
                                              List<String> reasons, String requestId, Map<String, Object> extra) {
        AuditEvent e = AuditEvent.of(EV_REJECTED).user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .statement(statementClass, null, hmac, length, "confirmation_required").detail("reasons", reasons).detail(REQUEST_ID, requestId);
        extra.forEach(e::detail);
        audit.record(e);
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("reasons", reasons);
        details.put(STATEMENT_CLASS, statementClass);
        return new ApiException(409, "CONFIRMATION_REQUIRED", "This statement can change or remove data or objects. Confirm to run it.", details);
    }

    /** One statement that ran (or was stopped): its result document, how it ended, and whether the session ended. */
    private record Run(ResultBuilder result, Ended ended, boolean sessionEnded) {
    }

    /**
     * Runs one statement the gate approved: audit intent (fail-closed), the statement under the timer, outcome. A single
     * statement ({@code script} null) in an open transaction is read through a cursor and the rest of its rows stays on
     * the server for {@link #fetch}. {@code extra} goes into both audit records (a script's ID and the statement's place
     * in it).
     */
    private Run runApproved(UserContext user, DbSession s, String sql, StatementGate.Verdict v, boolean scs, String hmac, String requestId,
                            Map<String, Object> extra, long byteBudget, ScriptRun script) {
        int length = sql.length();
        String txBefore = s.transactionState();
        String normalised = scs ? gateText(v.statement().text()) : null;
        boolean stream = script == null && OPEN.equals(txBefore) && !autoCommit(s.main());
        Statement st = newStatement(s, stream);
        Running run = new Running(st, clock.instant());
        Instant start = run.startedAt;
        // AUD-01: the intent is committed before anything is sent. Fail-closed throws here (503).
        try {
            AuditEvent intent = AuditEvent.of("exec_intent").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                    .execution(run.executionId, null).statement(v.statementClass(), normalised, hmac, length, scs ? "ok" : "ok_no_text")
                    .transaction(txBefore, null).times(start, null).detail(REQUEST_ID, requestId);
            extra.forEach(intent::detail);
            audit.require(intent);
        } catch (RuntimeException e) {
            closeQuietly(st);
            throw e;
        }
        ResultBuilder result = new ResultBuilder(config.limits(), run.executionId, v.statementClass(), byteBudget);
        ResultSet[] kept = new ResultSet[1];
        Ended ended = timed(s, run, result, script, () -> {
            boolean hasRows = run.statement.execute(sql);
            kept[0] = readAll(run.statement, hasRows, result, stream);
        });
        if (kept[0] != null && SUCCESS.equals(ended.outcome())) {
            s.cursor(new Cursor(run.executionId, st, kept[0], v.statementClass(), hmac, length, result.rowCountForAudit()));
            result.more();
        } else {
            closeQuietly(st);
        }
        Instant end = clock.instant();
        String txAfter = s.transactionState();
        syncAutoCommit(s, txAfter);
        sessions.transactionObserved(s, !IDLE.equals(txAfter));
        if ("FAILED".equals(txBefore) && IDLE.equals(txAfter) && StatementGate.isCommit(v.statement())) {
            result.notice("The transaction had failed, so COMMIT rolled it back. Nothing was saved.");
        }
        result.finish(txAfter, Duration.between(start, end).toMillis(), end);
        AuditEvent outcome = AuditEvent.of(EV_OUTCOME).user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .execution(run.executionId, run.executionId).statement(v.statementClass(), null, hmac, length, null)
                .outcome(ended.outcome(), safeSqlstate(ended.sqlstate()), result.rowCountForAudit(), result.truncated(),
                        Duration.between(start, end).toMillis())
                .cancel(run.cancelOutcome).transaction(txBefore, txAfter).times(start, end);
        extra.forEach(outcome::detail);
        audit.record(outcome);
        boolean untrusted = quarantineIfUntrusted(s, run, ended);
        if (untrusted) {
            result.sessionEnded();
        }
        return new Run(result, ended, untrusted);
    }

    /** After a lost connection or an escalated or unconfirmed stop the session can't be trusted: it is closed. */
    private boolean quarantineIfUntrusted(DbSession s, Running run, Ended ended) {
        boolean lost = INDETERMINATE.equals(ended.outcome());
        if (lost || ESCALATED.equals(run.cancelOutcome) || UNCONFIRMED.equals(run.cancelOutcome)) {
            sessions.quarantine(s, lost ? "connection_lost" : "terminated_after_" + run.stopReason);
            return true;
        }
        return false;
    }

    // ---- scripts (EXE-03) ---------------------------------------------

    /**
     * Runs a script (EXE-03, decided 2026-10-19): PostgreSQL's parser finds the statements, each passes the
     * single-statement gate, and nothing runs unless all of them pass. A destructive statement anywhere needs one
     * confirmation for the script. The statements are then sent one at a time, each with its own audit intent
     * (fail-closed) and outcome, its own timer, and the session's normal transaction behaviour; the script stops at
     * the first statement that doesn't succeed, and the rest are reported as not run.
     */
    @Override
    public Map<String, Object> script(UserContext user, String sessionId, String sql, boolean confirmed, String requestId) {
        DbSession s = lockedSession(user, sessionId);
        ScriptRun script = new ScriptRun();
        scripts.put(s.id(), script);
        try {
            closeCursor(s);
            return scriptLocked(user, s, sql, confirmed, requestId, script);
        } finally {
            scripts.remove(s.id());
            s.lock().unlock();
        }
    }

    private Map<String, Object> scriptLocked(UserContext user, DbSession s, String sql, boolean confirmed, String requestId, ScriptRun script) {
        boolean scs = standardConformingStrings(s.main());
        String hmac = sql == null ? null : audit.sqlHmac(sql);
        int length = sql == null ? 0 : sql.length();
        StatementGate.ScriptVerdict sv = gate.checkScript(sql, scs, config.limits().maxScriptStatements());
        if (!sv.approved()) {
            throw scriptRejected(user, s, sv, hmac, length, requestId);
        }
        List<StatementGate.ScriptStatement> items = sv.statements();
        int n = items.size();
        script.total = n;
        List<String> reasons = confirmationReasons(s, confirmed, items.stream().map(i -> i.verdict().statement()).toList());
        if (!reasons.isEmpty()) {
            throw confirmationRequired(user, s, "script", hmac, length, reasons, requestId, Map.of("statements", n));
        }
        Instant start = clock.instant();
        long budget = config.limits().maxResponseBytes();
        List<Map<String, Object>> out = new ArrayList<>(n);
        String stopReason = null;
        int stoppedAt = -1;
        int completed = 0;
        boolean sessionEnded = false;
        for (int i = 0; i < n; i++) {
            StatementGate.ScriptStatement item = items.get(i);
            String text = item.verdict().statement().text();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            entry.put("offset", item.offset());
            entry.put("length", text.length());
            entry.put(STATEMENT_CLASS, item.verdict().statementClass());
            if (stopReason == null && script.cancelled) {
                stopReason = "cancelled";
                stoppedAt = i;
            } else if (stopReason == null && standardConformingStrings(s.main()) != scs) {
                // The statement boundaries were found with the old setting; the rest can't be trusted to split the same way.
                stopReason = "settings_changed";
                stoppedAt = i;
            }
            if (stopReason != null) {
                entry.put(KIND, "not_run");
                out.add(entry);
                continue;
            }
            script.current = i;
            Map<String, Object> extra = Map.of(SCRIPT_ID, script.scriptId.toString(), "statementIndex", i, "statements", n);
            try {
                Run r = runApproved(user, s, text, item.verdict(), scs, audit.sqlHmac(text), requestId, extra, budget, script);
                budget = Math.max(0, budget - r.result().bytesUsed());
                entry.putAll(r.result().document());
                entry.put(STATEMENT_CLASS, item.verdict().statementClass());
                if (SUCCESS.equals(r.ended().outcome())) {
                    completed++;
                } else {
                    stopReason = r.ended().outcome();
                    stoppedAt = i;
                }
                if (r.sessionEnded()) {
                    sessionEnded = true;
                    if (stopReason == null) {
                        stopReason = "session_ended";
                        stoppedAt = i + 1;
                    }
                }
            } catch (AuditUnavailableException e) {
                // Fail-closed: this statement was not sent, and nothing after it runs.
                stopReason = "audit_unavailable";
                stoppedAt = i;
                entry.put(KIND, "not_run");
                entry.put(ERROR, Map.of("code", "AUDIT_UNAVAILABLE", "message", "The audit database is unavailable, so this statement was not run."));
            } catch (ApiException e) {
                stopReason = "session_ended";
                stoppedAt = i;
                sessionEnded = true;
                entry.put(KIND, "not_run");
                entry.put(ERROR, Map.of("code", e.code(), "message", e.getMessage()));
            }
            out.add(entry);
            if (sessionEnded) {
                for (int j = i + 1; j < n; j++) {
                    StatementGate.ScriptStatement rest = items.get(j);
                    out.add(new LinkedHashMap<>(Map.of("index", j, "offset", rest.offset(), "length", rest.verdict().statement().text().length(),
                            STATEMENT_CLASS, rest.verdict().statementClass(), KIND, "not_run")));
                }
                break;
            }
        }
        Instant end = clock.instant();
        String tx = s.state() == DbSession.State.OPEN ? s.transactionState() : "UNKNOWN";
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(KIND, "script");
        doc.put(SCRIPT_ID, script.scriptId.toString());
        doc.put(STATEMENT_CLASS, "script");
        doc.put("statements", out);
        doc.put("total", n);
        doc.put("completed", completed);
        if (stopReason != null) {
            doc.put("stopReason", stopReason);
            doc.put("stoppedAt", stoppedAt);
        }
        doc.put("transaction", tx);
        doc.put("durationMs", Duration.between(start, end).toMillis());
        doc.put("retrievedAt", end.toString());
        if (sessionEnded) {
            doc.put("sessionEnded", true);
        }
        audit.record(AuditEvent.of("script_outcome").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .statement("script", null, hmac, length, null)
                .outcome(stopReason == null ? SUCCESS : stopReason, null, null, null, Duration.between(start, end).toMillis())
                .times(start, end).detail(SCRIPT_ID, script.scriptId.toString()).detail("statements", n).detail("completed", completed)
                .detail(REQUEST_ID, requestId));
        return doc;
    }

    private ApiException scriptRejected(UserContext user, DbSession s, StatementGate.ScriptVerdict v, String hmac, int length, String requestId) {
        AuditEvent e = AuditEvent.of(EV_REJECTED).user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .statement("script", null, hmac, length, v.rejection().name().toLowerCase(Locale.ROOT)).detail(REQUEST_ID, requestId);
        if (v.statementIndex() >= 0) {
            e.detail("statementIndex", v.statementIndex());
        }
        audit.record(e);
        int status = v.rejection() == StatementGate.Rejection.TOO_LARGE ? 413 : 400;
        Map<String, Object> err = new LinkedHashMap<>();
        if (v.position() > 0) {
            err.put("position", v.position());
        }
        if (v.statementIndex() >= 0) {
            err.put("statementIndex", v.statementIndex());
        }
        return new ApiException(status, v.rejection().name(), v.message(), err);
    }

    // ---- further pages of a result (P9-3) -----------------------------

    /**
     * The next page of the last statement's rows, from the cursor left open inside the session's transaction. Each
     * page is an execution of its own for the audit ({@code exec_fetch}, fail-closed, linked to the statement's
     * execution ID; then {@code exec_fetch_outcome}), under the same timer and cancel as a statement.
     */
    @Override
    public Map<String, Object> fetch(UserContext user, String sessionId, String executionId, String requestId) {
        DbSession s = lockedSession(user, sessionId);
        try {
            return fetchLocked(user, s, executionId, requestId);
        } finally {
            s.lock().unlock();
        }
    }

    private Map<String, Object> fetchLocked(UserContext user, DbSession s, String executionId, String requestId) {
        Cursor c = s.cursor() instanceof Cursor k ? k : null;
        if (c == null || !c.executionId.toString().equals(executionId)) {
            throw cursorClosed();
        }
        String txBefore = s.transactionState();
        if (!OPEN.equals(txBefore)) {
            closeCursor(s);
            throw cursorClosed();
        }
        Running run = new Running(c.statement, clock.instant());
        Instant start = run.startedAt;
        long before = c.fetched;
        audit.require(AuditEvent.of("exec_fetch").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .execution(run.executionId, c.executionId).statement(c.statementClass, null, c.hmac, c.length, null)
                .transaction(txBefore, null).times(start, null).detail("page", c.pages + 1).detail("rowsBefore", before)
                .detail(REQUEST_ID, requestId));
        ResultBuilder result = new ResultBuilder(config.limits(), c.executionId, c.statementClass, config.limits().maxResponseBytes());
        int pageRows = (int) Math.min(config.limits().maxRows(), config.limits().maxFetchedRows() - before);
        boolean[] more = new boolean[1];
        Ended ended = timed(s, run, result, null, () -> more[0] = result.page(c.rows, pageRows));
        long got = result.rowCountForAudit() == null ? 0 : result.rowCountForAudit();
        c.fetched += got;
        c.pages++;
        if (SUCCESS.equals(ended.outcome()) && more[0] && c.fetched < config.limits().maxFetchedRows()) {
            result.more();
        } else {
            if (SUCCESS.equals(ended.outcome()) && more[0]) {
                result.limitReached();
            }
            closeCursor(s);
        }
        Instant end = clock.instant();
        String txAfter = s.transactionState();
        syncAutoCommit(s, txAfter);
        sessions.transactionObserved(s, !IDLE.equals(txAfter));
        result.finish(txAfter, Duration.between(start, end).toMillis(), end);
        result.document().put("fetchId", run.executionId.toString());
        result.document().put("rowsBefore", before);
        audit.record(AuditEvent.of("exec_fetch_outcome").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .execution(run.executionId, run.executionId).statement(c.statementClass, null, c.hmac, c.length, null)
                .outcome(ended.outcome(), safeSqlstate(ended.sqlstate()), got, result.truncated(), Duration.between(start, end).toMillis())
                .cancel(run.cancelOutcome).transaction(txBefore, txAfter).times(start, end));
        if (quarantineIfUntrusted(s, run, ended)) {
            result.sessionEnded();
        }
        return result.document();
    }

    private static ApiException cursorClosed() {
        return ApiException.conflict("CURSOR_CLOSED", "The rest of these rows is no longer available. Run the statement again to see them.");
    }

    /** Closes the session's open cursor, if any (only while holding the session's lock). */
    private static void closeCursor(DbSession s) {
        AutoCloseable c = s.cursor();
        if (c != null) {
            s.cursor(null);
            try {
                c.close();
            } catch (Exception e) {
                LOG.debug("closing the cursor of session {} failed: {}", s.id(), e.getMessage());
            }
        }
    }

    /**
     * pgJDBC reads rows through a cursor only with autocommit off. pgweb mirrors PostgreSQL's own transaction state, so
     * the driver never begins or commits anything by itself: autocommit is switched off only while a transaction is
     * open (pgJDBC sends no BEGIN then, as one is already open) and on again once it has ended (pgJDBC commits only a
     * transaction that is still open, and there is none then). Called with the session's lock held.
     */
    static void syncAutoCommit(DbSession s, String transaction) {
        try {
            Connection c = s.main();
            if (IDLE.equals(transaction) && !c.getAutoCommit()) {
                c.setAutoCommit(true);
            } else if ((OPEN.equals(transaction) || "FAILED".equals(transaction)) && c.getAutoCommit()) {
                c.setAutoCommit(false);
            }
        } catch (SQLException e) {
            LOG.debug("could not align autocommit for session {}: {}", s.id(), e.getSQLState());
        }
    }

    private static boolean autoCommit(Connection c) {
        try {
            return c.getAutoCommit();
        } catch (SQLException e) {
            return true;
        }
    }

    // ---- shared -------------------------------------------------------

    /** How a statement ended: success, error, timeout, cancelled or indeterminate, with the SQLSTATE if any. */
    private record Ended(String outcome, String sqlstate) {
    }

    private ApiException rejected(UserContext user, DbSession s, StatementGate.Verdict v, String hmac, int length, String requestId) {
        audit.record(AuditEvent.of(EV_REJECTED).user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .statement(null, null, hmac, length, v.rejection().name().toLowerCase(Locale.ROOT)).detail(REQUEST_ID, requestId));
        int status = v.rejection() == StatementGate.Rejection.TOO_LARGE ? 413 : 400;
        Map<String, Object> err = v.position() > 0 ? Map.of("position", v.position()) : Map.of();
        return new ApiException(status, v.rejection().name(), v.message(), err);
    }

    private Statement newStatement(DbSession s, boolean stream) {
        try {
            Statement st = s.main().createStatement();
            st.setEscapeProcessing(false); // ADR-0009 J4
            if (stream) {
                // A page at a time from the server; the total is capped by maxFetchedRows (one more to see that there is more).
                st.setFetchSize(config.limits().maxRows());
                st.setMaxRows(config.limits().maxFetchedRows() + 1);
            } else {
                st.setMaxRows(config.limits().maxRows() + 1);
            }
            return st;
        } catch (SQLException e) {
            throw sessionLost(s);
        }
    }

    /** Database work done under the statement timer. */
    private interface Work {
        void run() throws SQLException;
    }

    /**
     * Runs {@code work} with the timer armed and cancel possible; never throws for a failed statement. A script that
     * was cancelled just before this statement started doesn't send it at all.
     */
    private Ended timed(DbSession s, Running run, ResultBuilder result, ScriptRun script, Work work) {
        running.put(s.id(), run);
        if (script != null && script.cancelled) {
            synchronized (run.lock) {
                run.stopReason = "cancelled";
            }
            running.remove(s.id());
            settle(run);
            result.stopped(run.stopReason, run.cancelOutcome);
            return new Ended(run.stopReason, null);
        }
        Duration limit = config.profiles().get(s.profile()).statementTimeout();
        ScheduledFuture<?> timer = timers.schedule(() -> stop(s, run, "timeout"), limit.toMillis(), TimeUnit.MILLISECONDS);
        Ended ended;
        try {
            work.run();
            ended = new Ended(SUCCESS, null);
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
        }
        if (ended.outcome().equals(run.stopReason)) {
            result.stopped(run.stopReason, run.cancelOutcome);
        }
        return ended;
    }

    private static String safeSqlstate(String sqlstate) {
        return sqlstate != null && sqlstate.matches("[0-9A-Z]{5}") ? sqlstate : null;
    }

    /** Cancel requested by the user (the cancel endpoint; doesn't need the session lock). Also stops a running script. */
    @Override
    public Map<String, Object> cancel(UserContext user, String sessionId) {
        DbSession s = sessions.get(user, sessionId);
        ScriptRun script = scripts.get(s.id());
        Running run = running.get(s.id());
        if (run == null && script == null) {
            throw ApiException.conflict("NOT_RUNNING", "No statement is running in this session");
        }
        AuditEvent e = AuditEvent.of(CANCEL_REQUESTED).user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .execution(run == null ? null : run.executionId, null);
        if (script != null) {
            script.cancelled = true;
            e.detail(SCRIPT_ID, script.scriptId.toString());
        }
        audit.record(e);
        if (run != null) {
            stop(s, run, "cancelled");
        }
        return Map.of(EXECUTION_ID, (run == null ? script.scriptId : run.executionId).toString(), "status", CANCEL_REQUESTED);
    }

    /**
     * Whether a statement is running on the session, for how long, and what its backend waits for, read from
     * {@code pg_stat_activity} on the companion connection (the user can see their own backends). Polled by the UI
     * about once a second while a statement runs; not audited (it changes nothing). Degraded sessions report no wait
     * information. During a script, which statement of how many is running.
     */
    @Override
    public Map<String, Object> activity(UserContext user, String sessionId) {
        DbSession s = sessions.get(user, sessionId);
        Running run = running.get(s.id());
        ScriptRun script = scripts.get(s.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", run != null || script != null);
        if (script != null && script.total > 0) {
            m.put("scriptStatement", script.current + 1);
            m.put("scriptStatements", script.total);
        }
        if (run == null) {
            return m;
        }
        m.put(EXECUTION_ID, run.executionId.toString());
        m.put("elapsedMs", Duration.between(run.startedAt, clock.instant()).toMillis());
        Connection companion = s.companion();
        if (companion == null || !s.companionLock().tryLock()) {
            return m; // degraded, or the object browser is using the companion: no wait details this time
        }
        try (PreparedStatement ps = companion.prepareStatement(
                "SELECT wait_event_type, wait_event FROM pg_catalog.pg_stat_activity WHERE pid = ?")) {
            ps.setInt(1, s.mainPid());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    m.put("waitEventType", rs.getString(1));
                    m.put("waitEvent", rs.getString(2));
                    m.put("lockWait", "Lock".equals(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            LOG.debug("activity query failed for session {}: {}", s.id(), e.getSQLState());
        } finally {
            s.companionLock().unlock();
        }
        return m;
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

    /**
     * Reads every result of the statement. With {@code keepOpen} (a cursor inside a transaction), a row result that
     * has more rows than one response holds is left open and returned, positioned on its first unread row; otherwise
     * everything is closed and null is returned.
     */
    private static ResultSet readAll(Statement st, boolean hasRows, ResultBuilder result, boolean keepOpen) throws SQLException {
        boolean rows = hasRows;
        while (true) {
            if (rows) {
                ResultSet rs = st.getResultSet();
                boolean unread;
                try {
                    unread = result.rows(rs);
                } catch (SQLException | RuntimeException e) {
                    rs.close();
                    throw e;
                }
                if (keepOpen && unread) {
                    return rs;
                }
                rs.close();
            } else {
                int count = st.getUpdateCount();
                if (count == -1) {
                    break;
                }
                result.command(count);
            }
            rows = st.getMoreResults();
        }
        return null;
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
        private final long budget;
        private final Map<String, Object> doc = new LinkedHashMap<>();
        private final List<Map<String, Object>> results = new ArrayList<>();
        private long bytes;
        private boolean truncated;
        private Long auditRows;

        ResultBuilder(Config.Limits limits, UUID executionId, String statementClass) {
            this(limits, executionId, statementClass, limits.maxResponseBytes());
        }

        /** {@code budget}: response bytes this document may use (a script shares one budget across its statements). */
        ResultBuilder(Config.Limits limits, UUID executionId, String statementClass, long budget) {
            this.limits = limits;
            this.budget = budget;
            doc.put(EXECUTION_ID, executionId.toString());
            doc.put(STATEMENT_CLASS, statementClass);
        }

        /** Reads a result set from its start; true when it stopped on a row it didn't keep (there is more). */
        boolean rows(ResultSet rs) throws SQLException {
            return read(rs, false, limits.maxRows());
        }

        /**
         * The next page of an open cursor, which is positioned on its first unread row: at most {@code maxRows} rows.
         * True when there are more.
         */
        boolean page(ResultSet rs, int maxRows) throws SQLException {
            return read(rs, true, maxRows);
        }

        private boolean read(ResultSet rs, boolean onRow, int maxRows) throws SQLException {
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
            boolean positioned = onRow;
            while (reason == null && (positioned || rs.next())) {
                positioned = false;
                if (rows.size() >= maxRows) {
                    reason = "rows"; // the row now current is not kept: there is more
                } else {
                    reason = keep(rs, n, rows, cutCells);
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(KIND, "rows");
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
            return reason != null;
        }

        /** Reads the current row and keeps it if it fits the response limit; returns "bytes" when it doesn't. */
        private String keep(ResultSet rs, int n, List<List<String>> rows, List<List<Integer>> cutCells) throws SQLException {
            List<Integer> cutHere = new ArrayList<>();
            List<String> row = readRow(rs, n, cutHere);
            long rowBytes = estimatedBytes(row);
            if (bytes + rowBytes > budget) {
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
            r.put(KIND, "command");
            if (count >= 0) {
                r.put("rowCount", count);
                auditRows = (long) count;
            }
            results.add(r);
        }

        /** The last row result continues on the server: the client may fetch the next page. */
        void more() {
            if (!results.isEmpty()) {
                results.get(results.size() - 1).put("more", true);
            }
        }

        /** The last row result has more rows, but the per-statement cap on fetched rows was reached. */
        void limitReached() {
            if (!results.isEmpty()) {
                Map<String, Object> r = results.get(results.size() - 1);
                r.put("truncated", true);
                r.put("truncatedBy", "limit");
                truncated = true;
            }
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
                putIfPresent(err, "constraint", m.getConstraint());
                putIfPresent(err, "schema", m.getSchema());
                putIfPresent(err, "table", m.getTable());
                putIfPresent(err, "column", m.getColumn());
                putIfPresent(err, "severity", m.getSeverity());
            }
            err.put("message", message);
            doc.put(KIND, ERROR);
            doc.put(ERROR, err);
        }

        void stopped(String reason, String cancelOutcome) {
            doc.put(KIND, reason);
            if (cancelOutcome != null) {
                doc.put("cancelOutcome", cancelOutcome);
            }
        }

        void indeterminate() {
            doc.put(KIND, INDETERMINATE);
            doc.put("message", "The connection was lost while the statement ran. It may or may not have taken effect.");
        }

        /** A note from the app about what happened (for example COMMIT on a failed transaction). */
        void notice(String text) {
            doc.put("notice", text);
        }

        void sessionEnded() {
            doc.put("sessionEnded", true);
        }

        void finish(String transaction, long millis, Instant at) {
            if (!doc.containsKey(KIND)) {
                doc.put(KIND, results.isEmpty() ? "command" : results.get(results.size() - 1).get(KIND));
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

        /** Estimated response bytes used so far. */
        long bytesUsed() {
            return bytes;
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
