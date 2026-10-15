package pgweb.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import waffle.servlet.NegotiateSecurityFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Waffle's {@link NegotiateSecurityFilter} (ADR-0001) with a fixed, reviewed configuration:
 * Negotiate (plus NTLM in test mode only), no Basic, no guest login, no impersonation, principal names as
 * domain-qualified ("fqn"). The public health endpoints bypass it by exact path ({@link PublicPaths}).
 */
public final class WaffleAuthenticationFilter implements Filter {

    private final NegotiateSecurityFilter waffle = new NegotiateSecurityFilter();
    private final Map<String, String> params = new LinkedHashMap<>();

    public WaffleAuthenticationFilter(Set<String> protocols) {
        params.put("principalFormat", "fqn");
        params.put("roleFormat", "both");
        params.put("allowGuestLogin", "false");
        params.put("impersonate", "false");
        params.put("securityFilterProviders", "waffle.servlet.spi.NegotiateSecurityFilterProvider");
        params.put("waffle.servlet.spi.NegotiateSecurityFilterProvider/protocols", String.join(" ", protocols));
    }

    @Override
    public void init(FilterConfig outer) throws ServletException {
        waffle.init(new FilterConfig() {
            @Override
            public String getFilterName() {
                return outer.getFilterName();
            }

            @Override
            public ServletContext getServletContext() {
                return outer.getServletContext();
            }

            @Override
            public String getInitParameter(String name) {
                return params.get(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(params.keySet());
            }
        });
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest req && PublicPaths.isPublic(req)) {
            chain.doFilter(request, response);
            return;
        }
        waffle.doFilter(request, response, chain);
    }

    @Override
    public void destroy() {
        waffle.destroy();
    }
}
