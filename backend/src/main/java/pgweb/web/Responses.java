package pgweb.web;

import jakarta.servlet.http.HttpServletResponse;

/** Common response headers for API responses. */
public final class Responses {

    private Responses() {
    }

    /** JSON content type and no caching: API responses may contain query results (plan §5). */
    public static void json(HttpServletResponse resp) {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("X-Content-Type-Options", "nosniff");
    }
}
