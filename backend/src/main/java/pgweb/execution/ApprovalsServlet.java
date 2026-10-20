package pgweb.execution;

import jakarta.servlet.http.HttpServletRequest;
import pgweb.security.UserContext;
import pgweb.web.ApiException;
import pgweb.web.ApiServlet;
import pgweb.web.RequestIdFilter;

import java.util.Objects;

/**
 * Two-person approval (admin Phase 14). Requests are made per session ({@code POST /api/db-sessions/{id}/approval}).
 * <pre>
 *   GET    /api/approvals                    your requests, and (approvers) the open requests of others
 *   POST   /api/approvals/{id}/decision      {approve, comment}: approvers only, never your own
 *   DELETE /api/approvals/{id}               withdraw your own open request
 * </pre>
 */
public final class ApprovalsServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;
    private final transient Approvals approvals;

    /** Body of a decision. */
    public record Decision(Boolean approve, String comment) {
    }

    public ApprovalsServlet(Approvals approvals) {
        this.approvals = approvals;
    }

    private static UserContext user(HttpServletRequest req) {
        UserContext u = UserContext.of(req);
        if (u == null) {
            throw ApiException.unauthorized();
        }
        return u;
    }

    private static String[] path(HttpServletRequest req) {
        String info = req.getPathInfo();
        return info == null || info.equals("/") ? new String[0] : info.substring(1).split("/", -1);
    }

    private static String requestId(HttpServletRequest req) {
        return Objects.toString(req.getAttribute(RequestIdFilter.ATTRIBUTE), null);
    }

    @Override
    protected Object get(HttpServletRequest req) {
        if (path(req).length != 0) {
            throw ApiException.notFound();
        }
        return approvals.list(user(req));
    }

    @Override
    protected Object post(HttpServletRequest req) {
        String[] p = path(req);
        if (p.length != 2 || !p[1].equals("decision")) {
            throw ApiException.notFound();
        }
        Decision d = body(req, Decision.class);
        return approvals.decide(user(req), p[0], d.approve(), d.comment(), requestId(req));
    }

    @Override
    protected Object delete(HttpServletRequest req) {
        String[] p = path(req);
        if (p.length != 1) {
            throw ApiException.notFound();
        }
        return approvals.withdraw(user(req), p[0], requestId(req));
    }
}
