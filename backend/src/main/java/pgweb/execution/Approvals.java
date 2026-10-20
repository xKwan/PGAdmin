package pgweb.execution;

import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.config.Config;
import pgweb.dbsession.DbSession;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-person approval of high-risk statements (admin Phase 14, decision A19). When a profile lists reasons in
 * {@code profile.<name>.approvalReasons} (for example {@code drop,truncate}), a statement or script with one of them
 * runs only after a <b>second person</b>, a member of the approver group ({@code approval.approverSid}) and not the
 * requester, approved that exact text.
 * <ul>
 *   <li>An approval is bound to the requester's account, app session and database session, and the text's HMAC, and is
 *       used once: running it again needs a new approval. Requests expire after {@link #PENDING_TTL}; an approval must
 *       be used within {@link #APPROVED_TTL}.</li>
 *   <li>Requests live in memory only (the text the approver must read is never written to disk) and end with a
 *       restart. At most {@link #MAX_PENDING_PER_USER} open requests per account and {@link #MAX_REQUESTS} in all.</li>
 *   <li>Requesting, approving, rejecting and withdrawing are audited first (fail-closed), with the HMAC, never the
 *       text. The run records the approval's ID.</li>
 *   <li>This is a control on top of PostgreSQL's privileges, never instead of them: the statement still runs as the
 *       requester's role, through the gate, the confirmation and the kill switch.</li>
 * </ul>
 */
public final class Approvals {

    static final Duration PENDING_TTL = Duration.ofMinutes(30);
    static final Duration APPROVED_TTL = Duration.ofMinutes(15);
    static final int MAX_PENDING_PER_USER = 5;
    static final int MAX_REQUESTS = 500;
    static final int MAX_COMMENT = 500;

    /** Where a request is. */
    public enum State { PENDING, APPROVED, REJECTED, USED, EXPIRED, WITHDRAWN }

    static final class Request {
        final String id = UUID.randomUUID().toString();
        final String requesterAccount;
        final String requesterSid;
        final String appSessionHash;
        final String dbSessionId;
        final String profile;
        final String database;
        final String role;
        final String hmac;
        final String text;
        final boolean script;
        final List<String> reasons;
        final Instant createdAt;
        volatile State state = State.PENDING;
        volatile String deciderAccount;
        volatile Instant decidedAt;
        volatile String comment;

        Request(UserContext user, DbSession s, String hmac, String text, boolean script, List<String> reasons, Instant now) {
            this.requesterAccount = user.account();
            this.requesterSid = user.sid();
            this.appSessionHash = user.sessionHash();
            this.dbSessionId = s.id();
            this.profile = s.profile();
            this.database = s.database();
            this.role = s.role();
            this.hmac = hmac;
            this.text = text;
            this.script = script;
            this.reasons = List.copyOf(reasons);
            this.createdAt = now;
        }
    }

    private final Config config;
    private final AuditService audit;
    private final Clock clock;
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    public Approvals(Config config, AuditService audit, Clock clock) {
        this.config = config;
        this.audit = audit;
        this.clock = clock;
    }

    /** The reasons of the profile that need a second person (empty: none). */
    public List<String> required(String profile, List<String> reasons) {
        List<String> listed = config.profiles().get(profile).approvalReasons();
        return reasons.stream().filter(listed::contains).distinct().toList();
    }

    public boolean isApprover(UserContext user) {
        String sid = config.auth().approverSid();
        return sid != null && user.groupSids().contains(sid.toUpperCase(Locale.ROOT));
    }

    /** Creates a request for text that needs approval (the engine has checked it with the gate). */
    synchronized Map<String, Object> request(UserContext user, DbSession s, String hmac, String text, boolean script, List<String> reasons, String requestId) {
        expire();
        long open = requests.values().stream().filter(r -> r.requesterSid.equals(user.sid()) && r.state == State.PENDING).count();
        if (open >= MAX_PENDING_PER_USER) {
            throw ApiException.conflict("TOO_MANY_REQUESTS", "You have " + MAX_PENDING_PER_USER + " requests waiting. Withdraw one, or wait for a decision.");
        }
        if (requests.size() >= MAX_REQUESTS) {
            throw ApiException.conflict("TOO_MANY_REQUESTS", "Too many approval requests are open. Try again later.");
        }
        Request r = new Request(user, s, hmac, text, script, reasons, clock.instant());
        audit.require(AuditEvent.of("approval_requested").user(user).target(s.profile(), s.database(), s.role(), s.mainPid(), s.id())
                .statement(script ? "script" : null, null, hmac, text.length(), null).detail("approvalId", r.id).detail("reasons", reasons)
                .detail("requestId", requestId));
        requests.put(r.id, r);
        return view(r, true, user);
    }

    /** The approval for exactly this run, if one was given and is still valid; it is then used up. */
    synchronized String consume(UserContext user, DbSession s, String hmac, boolean script) {
        expire();
        for (Request r : requests.values()) {
            if (r.state == State.APPROVED && r.requesterSid.equals(user.sid()) && r.appSessionHash.equals(user.sessionHash())
                    && r.dbSessionId.equals(s.id()) && r.hmac.equals(hmac) && r.script == script) {
                r.state = State.USED;
                return r.id;
            }
        }
        return null;
    }

    /** Your own requests, and (for approvers) the open requests of others with their text. */
    public synchronized Map<String, Object> list(UserContext user) {
        expire();
        List<Map<String, Object>> mine = new ArrayList<>();
        List<Map<String, Object>> toDecide = new ArrayList<>();
        boolean approver = isApprover(user);
        requests.values().stream().sorted(Comparator.comparing((Request r) -> r.createdAt).reversed()).forEach(r -> {
            if (r.requesterSid.equals(user.sid())) {
                mine.add(view(r, true, user));
            } else if (approver && r.state == State.PENDING) {
                toDecide.add(view(r, true, user));
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mine", mine);
        out.put("approver", approver);
        out.put("toDecide", approver ? toDecide : List.of());
        return out;
    }

    /** Approves or rejects another person's open request; only approvers, never your own. */
    public synchronized Map<String, Object> decide(UserContext user, String id, Boolean approve, String comment, String requestId) {
        expire();
        Request r = id == null ? null : requests.get(id);
        if (r == null || (!r.requesterSid.equals(user.sid()) && !isApprover(user))) {
            throw ApiException.notFound();
        }
        if (!isApprover(user)) {
            throw ApiException.forbidden("NOT_APPROVER", "Only members of the approver group can decide on requests.");
        }
        if (r.requesterSid.equals(user.sid())) {
            throw ApiException.forbidden("OWN_REQUEST", "A second person must decide: you can't approve or reject your own request.");
        }
        if (approve == null) {
            throw ApiException.badRequest("INVALID_DECISION", "approve must be true or false");
        }
        if (comment != null && (comment.length() > MAX_COMMENT || comment.indexOf('\0') >= 0)) {
            throw ApiException.badRequest("INVALID_COMMENT", "The comment is too long");
        }
        if (r.state != State.PENDING) {
            throw ApiException.conflict("NOT_PENDING", "This request was already decided, withdrawn or has expired.");
        }
        audit.require(AuditEvent.of(approve ? "approval_granted" : "approval_rejected").user(user)
                .target(r.profile, r.database, r.role, null, r.dbSessionId).statement(r.script ? "script" : null, null, r.hmac, r.text.length(), null)
                .detail("approvalId", r.id).detail("requester", r.requesterAccount).detail("requesterSid", r.requesterSid)
                .detail("commentLength", comment == null ? 0 : comment.length()).detail("requestId", requestId));
        r.state = approve ? State.APPROVED : State.REJECTED;
        r.deciderAccount = user.account();
        r.decidedAt = clock.instant();
        r.comment = comment == null || comment.isBlank() ? null : comment.strip();
        return view(r, true, user);
    }

    /** Withdraws your own open request. */
    public synchronized Map<String, Object> withdraw(UserContext user, String id, String requestId) {
        expire();
        Request r = id == null ? null : requests.get(id);
        if (r == null || !r.requesterSid.equals(user.sid())) {
            throw ApiException.notFound();
        }
        if (r.state != State.PENDING && r.state != State.APPROVED) {
            throw ApiException.conflict("NOT_PENDING", "This request is no longer open.");
        }
        audit.require(AuditEvent.of("approval_withdrawn").user(user).target(r.profile, r.database, r.role, null, r.dbSessionId)
                .detail("approvalId", r.id).detail("requestId", requestId));
        r.state = State.WITHDRAWN;
        return view(r, true, user);
    }

    /** Ends what is past its time, and forgets finished requests after a day. */
    private void expire() {
        Instant now = clock.instant();
        requests.values().removeIf(r -> r.state != State.PENDING && r.state != State.APPROVED && r.createdAt.plus(Duration.ofDays(1)).isBefore(now));
        for (Request r : requests.values()) {
            if (r.state == State.PENDING && r.createdAt.plus(PENDING_TTL).isBefore(now)
                    || r.state == State.APPROVED && r.decidedAt.plus(APPROVED_TTL).isBefore(now)) {
                r.state = State.EXPIRED;
            }
        }
    }

    private Map<String, Object> view(Request r, boolean withText, UserContext viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("state", r.state.name().toLowerCase(Locale.ROOT));
        m.put("requester", r.requesterAccount);
        m.put("mine", r.requesterSid.equals(viewer.sid()));
        m.put("profile", r.profile);
        m.put("database", r.database);
        m.put("role", r.role);
        m.put("script", r.script);
        m.put("reasons", r.reasons);
        m.put("dbSessionId", r.dbSessionId);
        m.put("createdAt", r.createdAt.toString());
        m.put("expiresAt", (r.state == State.APPROVED ? r.decidedAt.plus(APPROVED_TTL) : r.createdAt.plus(PENDING_TTL)).toString());
        if (withText) {
            m.put("text", r.text);
        }
        if (r.deciderAccount != null) {
            m.put("decidedBy", r.deciderAccount);
            m.put("decidedAt", r.decidedAt.toString());
        }
        if (r.comment != null) {
            m.put("comment", r.comment);
        }
        return m;
    }
}
