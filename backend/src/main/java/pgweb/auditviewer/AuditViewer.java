package pgweb.auditviewer;

import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.config.Config;
import pgweb.dbsession.DbSessionManager;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The audit viewer (admin Phase 9, decision A14): a read-only list of audit events for named auditors.
 * <ul>
 *   <li>pgweb reads nothing with its own account: each request opens a short-lived connection to the audit database
 *       <b>as the signed-in user</b> (Kerberos delegation, as for database sessions), so PostgreSQL decides who may
 *       read: members of {@code pgweb_audit_reader} (ADR-0005, unchanged). The app role stays INSERT-only.</li>
 *   <li>Every search is recorded first ({@code audit_view}, with its filters), fail-closed: no record, no search.</li>
 *   <li>One constant query; every filter is a bound parameter; a read-only transaction with a statement timeout;
 *       at most {@link #MAX_LIMIT} rows per page and {@link #MAX_RANGE} per search.</li>
 *   <li>Off unless {@code auditViewer.enabled=true}.</li>
 * </ul>
 */
public final class AuditViewer {

    /** Newest first, filtered; paging by {@code seq}. */
    static final String EVENTS = """
            SELECT seq, occurred_at, event_type, windows_user, user_sid, client_ip, profile, database_name, pg_session_user,
                   statement_class, sql_normalised, outcome, sqlstate, row_count, duration_ms, execution_id, db_session_id,
                   request_id, details::text
            FROM audit.event
            WHERE occurred_at >= ? AND occurred_at < ?
              AND (CAST(? AS text) IS NULL OR event_type = ?)
              AND (CAST(? AS text) IS NULL OR windows_user ILIKE ? ESCAPE '!' OR user_sid = ?)
              AND (CAST(? AS text) IS NULL OR outcome = ?)
              AND (CAST(? AS text) IS NULL OR database_name = ?)
              AND (CAST(? AS bigint) IS NULL OR seq < ?)
            ORDER BY seq DESC
            LIMIT ?""";

    /** A bounded wait for the audit database: a search never ties up a request thread for long. */
    static final String TIMEOUT = "SELECT pg_catalog.set_config('statement_timeout', '15s', true)";

    static final int MAX_LIMIT = 200;
    static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z_]{1,62}$");
    private static final Pattern OUTCOME = Pattern.compile("^[a-z][a-z_]{0,39}$");

    /** The filters of one search, as received. */
    public record Query(String from, String to, String type, String user, String outcome, String database, Long before, Integer limit) {
    }

    private final Config config;
    private final DbSessionManager sessions;
    private final AuditService audit;

    public AuditViewer(Config config, DbSessionManager sessions, AuditService audit) {
        this.config = config;
        this.sessions = sessions;
        this.audit = audit;
    }

    public Map<String, Object> events(UserContext user, Object credential, String requestId, Query q) {
        Config.DbProfile profile = config.auditViewer().orElseThrow(ApiException::notFound);
        Instant from = instant(q.from(), "from");
        Instant to = instant(q.to(), "to");
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("INVALID_RANGE", "Choose a period of at most " + MAX_RANGE.toDays() + " days, with its end after its start");
        }
        String type = optional(q.type(), TYPE, "event type");
        String outcome = optional(q.outcome(), OUTCOME, "outcome");
        String who = text(q.user(), 256, "user");
        String database = text(q.database(), 63, "database");
        Long before = q.before();
        if (before != null && before < 1) {
            throw ApiException.badRequest("INVALID_FILTER", "'before' is out of range");
        }
        int limit = q.limit() == null ? 100 : q.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("INVALID_FILTER", "'limit' must be between 1 and " + MAX_LIMIT);
        }

        AuditEvent intent = AuditEvent.of("audit_view").user(user).detail("from", from.toString()).detail("to", to.toString())
                .detail("limit", limit);
        putIfPresent(intent, "type", type);
        putIfPresent(intent, "user", who);
        putIfPresent(intent, "outcome", outcome);
        putIfPresent(intent, "database", database);
        putIfPresent(intent, "before", before);
        putIfPresent(intent, "requestId", requestId);
        audit.require(intent); // fail-closed: a search that can't be recorded isn't run

        List<Map<String, Object>> rows = sessions.oneOff(user, profile, profile.maintenanceDb(), credential, "pgweb-audit-viewer", c -> {
            try {
                return read(c, from, to, type, who, outcome, database, before, limit);
            } catch (SQLException e) {
                ApiException mapped = readError(e.getSQLState());
                if (mapped != null) {
                    throw mapped;
                }
                throw e;
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", rows);
        out.put("limit", limit);
        out.put("more", rows.size() == limit);
        return out;
    }

    private static List<Map<String, Object>> read(Connection c, Instant from, Instant to, String type, String who, String outcome,
                                                  String database, Long before, int limit) throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement(TIMEOUT)) {
                ps.executeQuery().close();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(EVENTS)) {
                int i = 1;
                ps.setTimestamp(i++, Timestamp.from(from));
                ps.setTimestamp(i++, Timestamp.from(to));
                ps.setString(i++, type);
                ps.setString(i++, type);
                ps.setString(i++, who);
                ps.setString(i++, who == null ? null : "%" + likeEscape(who) + "%");
                ps.setString(i++, who);
                ps.setString(i++, outcome);
                ps.setString(i++, outcome);
                ps.setString(i++, database);
                ps.setString(i++, database);
                setLong(ps, i++, before);
                setLong(ps, i++, before);
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(row(rs));
                    }
                }
            }
            return out;
        } finally {
            try {
                c.rollback();
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", rs.getLong(1));
        m.put("occurredAt", rs.getTimestamp(2).toInstant().toString());
        m.put("type", rs.getString(3));
        String[] names = {"windowsUser", "userSid", "clientIp", "profile", "database", "sessionUser", "statementClass", "sqlNormalised",
            "outcome", "sqlstate"};
        for (int k = 0; k < names.length; k++) {
            putIfPresent(m, names[k], rs.getString(4 + k));
        }
        long rows = rs.getLong(14);
        if (!rs.wasNull()) {
            m.put("rowCount", rows);
        }
        long ms = rs.getLong(15);
        if (!rs.wasNull()) {
            m.put("durationMs", ms);
        }
        putIfPresent(m, "executionId", rs.getString(16));
        putIfPresent(m, "dbSessionId", rs.getString(17));
        putIfPresent(m, "requestId", rs.getString(18));
        putIfPresent(m, "details", rs.getString(19));
        return m;
    }

    /** What a failed search tells the user: no privilege, or too slow; null for anything else. */
    static ApiException readError(String sqlState) {
        return switch (String.valueOf(sqlState)) {
            case "42501" -> ApiException.forbidden("AUDIT_READ_DENIED", "Your PostgreSQL role can't read the audit trail. Auditors are members of "
                    + "pgweb_audit_reader on the audit database; ask the administrator.");
            case "57014" -> new ApiException(422, "AUDIT_SEARCH_TIMEOUT", "The search took too long. Choose a shorter period or more filters.");
            default -> null;
        };
    }

    /** LIKE with '!' as the escape character: the user's text matches literally. */
    static String likeEscape(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static Instant instant(String s, String what) {
        if (s == null || s.isBlank()) {
            throw ApiException.badRequest("INVALID_RANGE", "'" + what + "' is required");
        }
        try {
            return Instant.parse(s.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_RANGE", "'" + what + "' is not a time (ISO 8601, UTC)");
        }
    }

    private static String optional(String s, Pattern p, String what) {
        if (s == null || s.isBlank()) {
            return null;
        }
        if (!p.matcher(s.strip()).matches()) {
            throw ApiException.badRequest("INVALID_FILTER", "Not a valid " + what);
        }
        return s.strip();
    }

    private static String text(String s, int max, String what) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        if (t.length() > max || t.indexOf('\0') >= 0) {
            throw ApiException.badRequest("INVALID_FILTER", "Not a valid " + what);
        }
        return t;
    }

    private static void putIfPresent(AuditEvent e, String k, Object v) {
        if (v != null) {
            e.detail(k, v);
        }
    }

    private static void putIfPresent(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
