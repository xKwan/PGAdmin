package pgweb.authz;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.security.PublicPaths;
import pgweb.security.UserContext;
import pgweb.web.ErrorEnvelope;

import java.io.IOException;
import java.util.Map;

/**
 * Enforces each route's declared {@link Access} level (deny by default), after authentication and CSRF. The route is
 * identified by the servlet the container mapped, never by parsing the URL again.
 */
public final class AccessFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;

    private final Map<String, Access> byServlet;
    private final transient AuditService audit;

    public AccessFilter(Map<String, Access> byServlet, AuditService audit) {
        this.byServlet = Map.copyOf(byServlet);
        this.audit = audit;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletMapping mapping = req.getHttpServletMapping();
        Access access = mapping == null ? null : byServlet.get(mapping.getServletName());
        UserContext user = UserContext.of(req);
        if (access == Access.PUBLIC && PublicPaths.isPublic(req)) {
            chain.doFilter(req, res);
            return;
        }
        if (user == null) {
            // Defence in depth: SessionFilter already refused unauthenticated requests.
            ErrorEnvelope.write(res, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        if (access == null || access == Access.PUBLIC) {
            deny(req, res, user, "undeclared_route");
            return;
        }
        if (access == Access.DATA_ADMIN && !user.dataAdmin()) {
            deny(req, res, user, "not_data_admin");
            return;
        }
        chain.doFilter(req, res);
    }

    private void deny(HttpServletRequest req, HttpServletResponse res, UserContext user, String reason) throws IOException {
        audit.record(AuditEvent.of("authz_denied").user(user).request(req).detail("reason", reason)
                .detail("method", req.getMethod()).detail("path", PublicPaths.path(req)));
        ErrorEnvelope.write(res, 403, "FORBIDDEN", "You are not allowed to use this function");
    }
}
