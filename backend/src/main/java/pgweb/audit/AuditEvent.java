package pgweb.audit;

import jakarta.servlet.http.HttpServletRequest;
import pgweb.security.UserContext;
import pgweb.web.RequestIdFilter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One audit record (docs/audit-policy.md §2–§3), built fluently and written by {@link AuditService}. Field names
 * match the {@code audit.event} columns; {@code seq}, {@code occurred_at} and the hashes are set by the database.
 * Never put credentials, raw SQL text or result data in a field or in {@link #detail}.
 */
public final class AuditEvent {

    final UUID eventId;
    final String eventType;
    String windowsUser;
    String userSid;
    Boolean dataAdmin;
    String clientIp;
    String requestId;
    String appSessionHash;
    String dbSessionId;
    UUID executionId;
    UUID intentId;
    String profile;
    String databaseName;
    String pgSessionUser;
    Integer backendPid;
    String statementClass;
    String sqlNormalised;
    String sqlHmac;
    Integer sqlLength;
    String parseStatus;
    String outcome;
    String cancelOutcome;
    String sqlstate;
    Long rowCount;
    Boolean truncated;
    Long durationMs;
    String txBefore;
    String txAfter;
    Instant startedAt;
    Instant endedAt;
    final Map<String, Object> details = new LinkedHashMap<>();

    private AuditEvent(UUID id, String eventType) {
        if (!eventType.matches("[a-z][a-z_]{1,62}")) {
            throw new IllegalArgumentException("invalid event type: " + eventType);
        }
        this.eventId = id;
        this.eventType = eventType;
    }

    public static AuditEvent of(String eventType) {
        return new AuditEvent(UUID.randomUUID(), eventType);
    }

    /** A spooled record being replayed keeps its original ID. */
    static AuditEvent restore(UUID id, String eventType) {
        return new AuditEvent(id, eventType);
    }

    public UUID id() {
        return eventId;
    }

    public String type() {
        return eventType;
    }

    public AuditEvent user(UserContext u) {
        windowsUser = u.account();
        userSid = u.sid();
        dataAdmin = u.dataAdmin();
        appSessionHash = u.sessionHash();
        clientIp = u.clientIp();
        return this;
    }

    public AuditEvent user(String account, String sid, Boolean isDataAdmin) {
        windowsUser = account;
        userSid = sid;
        dataAdmin = isDataAdmin;
        return this;
    }

    public AuditEvent session(String sessionHash) {
        appSessionHash = sessionHash;
        return this;
    }

    public AuditEvent client(String ip) {
        clientIp = ip;
        return this;
    }

    /** Request ID and client IP of the current request. */
    public AuditEvent request(HttpServletRequest req) {
        Object rid = req.getAttribute(RequestIdFilter.ATTRIBUTE);
        requestId = rid == null ? null : rid.toString();
        if (clientIp == null) {
            clientIp = req.getRemoteAddr();
        }
        return this;
    }

    public AuditEvent target(String profileName, String database, String sessionUser, Integer pid, String dbSession) {
        profile = profileName;
        databaseName = database;
        pgSessionUser = sessionUser;
        backendPid = pid;
        dbSessionId = dbSession;
        return this;
    }

    public AuditEvent execution(UUID execution, UUID intent) {
        executionId = execution;
        intentId = intent;
        return this;
    }

    public AuditEvent statement(String statementKind, String normalised, String hmac, Integer length, String parse) {
        statementClass = statementKind;
        sqlNormalised = normalised;
        sqlHmac = hmac;
        sqlLength = length;
        parseStatus = parse;
        return this;
    }

    public AuditEvent outcome(String result, String state, Long rows, Boolean wasTruncated, Long millis) {
        outcome = result;
        sqlstate = state;
        rowCount = rows;
        truncated = wasTruncated;
        durationMs = millis;
        return this;
    }

    public AuditEvent cancel(String cancelResult) {
        cancelOutcome = cancelResult;
        return this;
    }

    public AuditEvent transaction(String before, String after) {
        txBefore = before;
        txAfter = after;
        return this;
    }

    public AuditEvent times(Instant start, Instant end) {
        startedAt = start;
        endedAt = end;
        return this;
    }

    /** Adds a detail (stored in the {@code details} jsonb column); null values are left out. */
    public AuditEvent detail(String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
        return this;
    }

    public Map<String, Object> details() {
        return Map.copyOf(details);
    }

    public String windowsUser() {
        return windowsUser;
    }

    public String outcome() {
        return outcome;
    }
}
