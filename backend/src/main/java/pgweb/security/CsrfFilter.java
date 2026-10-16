package pgweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.web.ErrorEnvelope;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * CSRF protection for every state-changing request (threat T05). The browser sends Windows credentials
 * automatically, so a forged cross-site request would otherwise be authenticated.
 * <ol>
 *   <li><b>Origin:</b> {@code Origin} must be this application's own origin; without {@code Origin},
 *       {@code Sec-Fetch-Site} must be {@code same-origin}. Requests with neither are refused.</li>
 *   <li><b>Token:</b> {@code X-CSRF-Token} must equal the session's synchronizer token (from {@code GET /api/me}),
 *       compared in constant time.</li>
 * </ol>
 * The session cookie is also {@code SameSite=Strict}. GET, HEAD and OPTIONS must have no side effects.
 */
public final class CsrfFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;
    public static final String HEADER = "X-CSRF-Token";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final transient Supplier<Set<String>> allowedOrigins;
    private final transient AuditService audit;

    /**
     * @param allowedOrigins exact origins, e.g. {@code https://pgweb.corp.example} (lower case, no trailing slash);
     *                       a supplier because the bound port is known only after startup
     */
    public CsrfFilter(Supplier<Set<String>> allowedOrigins, AuditService audit) {
        this.allowedOrigins = allowedOrigins;
        this.audit = audit;
    }

    /** The origins a deployment accepts: its public host, with the configured port unless it is the default. */
    public static Set<String> originsFor(String publicHost, int port, boolean tls) {
        String scheme = tls ? "https" : "http";
        String host = publicHost.toLowerCase(Locale.ROOT);
        boolean defaultPort = port == 0 || (tls && port == 443) || (!tls && port == 80);
        return defaultPort ? Set.of(scheme + "://" + host) : Set.of(scheme + "://" + host + ":" + port);
    }

    static String normaliseOrigin(String origin) {
        try {
            URI u = URI.create(origin.strip());
            if (u.getScheme() == null || u.getHost() == null || u.getRawPath() != null && !u.getRawPath().isEmpty()) {
                return null;
            }
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (SAFE.contains(req.getMethod()) || PublicPaths.isPublic(req)) {
            chain.doFilter(req, res);
            return;
        }
        String reason = check(req);
        if (reason != null) {
            UserContext user = UserContext.of(req);
            AuditEvent e = AuditEvent.of("authz_denied").request(req).detail("reason", "csrf_" + reason)
                    .detail("method", req.getMethod()).detail("path", PublicPaths.path(req));
            if (user != null) {
                e.user(user);
            }
            if ("token".equals(reason) && Boolean.TRUE.equals(req.getAttribute(SessionFilter.SIGNED_IN_NOW))) {
                // Same origin, but the app session was created by this very request: the old one expired and the
                // browser signed in again by itself (Negotiate), so the page still holds the old token. Nothing runs;
                // the page reloads once on SESSION_RENEWED and picks up the new session (finding P11-2). Not 401: a 401
                // on the request that completed an NTLM handshake makes Chromium send the request again.
                audit.record(e.detail("sessionRenewed", true));
                ErrorEnvelope.write(res, 403, "SESSION_RENEWED", "Your session had expired and was renewed; the page will reload");
                return;
            }
            audit.record(e);
            ErrorEnvelope.write(res, 403, "CSRF_FAILED", "Request blocked: missing or invalid CSRF protection");
            return;
        }
        chain.doFilter(req, res);
    }

    /** Null if the request passes; otherwise a short reason for the audit record. */
    String check(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin != null) {
            String n = normaliseOrigin(origin);
            if (n == null || !allowedOrigins.get().contains(n)) {
                return "origin";
            }
        } else if (!"same-origin".equalsIgnoreCase(req.getHeader("Sec-Fetch-Site"))) {
            return "no_origin";
        }
        HttpSession session = req.getSession(false);
        Object expected = session == null ? null : session.getAttribute(SessionFilter.CSRF_TOKEN);
        String supplied = req.getHeader(HEADER);
        if (!(expected instanceof String exp) || supplied == null
                || !MessageDigest.isEqual(exp.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
            return "token";
        }
        return null;
    }
}
