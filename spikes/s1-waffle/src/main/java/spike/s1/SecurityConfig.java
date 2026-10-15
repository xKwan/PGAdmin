package spike.s1;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.GenericFilterBean;
import waffle.servlet.WindowsPrincipal;
import waffle.servlet.spi.NegotiateSecurityFilterProvider;
import waffle.servlet.spi.SecurityFilterProvider;
import waffle.servlet.spi.SecurityFilterProviderCollection;
import waffle.spring.NegotiateSecurityFilter;
import waffle.spring.NegotiateSecurityFilterEntryPoint;
import waffle.windows.auth.impl.WindowsAuthProviderImpl;

import java.io.IOException;
import java.util.List;

/**
 * Waffle wired into Spring Security the way the application would use it (plan §2.3):
 * <ul>
 *   <li>Negotiate and NTLM only. No Basic, no form login, no guest login, no impersonation.</li>
 *   <li>DataAdmin is decided on the server from the Windows group <b>SID</b> in {@code pgweb.data-admin-sid}
 *       (a SID survives group renames). The real source is decision D1; this spike uses a local group.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    WindowsAuthProviderImpl waffleWindowsAuthProvider() {
        return new WindowsAuthProviderImpl();
    }

    @Bean
    SecurityFilterProviderCollection waffleSecurityFilterProviders(WindowsAuthProviderImpl authProvider) {
        NegotiateSecurityFilterProvider negotiate = new NegotiateSecurityFilterProvider(authProvider);
        negotiate.setProtocols(List.of("Negotiate", "NTLM"));
        return new SecurityFilterProviderCollection(new SecurityFilterProvider[] {negotiate});
    }

    @Bean
    NegotiateSecurityFilterEntryPoint negotiateEntryPoint(SecurityFilterProviderCollection providers) {
        NegotiateSecurityFilterEntryPoint entryPoint = new NegotiateSecurityFilterEntryPoint();
        entryPoint.setProvider(providers);
        return entryPoint;
    }

    /**
     * Built here rather than exposed as a bean, so that Spring Boot does not also register it as a plain servlet
     * filter: it must run inside the Spring Security chain only.
     */
    static NegotiateSecurityFilter waffleNegotiateSecurityFilter(SecurityFilterProviderCollection providers) throws Exception {
        NegotiateSecurityFilter filter = new NegotiateSecurityFilter();
        filter.setProvider(providers);
        filter.setAllowGuestLogin(false);
        filter.setImpersonate(false);
        filter.setPrincipalFormat("fqn");
        filter.setRoleFormat("both");
        filter.afterPropertiesSet();
        return filter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityFilterProviderCollection providers,
            NegotiateSecurityFilterEntryPoint entryPoint, @Value("${pgweb.data-admin-sid}") String dataAdminSid) throws Exception {
        NegotiateSecurityFilter waffle = waffleNegotiateSecurityFilter(providers);
        http
                .authorizeHttpRequests(auth -> auth
                        // The container forwards errors (e.g. a 403) to /error; that dispatch carries no
                        // authentication of its own (S1 finding W3).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/admin/**").access((authentication, context) ->
                                new AuthorizationDecision(isDataAdmin(authentication.get(), dataAdminSid)))
                        .anyRequest().authenticated())
                .addFilterBefore(new RequestDispatchOnly(waffle), BasicAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Runs Waffle on the original request only. On the container's internal ERROR forward (e.g. after a 403) Waffle
     * would otherwise try the already-consumed NTLM token again, fail, and overwrite the 403 with a 401 challenge
     * (S1 finding W3: observed on Spring Boot 3.5 and 4.1).
     */
    static final class RequestDispatchOnly extends GenericFilterBean {
        private final Filter delegate;

        RequestDispatchOnly(Filter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            if (request.getDispatcherType() == DispatcherType.REQUEST) {
                delegate.doFilter(request, response, chain);
            } else {
                chain.doFilter(request, response);
            }
        }
    }

    /** DataAdmin = the Windows identity is a member of the configured group SID (checked on the server only). */
    static boolean isDataAdmin(Authentication authentication, String dataAdminSid) {
        if (authentication == null || !(authentication.getPrincipal() instanceof WindowsPrincipal principal)) {
            return false;
        }
        return principal.getGroups().values().stream().anyMatch(g -> dataAdminSid.equalsIgnoreCase(g.getSidString()));
    }
}
