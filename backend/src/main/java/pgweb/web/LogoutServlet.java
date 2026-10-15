package pgweb.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.security.UserContext;

/**
 * {@code POST /api/logout}: ends the app session (and, from Phase 6, the user's DB sessions through the session
 * listener). Windows sign-in itself can't be "logged out": the next request authenticates again automatically.
 */
public final class LogoutServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    private final transient AuditService audit;

    public LogoutServlet(AuditService audit) {
        this.audit = audit;
    }

    @Override
    protected Object post(HttpServletRequest req) {
        UserContext u = UserContext.of(req);
        HttpSession session = req.getSession(false);
        if (u != null) {
            audit.record(AuditEvent.of("app_logout").user(u).request(req).detail("reason", "user"));
        }
        if (session != null) {
            session.invalidate();
        }
        return null;
    }
}
