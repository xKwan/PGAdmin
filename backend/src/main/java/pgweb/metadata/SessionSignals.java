package pgweb.metadata;

import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.config.Config;
import pgweb.dbsession.DbSession;
import pgweb.dbsession.DbSessionManager;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cancelling another session's running query, or ending that session (admin Phase 4; decision of 2026-10-20). It is a
 * change to someone else's work, so:
 * <ul>
 *   <li>It is a change like running SQL: refused while execution is switched off ({@code execution.enabled}, EXE-01).</li>
 *   <li>It runs <b>as the user's own role</b> on the session's companion, with PostgreSQL's own rule checked first and
 *       enforced again by PostgreSQL: the target must be a client session the role can see, and the role must have
 *       the privileges of the target's role or of {@code pg_signal_backend}; a superuser's session only by a
 *       superuser. pgweb's own session and companion are refused (use Cancel, or close the session).</li>
 *   <li>Without {@code confirmed} the answer is 409 {@code CONFIRMATION_REQUIRED}; nothing is sent.</li>
 *   <li>The {@code session_signal} audit record is committed <b>before</b> the signal (fail-closed, as for SQL), and
 *       {@code session_signal_outcome} after it.</li>
 * </ul>
 * Constant queries only; the pid is a bound parameter.
 */
public final class SessionSignals {

    /** The target: who, where, what kind, whether the role may see it and may signal it. Parameter: pid. */
    static final String TARGET = """
            SELECT a.usename, a.datname, a.backend_type,
                   a.query IS NOT NULL AND a.query <> '<insufficient privilege>',
                   CASE WHEN COALESCE(r.rolsuper, false)
                        THEN COALESCE((SELECT m.rolsuper FROM pg_catalog.pg_roles m WHERE m.rolname = current_user), false)
                        ELSE (a.usesysid IS NOT NULL AND pg_catalog.pg_has_role(a.usesysid, 'USAGE'))
                             OR pg_catalog.pg_has_role('pg_signal_backend', 'USAGE') END
            FROM pg_catalog.pg_stat_activity a LEFT JOIN pg_catalog.pg_roles r ON r.oid = a.usesysid
            WHERE a.pid = ?""";

    /** Parameter: pid. */
    static final String CANCEL = "SELECT pg_catalog.pg_cancel_backend(?)";

    /** Parameter: pid. */
    static final String TERMINATE = "SELECT pg_catalog.pg_terminate_backend(?)";

    private static final String ACTION = "action";
    private static final String TARGET_PID = "targetPid";
    private static final String NOT_PERMITTED = "NOT_PERMITTED";

    private final Config config;
    private final MetadataService metadata;
    private final DbSessionManager sessions;
    private final AuditService audit;

    public SessionSignals(Config config, MetadataService metadata, DbSessionManager sessions, AuditService audit) {
        this.config = config;
        this.metadata = metadata;
        this.sessions = sessions;
        this.audit = audit;
    }

    private record Target(String user, String database, String backendType, boolean visible, boolean permitted) {
    }

    /** Cancels the query of ({@code cancel}) or ends ({@code terminate}) the session with this backend pid. */
    public Map<String, Object> signal(UserContext user, String sessionId, Integer pid, String action, boolean confirmed) {
        if (!config.executionEnabled()) {
            throw ApiException.forbidden("EXECUTION_DISABLED", "Running SQL is switched off by the administrator");
        }
        String sql = switch (action == null ? "" : action) {
            case "cancel" -> CANCEL;
            case "terminate" -> TERMINATE;
            default -> throw ApiException.badRequest("INVALID_ACTION", "action must be cancel or terminate");
        };
        if (pid == null || pid <= 0) {
            throw ApiException.badRequest("INVALID_PID", "pid must be a positive number");
        }
        DbSession s = sessions.use(user, sessionId);
        if (pid == s.mainPid() || Objects.equals(pid, s.companionPid())) {
            throw ApiException.badRequest("OWN_SESSION", "This is your own pgweb session: use Cancel in the editor, or close the session.");
        }
        try {
            return metadata.withCompanion(s, c -> {
                Target t;
                try (PreparedStatement ps = MetadataService.prepare(c, TARGET)) {
                    ps.setInt(1, pid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ApiException(404, "NOT_FOUND", "That session has already ended.");
                        }
                        t = new Target(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5));
                    }
                }
                if (!t.visible()) {
                    throw ApiException.forbidden(NOT_PERMITTED, "Your role can't see that session, so it can't cancel or end it.");
                }
                if (!"client backend".equals(t.backendType())) {
                    throw ApiException.badRequest("NOT_CLIENT_SESSION", "Only client sessions can be cancelled or ended here.");
                }
                AuditEvent intent = AuditEvent.of("session_signal").user(user).target(s.profile(), s.database(), s.role(), s.companionPid(), s.id())
                        .detail(ACTION, action).detail(TARGET_PID, pid).detail("targetUser", t.user()).detail("targetDatabase", t.database());
                if (!t.permitted()) {
                    audit.record(intent.outcome("refused", "42501", null, null, null));
                    throw ApiException.forbidden(NOT_PERMITTED, "Your role may not " + ("cancel".equals(action) ? "cancel queries of" : "end")
                            + " that session: it needs the privileges of " + t.user() + " or of pg_signal_backend.");
                }
                if (!confirmed) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("reasons", List.of("cancel".equals(action) ? "cancel_query" : "terminate_session"));
                    details.put("targetUser", t.user());
                    details.put("targetDatabase", t.database());
                    throw new ApiException(409, "CONFIRMATION_REQUIRED", "This affects another session. Confirm to go ahead.", details);
                }
                audit.require(intent);
                boolean sent;
                try (PreparedStatement ps = MetadataService.prepare(c, sql)) {
                    ps.setInt(1, pid);
                    try (ResultSet rs = ps.executeQuery()) {
                        sent = rs.next() && rs.getBoolean(1);
                    }
                } catch (SQLException e) {
                    audit.record(AuditEvent.of("session_signal_outcome").user(user).target(s.profile(), s.database(), s.role(), s.companionPid(), s.id())
                            .detail(ACTION, action).detail(TARGET_PID, pid).outcome("error", e.getSQLState(), null, null, null));
                    throw e;
                }
                audit.record(AuditEvent.of("session_signal_outcome").user(user).target(s.profile(), s.database(), s.role(), s.companionPid(), s.id())
                        .detail(ACTION, action).detail(TARGET_PID, pid).outcome(sent ? "success" : "not_sent", null, null, null, null));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("pid", pid);
                out.put(ACTION, action);
                out.put("sent", sent);
                return out;
            });
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                throw ApiException.forbidden(NOT_PERMITTED, "PostgreSQL refused: your role may not signal that session.");
            }
            throw new ApiException(422, "SIGNAL_FAILED", "The signal could not be sent (" + (e.getSQLState() == null ? "error" : e.getSQLState()) + ").");
        }
    }
}
