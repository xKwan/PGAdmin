package spike.s1servlet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import waffle.servlet.WindowsPrincipal;

import java.io.IOException;

/**
 * Runs after Waffle. Fails closed: a request without a Windows principal is refused even if Waffle let it through
 * (Waffle skips authentication entirely when it believes it is not on Windows). On the first authenticated request
 * of a session it rotates the session ID (Waffle stores the principal in whatever session exists, so a planted
 * session would otherwise be adopted) and binds the session to the user's SID.
 */
final class AuthenticatedSessionFilter extends HttpFilter {

    static final String BOUND_SID = "pgweb.boundSid";

    private final String anonPrefix;

    AuthenticatedSessionFilter(String anonPrefix) {
        this.anonPrefix = anonPrefix;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req.getRequestURI().startsWith(anonPrefix)) {
            chain.doFilter(req, res);
            return;
        }
        if (!(req.getUserPrincipal() instanceof WindowsPrincipal principal)) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        HttpSession session = req.getSession(false);
        if (session != null) {
            Object bound = session.getAttribute(BOUND_SID);
            if (bound == null) {
                req.changeSessionId();
                session.setAttribute(BOUND_SID, principal.getSidString());
            } else if (!bound.equals(principal.getSidString())) {
                session.invalidate();
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
