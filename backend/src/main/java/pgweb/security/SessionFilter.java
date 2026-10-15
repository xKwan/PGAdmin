package pgweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.config.Config;
import pgweb.web.ErrorEnvelope;
import waffle.servlet.WindowsPrincipal;
import waffle.windows.auth.WindowsAccount;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Runs after Waffle and turns its principal into the app session (plan §5, Phase 4):
 * <ul>
 *   <li><b>Fail-closed:</b> no {@link WindowsPrincipal}, no access, even if Waffle let the request through.</li>
 *   <li><b>Session fixation:</b> on the first authenticated request of a session the session ID is changed, and the
 *       session is bound to the user's SID; a different SID in a bound session invalidates it.</li>
 *   <li><b>Lifetimes:</b> idle timeout is the container's; the absolute lifetime (8 h) is enforced here.</li>
 *   <li><b>Domains:</b> only configured domains may sign in.</li>
 *   <li>Builds the request's {@link UserContext}; DataAdmin is evaluated on every request (T28).</li>
 * </ul>
 */
public final class SessionFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;

    static final String BOUND_SID = "pgweb.boundSid";
    static final String LOGIN_AT = "pgweb.loginAt";
    static final String MECHANISM = "pgweb.mechanism";
    public static final String CSRF_TOKEN = "pgweb.csrfToken";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final transient Config.Auth auth;
    private final transient AuditService audit;
    private final transient Clock clock;

    public SessionFilter(Config.Auth auth, AuditService audit, Clock clock) {
        this.auth = auth;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (PublicPaths.isPublic(req)) {
            chain.doFilter(req, res);
            return;
        }
        if (!(req.getUserPrincipal() instanceof WindowsPrincipal principal)) {
            ErrorEnvelope.write(res, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        String sid = principal.getSidString();
        String account = principal.getName();
        HttpSession session = req.getSession(true);
        Object bound = session.getAttribute(BOUND_SID);
        boolean fresh = bound == null;
        if (fresh) {
            req.changeSessionId();
            session.setAttribute(BOUND_SID, sid);
            session.setAttribute(LOGIN_AT, clock.instant());
            session.setAttribute(MECHANISM, mechanism(req));
            session.setAttribute(CSRF_TOKEN, newToken());
        } else if (!bound.equals(sid)) {
            session.invalidate();
            ErrorEnvelope.write(res, 401, "SESSION_INVALID", "Session does not belong to the authenticated user");
            return;
        }
        Instant loginAt = (Instant) session.getAttribute(LOGIN_AT);
        if (clock.instant().isAfter(loginAt.plus(auth.sessionAbsolute()))) {
            String hash = audit.sessionHash(session.getId());
            session.invalidate();
            audit.record(AuditEvent.of("app_logout").user(account, sid, null).session(hash).client(req.getRemoteAddr())
                    .detail("reason", "absolute_lifetime"));
            ErrorEnvelope.write(res, 401, "SESSION_EXPIRED", "Your session has expired; reload the page to sign in again");
            return;
        }

        Set<String> groups = new HashSet<>();
        for (WindowsAccount g : principal.getGroups().values()) {
            groups.add(g.getSidString().toUpperCase(Locale.ROOT));
        }
        boolean dataAdmin = groups.contains(auth.dataAdminSid().toUpperCase(Locale.ROOT));
        UserContext user = new UserContext(account, sid, Set.copyOf(groups), dataAdmin, (String) session.getAttribute(MECHANISM),
                audit.sessionHash(session.getId()), loginAt, req.getRemoteAddr());

        if (!auth.allowedDomains().isEmpty() && !auth.allowedDomains().contains(user.domain())) {
            if (fresh) {
                audit.record(AuditEvent.of("authz_denied").user(user).request(req).detail("reason", "domain_not_allowed"));
            }
            session.invalidate();
            ErrorEnvelope.write(res, 403, "DOMAIN_NOT_ALLOWED", "Your account's domain is not allowed to use this application");
            return;
        }
        if (fresh) {
            audit.record(AuditEvent.of("app_login").user(user).request(req).detail("mechanism", user.mechanism()));
        }
        req.setAttribute(UserContext.ATTRIBUTE, user);
        chain.doFilter(req, res);
    }

    private static String mechanism(HttpServletRequest req) {
        Object m = req.getAttribute(AuthSchemeFilter.MECHANISM_ATTRIBUTE);
        return m == null ? "unknown" : m.toString();
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
