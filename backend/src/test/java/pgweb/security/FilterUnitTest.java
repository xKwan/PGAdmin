package pgweb.security;

import org.junit.jupiter.api.Test;
import pgweb.config.Config;
import pgweb.testsupport.ServletStubs;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Filter behaviour on paths that HTTP tests can't reach directly. */
class FilterUnitTest {

    private static final String NTLM_TOKEN = Base64.getEncoder().encodeToString("NTLMSSP\0\1\0\0\0rest".getBytes(StandardCharsets.ISO_8859_1));

    @Test
    void unsupportedSchemesAreHiddenFromEveryHeaderAccessor() throws Exception {
        var req = ServletStubs.request("GET", "/api/me", Map.of("Authorization", "Basic dXNlcjpwYXNz", "Accept", "*/*"), null, false);
        ServletStubs.Chain chain = new ServletStubs.Chain();
        new AuthSchemeFilter(true).doFilter(req, new ServletStubs.Recorded().response, chain);
        assertThat(chain.seen.getHeader("Authorization")).isNull();
        assertThat(chain.seen.getHeader("Accept")).isEqualTo("*/*");
        assertThat(Collections.list(chain.seen.getHeaders("authorization"))).isEmpty();
        assertThat(Collections.list(chain.seen.getHeaders("accept"))).containsExactly("*/*");
        assertThat(Collections.list(chain.seen.getHeaderNames())).containsExactly("accept");
    }

    @Test
    void mechanismIsRecordedAndNtlmRefusedOnlyInKerberosOnlyMode() throws Exception {
        byte[] kerberosLike = new byte[64];
        kerberosLike[0] = 0x60;
        String kerberos = "Negotiate " + Base64.getEncoder().encodeToString(kerberosLike);

        ServletStubs.Chain chain = new ServletStubs.Chain();
        var k = ServletStubs.request("GET", "/api/me", Map.of("Authorization", kerberos), null, false);
        new AuthSchemeFilter(true).doFilter(k, new ServletStubs.Recorded().response, chain);
        assertThat(chain.seen.getAttribute(AuthSchemeFilter.MECHANISM_ATTRIBUTE)).isEqualTo("Kerberos");

        ServletStubs.Chain chain2 = new ServletStubs.Chain();
        var n = ServletStubs.request("GET", "/api/me", Map.of("Authorization", "Negotiate " + NTLM_TOKEN), null, false);
        new AuthSchemeFilter(false).doFilter(n, new ServletStubs.Recorded().response, chain2);
        assertThat(chain2.seen.getAttribute(AuthSchemeFilter.MECHANISM_ATTRIBUTE)).isEqualTo("NTLM");

        ServletStubs.Recorded res = new ServletStubs.Recorded();
        ServletStubs.Chain chain3 = new ServletStubs.Chain();
        new AuthSchemeFilter(true).doFilter(ServletStubs.request("GET", "/api/me", Map.of("Authorization", "NTLM " + NTLM_TOKEN), null, false), res.response, chain3);
        assertThat(chain3.seen).isNull();
        assertThat(res.status).isEqualTo(401);
        assertThat(res.text()).contains("KERBEROS_REQUIRED");
        assertThat(res.headers).containsEntry("WWW-Authenticate", "Negotiate");
    }

    @Test
    void theSessionFilterFailsClosedWithoutAWindowsPrincipal() throws Exception {
        Config.Auth auth = new Config.Auth(Set.of("Negotiate"), true, "S-1-5-32-545", null, Set.of(), Duration.ofMinutes(30), Duration.ofHours(8));
        SessionFilter f = new SessionFilter(auth, null, Clock.systemUTC());
        ServletStubs.Recorded res = new ServletStubs.Recorded();
        ServletStubs.Chain chain = new ServletStubs.Chain();
        f.doFilter(ServletStubs.request("GET", "/api/me", Map.of(), () -> "CORP\\not-windows", false), res.response, chain);
        assertThat(chain.seen).isNull();
        assertThat(res.status).isEqualTo(401);
        ServletStubs.Chain pub = new ServletStubs.Chain();
        f.doFilter(ServletStubs.request("GET", "/api/health", Map.of(), null, false), new ServletStubs.Recorded().response, pub);
        assertThat(pub.seen).isNotNull();
    }

    @Test
    void hstsOnlyUnderTlsAndASpaPolicyWithItsNonce() throws Exception {
        ServletStubs.Recorded tls = new ServletStubs.Recorded();
        var req = ServletStubs.request("GET", "/", Map.of(), null, true);
        new SecurityHeadersFilter().doFilter(req, tls.response, new ServletStubs.Chain());
        assertThat(tls.headers).containsKey("Strict-Transport-Security");
        String nonce = (String) req.getAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE);
        assertThat(nonce).hasSize(24);
        assertThat(SecurityHeadersFilter.spaCsp(nonce)).contains("'nonce-" + nonce + "'").contains("script-src 'self'")
                .doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
        ServletStubs.Recorded plain = new ServletStubs.Recorded();
        new SecurityHeadersFilter().doFilter(ServletStubs.request("GET", "/", Map.of(), null, false), plain.response, new ServletStubs.Chain());
        assertThat(plain.headers).doesNotContainKey("Strict-Transport-Security");
    }

    @Test
    void publicPathsIgnoreTheContextPathOnlyWhenPresent() {
        var req = ServletStubs.request("GET", "/api/health", Map.of(), null, false);
        assertThat(PublicPaths.isPublic(req)).isTrue();
        assertThat(PublicPaths.isPublic(ServletStubs.request("GET", "/api/health/", Map.of(), null, false))).isFalse();
        assertThat(PublicPaths.isPublic(ServletStubs.request("GET", "/API/HEALTH", Map.of(), null, false))).isFalse();
    }

    @Test
    void csrfChecksWithoutASignedInUser() throws Exception {
        CsrfFilter f = new CsrfFilter(() -> Set.of("http://localhost:8080"), null);
        var req = ServletStubs.request("POST", "/api/x", Map.of("Origin", "http://localhost:8080"), null, false);
        assertThat(f.check(req)).isEqualTo("token");
        req.getSession(true).setAttribute(SessionFilter.CSRF_TOKEN, "abc");
        assertThat(f.check(req)).isEqualTo("token");
        var ok = ServletStubs.request("POST", "/api/x", Map.of("Origin", "http://localhost:8080", "X-CSRF-Token", "abc"), null, false);
        ok.getSession(true).setAttribute(SessionFilter.CSRF_TOKEN, "abc");
        assertThat(f.check(ok)).isNull();
        var noSession = ServletStubs.request("POST", "/api/x", Map.of("Origin", "http://localhost:8080", "X-CSRF-Token", "abc"), null, false);
        assertThat(f.check(noSession)).isEqualTo("token");
    }
}
