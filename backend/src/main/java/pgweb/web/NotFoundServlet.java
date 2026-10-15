package pgweb.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Default servlet ({@code /}): every path no other servlet claims gets the 404 envelope. Mapping a default servlet
 * also makes the filter chain (request ID, security headers, authentication) run for unknown paths.
 */
public final class NotFoundServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected Object get(HttpServletRequest req) {
        throw ApiException.notFound();
    }

    @Override
    protected Object post(HttpServletRequest req) {
        throw ApiException.notFound();
    }

    @Override
    protected Object put(HttpServletRequest req) {
        throw ApiException.notFound();
    }

    @Override
    protected Object delete(HttpServletRequest req) {
        throw ApiException.notFound();
    }
}
