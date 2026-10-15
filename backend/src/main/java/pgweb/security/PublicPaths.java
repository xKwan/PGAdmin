package pgweb.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * The only paths that answer without Windows authentication. Compared exactly against the container-normalised path,
 * never as a pattern on the full URL (S1 finding W9: Waffle's excludePatterns match the whole URL).
 */
public final class PublicPaths {

    public static final Set<String> PATHS = Set.of("/api/health", "/api/ready");

    private PublicPaths() {
    }

    public static boolean isPublic(HttpServletRequest req) {
        return PATHS.contains(path(req));
    }

    /** Request path without the context path (the context is the root, but don't rely on it). */
    public static String path(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
