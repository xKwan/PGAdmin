package pgweb.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgweb.config.Secret;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Writes audit records (ADR-0005, docs/audit-policy.md §5).
 * <ul>
 *   <li>{@link #require(AuditEvent)}: the record must be in the audit DB before the caller proceeds (an execution
 *       intent). If it can't be written: fail-closed throws {@link AuditUnavailableException} (the operation is
 *       refused); fail-open spools it and lets the operation continue.</li>
 *   <li>{@link #record(AuditEvent)}: best effort (login, logout, denials, outcomes). A failed write goes to the local
 *       spool and is replayed later; the caller is never blocked.</li>
 * </ul>
 * Every failure is logged at ERROR as {@code audit_failure}, which monitoring alerts on (Windows Event Log, P12).
 */
public final class AuditService implements AutoCloseable {

    /** Fail mode (D4): closed in both profiles by default. */
    public enum FailMode { CLOSED, OPEN }

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
    // JSON field names in the spool
    private static final String F_DATA_ADMIN = "dataAdmin";
    private static final String F_BACKEND_PID = "backendPid";
    private static final String F_SQL_LENGTH = "sqlLength";
    private static final String F_ROW_COUNT = "rowCount";
    private static final String F_TRUNCATED = "truncated";
    private static final String F_DURATION_MS = "durationMs";
    private static final String F_SPOOLED_AT = "spooledAt";
    private static final String INSERT = "INSERT INTO audit.event (event_id, event_type, windows_user, user_sid, data_admin, client_ip, "
            + "request_id, app_session_hash, db_session_id, execution_id, intent_id, profile, database_name, pg_session_user, backend_pid, "
            + "statement_class, sql_normalised, sql_hmac, sql_length, parse_status, outcome, cancel_outcome, sqlstate, row_count, truncated, "
            + "duration_ms, tx_before, tx_after, started_at, ended_at, details) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)";

    private final AuditDatabase db;
    private final AuditSpool spool;
    private final FailMode failMode;
    private final byte[] hmacKey;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService replayer;

    public AuditService(AuditDatabase db, AuditSpool spool, FailMode failMode, Secret hmacKey, ObjectMapper mapper, boolean replayInBackground) {
        this.db = db;
        this.spool = spool;
        this.failMode = failMode;
        this.hmacKey = hmacKey.bytes();
        this.mapper = mapper;
        if (replayInBackground) {
            replayer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pgweb-audit-replay");
                t.setDaemon(true);
                return t;
            });
            replayer.scheduleWithFixedDelay(this::replayQuietly, 30, 30, TimeUnit.SECONDS);
        } else {
            replayer = null;
        }
    }

    public FailMode failMode() {
        return failMode;
    }

    /** Must be persisted before the caller proceeds; see the class comment for the fail mode. */
    public void require(AuditEvent e) {
        try {
            insert(e);
        } catch (SQLException ex) {
            LOG.error("audit_failure: required {} record {} could not be written ({})", e.type(), e.id(), ex.getSQLState());
            if (failMode == FailMode.CLOSED) {
                throw new AuditUnavailableException("audit database unavailable", ex);
            }
            spoolOrLog(e);
        }
    }

    /** Best effort: written now, or spooled for replay. Never throws. */
    public void record(AuditEvent e) {
        try {
            insert(e);
        } catch (SQLException ex) {
            LOG.error("audit_failure: {} record {} spooled ({})", e.type(), e.id(), ex.getSQLState());
            spoolOrLog(e);
        }
    }

    /** Keyed hash of an app session ID for correlation; the ID itself is never stored (audit-policy §3). */
    public String sessionHash(String sessionId) {
        return hmacHex("session:" + sessionId).substring(0, 32);
    }

    /** HMAC-SHA256 of SQL text, hex (AUD-03). */
    public String sqlHmac(String sql) {
        return hmacHex("sql:" + sql);
    }

    private String hmacHex(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Number of records waiting in the spool. */
    public long spooled() {
        return spool.size();
    }

    /**
     * Writes spooled records to the audit DB in order. Returns how many were replayed; 0 if the spool is empty or the
     * DB is still down (the spool is kept). The replay is one transaction, so a failure leaves nothing half-written;
     * only a crash between the commit and renaming the spool can replay records twice, and those duplicates share
     * their event_id.
     */
    public synchronized int replay() throws IOException {
        List<JsonNode> pending = spool.pending();
        if (pending.isEmpty()) {
            return 0;
        }
        int done = 0;
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                for (JsonNode n : pending) {
                    bind(ps, fromJson(n));
                    ps.executeUpdate();
                    done++;
                }
            }
            c.commit();
        } catch (SQLException ex) {
            LOG.error("audit_failure: spool replay failed after {} of {} records ({}); will retry", done, pending.size(), ex.getSQLState());
            return 0;
        }
        spool.markReplayed();
        record(AuditEvent.of("audit_spool_replayed").detail("records", done));
        LOG.info("audit spool replayed: {} records", done);
        return done;
    }

    void replayQuietly() {
        try {
            if (spool.size() > 0) {
                replay();
            }
        } catch (IOException | RuntimeException ex) {
            LOG.error("audit_failure: spool replay error: {}", ex.getMessage());
        }
    }

    private void insert(AuditEvent e) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setQueryTimeout(10);
            bind(ps, e);
            ps.executeUpdate();
        }
    }

    private void spoolOrLog(AuditEvent e) {
        try {
            spool.append(toJson(e));
        } catch (IOException ex) {
            LOG.error("audit_failure: record {} ({}) could not be spooled either: {}", e.id(), e.type(), ex.getMessage());
        }
    }

    private void bind(PreparedStatement ps, AuditEvent e) throws SQLException {
        int i = 1;
        ps.setObject(i++, e.eventId);
        ps.setString(i++, e.eventType);
        ps.setString(i++, e.windowsUser);
        ps.setString(i++, e.userSid);
        setBool(ps, i++, e.dataAdmin);
        ps.setString(i++, e.clientIp);
        ps.setString(i++, e.requestId);
        ps.setString(i++, e.appSessionHash);
        ps.setString(i++, e.dbSessionId);
        ps.setObject(i++, e.executionId);
        ps.setObject(i++, e.intentId);
        ps.setString(i++, e.profile);
        ps.setString(i++, e.databaseName);
        ps.setString(i++, e.pgSessionUser);
        setInt(ps, i++, e.backendPid);
        ps.setString(i++, e.statementClass);
        ps.setString(i++, e.sqlNormalised);
        ps.setString(i++, e.sqlHmac);
        setInt(ps, i++, e.sqlLength);
        ps.setString(i++, e.parseStatus);
        ps.setString(i++, e.outcome);
        ps.setString(i++, e.cancelOutcome);
        ps.setString(i++, e.sqlstate);
        setLong(ps, i++, e.rowCount);
        setBool(ps, i++, e.truncated);
        setLong(ps, i++, e.durationMs);
        ps.setString(i++, e.txBefore);
        ps.setString(i++, e.txAfter);
        setTime(ps, i++, e.startedAt);
        setTime(ps, i++, e.endedAt);
        try {
            ps.setString(i, e.details.isEmpty() ? null : mapper.writeValueAsString(e.details));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new SQLException("audit details not serialisable", ex);
        }
    }

    ObjectNode toJson(AuditEvent e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("eventId", e.eventId.toString());
        n.put("eventType", e.eventType);
        n.put("windowsUser", e.windowsUser);
        n.put("userSid", e.userSid);
        n.put(F_DATA_ADMIN, e.dataAdmin);
        n.put("clientIp", e.clientIp);
        n.put("requestId", e.requestId);
        n.put("appSessionHash", e.appSessionHash);
        n.put("dbSessionId", e.dbSessionId);
        n.put("executionId", e.executionId == null ? null : e.executionId.toString());
        n.put("intentId", e.intentId == null ? null : e.intentId.toString());
        n.put("profile", e.profile);
        n.put("databaseName", e.databaseName);
        n.put("pgSessionUser", e.pgSessionUser);
        n.put(F_BACKEND_PID, e.backendPid);
        n.put("statementClass", e.statementClass);
        n.put("sqlNormalised", e.sqlNormalised);
        n.put("sqlHmac", e.sqlHmac);
        n.put(F_SQL_LENGTH, e.sqlLength);
        n.put("parseStatus", e.parseStatus);
        n.put("outcome", e.outcome);
        n.put("cancelOutcome", e.cancelOutcome);
        n.put("sqlstate", e.sqlstate);
        n.put(F_ROW_COUNT, e.rowCount);
        n.put(F_TRUNCATED, e.truncated);
        n.put(F_DURATION_MS, e.durationMs);
        n.put("txBefore", e.txBefore);
        n.put("txAfter", e.txAfter);
        n.put("startedAt", e.startedAt == null ? null : e.startedAt.toString());
        n.put("endedAt", e.endedAt == null ? null : e.endedAt.toString());
        n.set("details", mapper.valueToTree(e.details));
        n.put(F_SPOOLED_AT, Instant.now().toString());
        return n;
    }

    AuditEvent fromJson(JsonNode n) {
        AuditEvent e = AuditEvent.restore(UUID.fromString(n.path("eventId").asText()), n.path("eventType").asText());
        e.windowsUser = text(n, "windowsUser");
        e.userSid = text(n, "userSid");
        e.dataAdmin = n.path(F_DATA_ADMIN).isBoolean() ? n.path(F_DATA_ADMIN).asBoolean() : null;
        e.clientIp = text(n, "clientIp");
        e.requestId = text(n, "requestId");
        e.appSessionHash = text(n, "appSessionHash");
        e.dbSessionId = text(n, "dbSessionId");
        e.executionId = uuid(n, "executionId");
        e.intentId = uuid(n, "intentId");
        e.profile = text(n, "profile");
        e.databaseName = text(n, "databaseName");
        e.pgSessionUser = text(n, "pgSessionUser");
        e.backendPid = n.path(F_BACKEND_PID).isInt() ? n.path(F_BACKEND_PID).asInt() : null;
        e.statementClass = text(n, "statementClass");
        e.sqlNormalised = text(n, "sqlNormalised");
        e.sqlHmac = text(n, "sqlHmac");
        e.sqlLength = n.path(F_SQL_LENGTH).isInt() ? n.path(F_SQL_LENGTH).asInt() : null;
        e.parseStatus = text(n, "parseStatus");
        e.outcome = text(n, "outcome");
        e.cancelOutcome = text(n, "cancelOutcome");
        e.sqlstate = text(n, "sqlstate");
        e.rowCount = n.path(F_ROW_COUNT).isNumber() ? n.path(F_ROW_COUNT).asLong() : null;
        e.truncated = n.path(F_TRUNCATED).isBoolean() ? n.path(F_TRUNCATED).asBoolean() : null;
        e.durationMs = n.path(F_DURATION_MS).isNumber() ? n.path(F_DURATION_MS).asLong() : null;
        e.txBefore = text(n, "txBefore");
        e.txAfter = text(n, "txAfter");
        String s = text(n, "startedAt");
        e.startedAt = s == null ? null : Instant.parse(s);
        String en = text(n, "endedAt");
        e.endedAt = en == null ? null : Instant.parse(en);
        for (var f : n.path("details").properties()) {
            e.details.put(f.getKey(), mapper.convertValue(f.getValue(), Object.class));
        }
        e.details.put(F_SPOOLED_AT, n.path(F_SPOOLED_AT).asText());
        return e;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    private static void setBool(PreparedStatement ps, int i, Boolean v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BOOLEAN);
        } else {
            ps.setBoolean(i, v);
        }
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setTime(PreparedStatement ps, int i, Instant v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(i, Timestamp.from(v));
        }
    }

    @Override
    public void close() {
        if (replayer != null) {
            replayer.shutdownNow();
        }
    }
}
