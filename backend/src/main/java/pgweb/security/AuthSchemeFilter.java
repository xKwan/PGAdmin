package pgweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import pgweb.web.ErrorEnvelope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Runs before Waffle.
 * <ul>
 *   <li>Hides {@code Authorization} headers with schemes Waffle can't handle ({@code Basic}, {@code Bearer}, ...):
 *       Waffle 3.7.0 answers them with HTTP 500 (S1 servlet finding W8).</li>
 *   <li>With {@code auth.kerberosOnly=true} (production), refuses NTLM, whether sent as {@code NTLM} or wrapped in
 *       SPNEGO: only a Kerberos logon can be delegated to PostgreSQL (ADR-0002), and NTLM is weaker (threat T02).</li>
 *   <li>Records the mechanism of the token for the session filter.</li>
 * </ul>
 */
public final class AuthSchemeFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;
    public static final String MECHANISM_ATTRIBUTE = "pgweb.authMechanism";
    private static final String AUTHORIZATION = "Authorization";
    private static final byte[] NTLMSSP = "NTLMSSP\0".getBytes(StandardCharsets.ISO_8859_1);

    private final boolean kerberosOnly;

    public AuthSchemeFilter(boolean kerberosOnly) {
        this.kerberosOnly = kerberosOnly;
    }

    /** {@code negotiate}, {@code ntlm}, another scheme in lower case, or null when there is no header. */
    static String scheme(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        return authorization.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    }

    /** True if the token is NTLM: raw NTLMSSP, or SPNEGO carrying an NTLMSSP message. */
    static boolean isNtlmToken(String authorization) {
        String[] parts = authorization.strip().split("\\s+", 2);
        if (parts.length < 2) {
            return false;
        }
        byte[] token;
        try {
            token = Base64.getDecoder().decode(parts[1].strip());
        } catch (IllegalArgumentException e) {
            return false; // not decodable: Waffle rejects it with 401
        }
        return indexOf(token, NTLMSSP) >= 0;
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        String authorization = req.getHeader(AUTHORIZATION);
        String scheme = scheme(authorization);
        if (scheme == null) {
            chain.doFilter(req, res);
            return;
        }
        if (!scheme.equals("negotiate") && !scheme.equals("ntlm")) {
            chain.doFilter(new WithoutAuthorization(req), res);
            return;
        }
        boolean ntlm = scheme.equals("ntlm") || isNtlmToken(authorization);
        if (ntlm && kerberosOnly) {
            res.setHeader("WWW-Authenticate", "Negotiate");
            res.setHeader("Connection", "close");
            ErrorEnvelope.write(res, 401, "KERBEROS_REQUIRED",
                    "Kerberos sign-in is required. Open the application using its full host name from a domain-joined computer.");
            return;
        }
        req.setAttribute(MECHANISM_ATTRIBUTE, ntlm ? "NTLM" : "Kerberos");
        chain.doFilter(req, res);
    }

    private static final class WithoutAuthorization extends HttpServletRequestWrapper {
        WithoutAuthorization(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return AUTHORIZATION.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return AUTHORIZATION.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(n -> !AUTHORIZATION.equalsIgnoreCase(n)).toList());
        }
    }
}
