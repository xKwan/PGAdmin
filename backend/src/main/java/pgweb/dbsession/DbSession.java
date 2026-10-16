package pgweb.dbsession;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One user's database session (ADR-0004): a dedicated main connection for SQL and a companion connection, both opened
 * as the user. Pinned to its owner (Windows SID and app session); never pooled, never shared.
 */
public final class DbSession {

    /** Lifecycle (plan §3.6). */
    public enum State { OPEN, CLOSED }

    private final String id;
    private final String ownerSid;
    private final String ownerAccount;
    private final String appSessionHash;
    private final String profile;
    private final String database;
    private final String role;
    private final Connection main;
    private final Connection companion;
    private final int mainPid;
    private final Integer companionPid;
    private final String serverVersion;
    private final Instant createdAt;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Instant lastActivity;
    private volatile Instant lastHeartbeat;
    private volatile Instant transactionStartedAt;
    private volatile State state = State.OPEN;
    private volatile String closeReason;

    DbSession(String id, String ownerSid, String ownerAccount, String appSessionHash, String profile, String database, String role,
              Connection main, Connection companion, int mainPid, Integer companionPid, String serverVersion, Instant now) {
        this.id = id;
        this.ownerSid = ownerSid;
        this.ownerAccount = ownerAccount;
        this.appSessionHash = appSessionHash;
        this.profile = profile;
        this.database = database;
        this.role = role;
        this.main = main;
        this.companion = companion;
        this.mainPid = mainPid;
        this.companionPid = companionPid;
        this.serverVersion = serverVersion;
        this.createdAt = now;
        this.lastActivity = now;
        this.lastHeartbeat = now;
    }

    public String id() {
        return id;
    }

    public String ownerSid() {
        return ownerSid;
    }

    public String ownerAccount() {
        return ownerAccount;
    }

    public String appSessionHash() {
        return appSessionHash;
    }

    public String profile() {
        return profile;
    }

    public String database() {
        return database;
    }

    public String role() {
        return role;
    }

    public int mainPid() {
        return mainPid;
    }

    public Integer companionPid() {
        return companionPid;
    }

    /** True when the companion connection could not be opened: no metadata, no cancel escalation (ADR-0004). */
    public boolean degraded() {
        return companion == null;
    }

    public State state() {
        return state;
    }

    public String closeReason() {
        return closeReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public Instant lastHeartbeat() {
        return lastHeartbeat;
    }

    /** When the current transaction started, or null outside a transaction (from Phase 7/9 execution). */
    public Instant transactionStartedAt() {
        return transactionStartedAt;
    }

    void touch(Instant now) {
        lastActivity = now;
    }

    void heartbeat(Instant now) {
        lastHeartbeat = now;
    }

    /** Called by the execution engine when it observes the transaction state after a statement. */
    void transactionObserved(boolean inTransaction, Instant now) {
        if (inTransaction && transactionStartedAt == null) {
            transactionStartedAt = now;
        } else if (!inTransaction) {
            transactionStartedAt = null;
        }
    }

    /** The main connection's transaction state as pgJDBC tracks it (ADR-0009 J9: never getAutoCommit()). */
    public String transactionState() {
        try {
            TransactionState s = main.unwrap(BaseConnection.class).getTransactionState();
            return s.name();
        } catch (SQLException | RuntimeException e) {
            return "UNKNOWN";
        }
    }

    /** Main connection, only for code holding {@link #lock()}. */
    public Connection main() {
        return main;
    }

    /** Companion connection (null when degraded). */
    public Connection companion() {
        return companion;
    }

    /** Serialises use of the main connection: one statement at a time per session (EXE-08). */
    public ReentrantLock lock() {
        return lock;
    }

    /** True if the main connection still answers (used by the reaper to detect loss). */
    boolean alive() {
        try {
            return main.isValid(3) && !main.isClosed() && main.unwrap(PGConnection.class) != null;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Closes both connections; idempotent. Rolling back is PostgreSQL's job when a connection closes mid-transaction. */
    void close(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        closeReason = reason;
        closeQuietly(main);
        closeQuietly(companion);
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (SQLException ignored) {
            // the server may already be gone
        }
    }

    /** What the API returns about a session. Never contains credentials. */
    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("profile", profile);
        m.put("database", database);
        m.put("role", role);
        m.put("serverVersion", serverVersion);
        m.put("backendPid", mainPid);
        m.put("companion", !degraded());
        m.put("state", state.name());
        m.put("transaction", transactionState());
        m.put("busy", lock.isLocked());
        m.put("createdAt", createdAt.toString());
        m.put("lastActivity", lastActivity.toString());
        return m;
    }
}
