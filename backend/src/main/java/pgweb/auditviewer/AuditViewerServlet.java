package pgweb.auditviewer;

import jakarta.servlet.http.HttpServletRequest;
import pgweb.security.FreshNegotiateFilter;
import pgweb.security.UserContext;
import pgweb.web.ApiException;
import pgweb.web.ApiServlet;
import pgweb.web.RequestIdFilter;

/**
 * {@code POST /api/audit/search} with the filters as JSON (admin Phase 9). POST, so the filters (which may name a
 * person) stay out of URLs and access logs, and the request carries its own Windows handshake (fresh Negotiate) for
 * the connection to the audit database as the user.
 */
public final class AuditViewerServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;
    private final transient AuditViewer viewer;

    public AuditViewerServlet(AuditViewer viewer) {
        this.viewer = viewer;
    }

    @Override
    protected Object post(HttpServletRequest req) {
        UserContext user = UserContext.of(req);
        if (user == null) {
            throw ApiException.unauthorized();
        }
        AuditViewer.Query q = body(req, AuditViewer.Query.class);
        return viewer.events(user, req.getAttribute(FreshNegotiateFilter.IDENTITY_ATTRIBUTE),
                java.util.Objects.toString(req.getAttribute(RequestIdFilter.ATTRIBUTE), null), q);
    }
}
