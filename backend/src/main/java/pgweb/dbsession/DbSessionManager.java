package pgweb.dbsession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.config.Config;
import pgweb.identity.RoleMapper;
import pgweb.security.RateLimiter;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns every DB session in this (single) instance (ADR-0004, D12): opening as the user, ownership checks, caps,
 * reaping, and closing when the app session ends. All decisions are made here, on the server.
 */
public final class DbSessionManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DbSessionManager.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String EV_CONNECT_RESULT = "db_connect_result";
    private static final String EV_DISCONNECT = "db_disconnect";
    private static final String OUTCOME_FAILURE = "failure";
    private static final String REASON = "reason";
    private static final String CONNECT_FAILED = "Could not connect to the database. Check that you have access to it, or ask the administrator.";

    private final Config config;
    private final DbConnector connector;
    private final RoleMapper roles;
    private final AuditService audit;
    private final Clock clock;
    private final RateLimiter failedOpens;
    private final Map<String, DbSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    public DbSessionManager(Config config, DbConnector connector, RoleMapper roles, AuditService audit, Clock clock, boolean reapInBackground) {
        this.config = config;
        this.connector = connector;
        this.roles = roles;
        this.audit = audit;
        this.clock = clock;
        this.failedOpens = new RateLimiter(config.limits().failedOpensPerWindow(), config.limits().failedOpenWindow(), clock);
        if (reapInBackground) {
            reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pgweb-session-reaper");
                t.setDaemon(true);
                return t;
            });
            reaper.scheduleWithFixedDelay(this::reapQuietly, 10, 10, TimeUnit.SECONDS);
        } else {
            reaper = null;
        }
    }

    /** The profile, after checking the user may use it (internal requires DataAdmin, re-checked every time: T03, T28). */
    public Config.DbProfile profileFor(UserContext user, String profileName) {
        Config.DbProfile p = config.profiles().get(profileName);
        if (p == null) {
            throw ApiException.notFound();
        }
        if (p.requiresDataAdmin() && !user.dataAdmin()) {
            throw ApiException.forbidden("FORBIDDEN", "You are not allowed to use this function");
        }
        return p;
    }

    /**
     * Opens a session as the user (ADR-0002/0004): checks caps and the failed-open limit, connects main and companion
     * through the connector, verifies {@code session_user} and refuses superusers (D14c). Everything is audited.
     */
    public DbSession open(UserContext user, String profileName, String database, Object credential, String requestId) {
        Config.DbProfile profile = profileFor(user, profileName);
        validateDatabaseName(database);
        if (failedOpens.blocked(user.sid())) {
            throw ApiException.tooManyRequests("Too many failed connection attempts. Try again in "
                    + Math.max(1, failedOpens.retryAfter(user.sid()).toMinutes()) + " minute(s).");
        }
        synchronized (this) {
            long mine = sessions.values().stream().filter(s -> s.ownerSid().equals(user.sid())).count();
            if (mine >= config.limits().sessionsPerUser()) {
                throw ApiException.conflict("SESSION_LIMIT", "You already have " + mine + " open database sessions; close one first.");
            }
            if (sessions.size() >= config.limits().sessionsGlobal()) {
                throw ApiException.unavailable("SERVER_BUSY", "The server has reached its limit of open database sessions; try again later.");
            }
        }
        String role = roles.roleFor(user);
        audit.record(AuditEvent.of("db_connect_attempt").user(user).target(profile.name(), database, null, null, null).detail("requestId", requestId));

        Connection main = null;
        Connection companion = null;
        try {
            main = connector.open(credential, profile, database, role, "pgweb");
            ConnectionFacts facts = facts(main);
            if (!role.equals(facts.sessionUser)) {
                throw new RefusedException("session_user_mismatch");
            }
            if (facts.superuser) {
                throw new RefusedException("superuser_refused");
            }
            try {
                companion = connector.open(credential, profile, database, role, "pgweb-companion");
            } catch (SQLException e) {
                LOG.warn("companion connection failed for a session of {} ({}): degraded", user.account(), e.getSQLState());
                audit.record(AuditEvent.of("db_companion_failed").user(user).target(profile.name(), database, role, facts.pid, null)
                        .outcome(OUTCOME_FAILURE, sqlstate(e), null, null, null));
            }
            Integer companionPid = companion == null ? null : facts(companion).pid;
            String id = newId();
            DbSession s = new DbSession(id, user.sid(), user.account(), user.sessionHash(), profile.name(), database, role,
                    main, companion, facts.pid, companionPid, facts.version, clock.instant());
            sessions.put(id, s);
            failedOpens.reset(user.sid());
            audit.record(AuditEvent.of(EV_CONNECT_RESULT).user(user).target(profile.name(), database, role, facts.pid, id)
                    .outcome("success", null, null, null, null).detail("companion", companion != null).detail("serverVersion", facts.version));
            return s;
        } catch (RefusedException e) {
            closeQuietly(main);
            closeQuietly(companion);
            failedOpens.recordFailure(user.sid());
            audit.record(AuditEvent.of(EV_CONNECT_RESULT).user(user).target(profile.name(), database, role, null, null)
                    .outcome("refused", null, null, null, null).detail(REASON, e.getMessage()));
            if ("superuser_refused".equals(e.getMessage())) {
                throw ApiException.forbidden("SUPERUSER_REFUSED", "Superuser roles can't be used through this application.");
            }
            throw ApiException.forbidden("ROLE_MISMATCH", "The database session did not run as your mapped role; the connection was closed.");
        } catch (SQLException e) {
            closeQuietly(main);
            closeQuietly(companion);
            failedOpens.recordFailure(user.sid());
            audit.record(AuditEvent.of(EV_CONNECT_RESULT).user(user).target(profile.name(), database, role, null, null)
                    .outcome(OUTCOME_FAILURE, sqlstate(e), null, null, null));
            throw new ApiException(422, "CONNECT_FAILED", CONNECT_FAILED);
        }
    }

    /**
     * Databases the user may connect to on this server (ADR-0003): owned by them or with CONNECT granted to them or a
     * role they belong to (including PUBLIC). Uses a short-lived connection to the maintenance database, as the user.
     */
    public List<Map<String, Object>> databases(UserContext user, String profileName, Object credential) {
        Config.DbProfile profile = profileFor(user, profileName);
        if (failedOpens.blocked(user.sid())) {
            throw ApiException.tooManyRequests("Too many failed connection attempts. Try again later.");
        }
        String role = roles.roleFor(user);
        try (Connection c = connector.open(credential, profile, profile.maintenanceDb(), role, "pgweb-catalog")) {
            ConnectionFacts facts = facts(c);
            if (!role.equals(facts.sessionUser) || facts.superuser) {
                failedOpens.recordFailure(user.sid());
                audit.record(AuditEvent.of(EV_CONNECT_RESULT).user(user).target(profile.name(), profile.maintenanceDb(), role, facts.pid, null)
                        .outcome("refused", null, null, null, null).detail(REASON, facts.superuser ? "superuser_refused" : "session_user_mismatch"));
                throw ApiException.forbidden(facts.superuser ? "SUPERUSER_REFUSED" : "ROLE_MISMATCH",
                        facts.superuser ? "Superuser roles can't be used through this application." : "The connection did not run as your mapped role.");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT d.datname, pg_catalog.pg_get_userbyid(d.datdba), d.datdba = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = session_user) "
                            + "FROM pg_catalog.pg_database d WHERE d.datallowconn AND NOT d.datistemplate "
                            + "AND pg_catalog.has_database_privilege(session_user, d.oid, 'CONNECT') ORDER BY 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Map.of("name", rs.getString(1), "owner", rs.getString(2), "owned", rs.getBoolean(3)));
                }
            }
            failedOpens.reset(user.sid());
            audit.record(AuditEvent.of("metadata_browse").user(user).target(profile.name(), profile.maintenanceDb(), role, facts.pid, null)
                    .detail("object", "databases").detail("count", out.size()));
            return out;
        } catch (SQLException e) {
            failedOpens.recordFailure(user.sid());
            audit.record(AuditEvent.of(EV_CONNECT_RESULT).user(user).target(profile.name(), profile.maintenanceDb(), role, null, null)
                    .outcome(OUTCOME_FAILURE, sqlstate(e), null, null, null));
            throw new ApiException(422, "CONNECT_FAILED", CONNECT_FAILED);
        }
    }

    /** A session owned by this user; anything else is "not found" so IDs of others can't be probed (SEC-07, T06). */
    public DbSession get(UserContext user, String id) {
        DbSession s = id == null ? null : sessions.get(id);
        if (s == null || !s.ownerSid().equals(user.sid()) || !s.appSessionHash().equals(user.sessionHash())) {
            throw ApiException.notFound();
        }
        if (s.state() == DbSession.State.CLOSED) {
            sessions.remove(id);
            throw new ApiException(410, "SESSION_LOST", "The database session has ended (" + s.closeReason() + ").");
        }
        profileFor(user, s.profile()); // internal sessions: DataAdmin is checked again on every use
        s.touch(clock.instant());
        return s;
    }

    public List<DbSession> list(UserContext user) {
        return sessions.values().stream()
                .filter(s -> s.ownerSid().equals(user.sid()) && s.appSessionHash().equals(user.sessionHash()))
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt())).toList();
    }

    /** Heartbeat from the browser; also detects a lost connection. */
    public DbSession heartbeat(UserContext user, String id) {
        DbSession s = get(user, id);
        s.heartbeat(clock.instant());
        if (!s.lock().isLocked() && !s.alive()) {
            close(s, "lost", "session_lost");
            throw new ApiException(410, "SESSION_LOST", "The connection to the database was lost.");
        }
        return s;
    }

    public void close(UserContext user, String id) {
        DbSession s = get(user, id);
        close(s, "user", EV_DISCONNECT);
    }

    /** Ends every DB session of an app session (logout, expiry, invalidation). */
    public int closeAllFor(String appSessionHash, String reason) {
        int n = 0;
        for (DbSession s : List.copyOf(sessions.values())) {
            if (s.appSessionHash().equals(appSessionHash)) {
                close(s, reason, EV_DISCONNECT);
                n++;
            }
        }
        return n;
    }

    /** Applies the idle, idle-in-transaction, abandonment, transaction-age and liveness rules (ADR-0006, plan §3.6). */
    public int reap() {
        Instant now = clock.instant();
        Config.Limits l = config.limits();
        int n = 0;
        for (DbSession s : List.copyOf(sessions.values())) {
            if (s.lock().isLocked()) {
                continue; // a statement is running; its own timeout applies
            }
            String reason = null;
            String event = "session_reaped";
            boolean inTx = !"IDLE".equals(s.transactionState());
            Instant txStart = s.transactionStartedAt();
            if (!s.alive()) {
                reason = "lost";
                event = "session_lost";
            } else if (inTx && older(s.lastActivity(), l.idleInTransaction(), now)) {
                reason = "idle_in_transaction";
            } else if (inTx && older(s.lastHeartbeat(), l.heartbeatAbandon(), now)) {
                reason = "abandoned";
            } else if (inTx && txStart != null && older(txStart, l.maxTransactionAge(), now)) {
                reason = "max_transaction_age";
            } else if (older(s.lastActivity(), l.idleSession(), now)) {
                reason = "idle";
            }
            if (reason != null) {
                close(s, reason, event);
                n++;
            }
        }
        return n;
    }

    private void reapQuietly() {
        try {
            reap();
        } catch (RuntimeException e) {
            LOG.error("session reaper error: {}", e.getMessage());
        }
    }

    private static boolean older(Instant t, Duration limit, Instant now) {
        return t.plus(limit).isBefore(now);
    }

    private void close(DbSession s, String reason, String event) {
        s.close(reason);
        sessions.remove(s.id());
        audit.record(AuditEvent.of(event).user(s.ownerAccount(), s.ownerSid(), null).session(s.appSessionHash())
                .target(s.profile(), s.database(), s.role(), s.mainPid(), s.id()).detail(REASON, reason));
    }

    public int count() {
        return sessions.size();
    }

    /** Graceful shutdown: every session is closed (PostgreSQL rolls back open transactions). */
    @Override
    public void close() {
        if (reaper != null) {
            reaper.shutdownNow();
        }
        for (DbSession s : List.copyOf(sessions.values())) {
            close(s, "shutdown", EV_DISCONNECT);
        }
    }

    /** All sessions (for metrics and tests only). */
    Collection<DbSession> all() {
        return new ArrayList<>(sessions.values());
    }

    // ------------------------------------------------------------------

    static void validateDatabaseName(String database) {
        if (database == null || database.isBlank() || database.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63
                || database.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw ApiException.badRequest("INVALID_DATABASE", "Not a valid database name");
        }
    }

    private record ConnectionFacts(String sessionUser, boolean superuser, int pid, String version) {
    }

    private static ConnectionFacts facts(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT session_user, (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = session_user), pg_backend_pid(), current_setting('server_version')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new ConnectionFacts(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getString(4));
        }
    }

    private static String sqlstate(SQLException e) {
        String s = e.getSQLState();
        return s != null && s.matches("[0-9A-Z]{5}") ? s : null;
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // nothing else to do
            }
        }
    }

    static String newId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static final class RefusedException extends Exception {
        private static final long serialVersionUID = 1L;

        RefusedException(String reason) {
            super(reason, null, false, false);
        }
    }
}
