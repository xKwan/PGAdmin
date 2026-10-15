package pgweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Security headers on every response (plan §5, SEC-09, threats T13 and T30). API responses get a CSP that allows
 * nothing; the SPA's HTML gets {@link #spaCsp(String)} with a per-request nonce (the static-content servlet sets it).
 */
public final class SecurityHeadersFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;
    public static final String NONCE_ATTRIBUTE = "pgweb.cspNonce";
    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** CSP for the single-page app: same-origin only, no inline script, styles only with this request's nonce. */
    public static String spaCsp(String nonce) {
        return "default-src 'none'; script-src 'self'; style-src 'self' 'nonce-" + nonce + "'; img-src 'self' data:; "
                + "font-src 'self'; connect-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
                + "form-action 'self'; object-src 'none'";
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        req.setAttribute(NONCE_ATTRIBUTE, Base64.getEncoder().encodeToString(b));
        res.setHeader("Content-Security-Policy", API_CSP);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=()");
        if (req.isSecure()) {
            res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(req, res);
    }
}
