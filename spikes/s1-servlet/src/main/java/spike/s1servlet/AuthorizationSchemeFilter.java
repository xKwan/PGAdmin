package spike.s1servlet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Runs before Waffle. Waffle's servlet filter throws a RuntimeException (HTTP 500) when the Authorization header uses
 * a scheme it has no provider for, such as Basic or Bearer (S1 servlet finding W8). This filter hides such headers,
 * so Waffle treats the request as unauthenticated and answers with its normal Negotiate/NTLM challenge.
 */
final class AuthorizationSchemeFilter extends HttpFilter {

    static boolean supported(String authorization) {
        if (authorization == null) {
            return true;
        }
        String scheme = authorization.strip().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        return scheme.equals("negotiate") || scheme.equals("ntlm");
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (supported(req.getHeader("Authorization"))) {
            chain.doFilter(req, res);
        } else {
            chain.doFilter(new WithoutAuthorization(req), res);
        }
    }

    private static final class WithoutAuthorization extends HttpServletRequestWrapper {
        WithoutAuthorization(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return "authorization".equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return "authorization".equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(n -> !"authorization".equalsIgnoreCase(n)).toList());
        }
    }
}
