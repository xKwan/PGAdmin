package pgweb.security;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pgweb.Application;
import pgweb.testsupport.MutableClock;
import pgweb.testsupport.NegotiateClient;
import pgweb.testsupport.RawHttp;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestApps;
import pgweb.testsupport.TestConfig;
import pgweb.web.Json;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Windows integrated authentication through Waffle (plan Phase 4), with the account running the tests and NTLM,
 * because the workstation has no domain. Kerberos is Untested in target environment (checklist K1).
 */
class AuthIT {

    @TempDir
    static Path tmp;

    static TestApps apps;
    static MutableClock clock;
    /** DataAdmin = BUILTIN\Users, which every local account belongs to. */
    static Application admin;
    /** DataAdmin = a group nobody here belongs to. */
    static Application plain;
    /** Production-style: Kerberos only. */
    static Application kerberosOnly;
    /** Only another domain may sign in. */
    static Application otherDomain;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        clock = new MutableClock(Instant.now());
        Application.Options withClock = new Application.Options(null, r -> { }, clock);
        admin = apps.app(p -> { }, withClock);
        Random r = Seeds.random("foreign-group");
        String foreign = "S-1-5-21-" + (1_000_000 + r.nextInt(9_000_000)) + "-" + (100 + r.nextInt(900)) + "-" + (1000 + r.nextInt(9000));
        plain = apps.app(p -> p.setProperty("auth.dataAdminSid", foreign), Application.Options.defaults());
        kerberosOnly = apps.app(p -> {
            p.setProperty("auth.kerberosOnly", "true");
            p.setProperty("auth.protocols", "Negotiate");
        }, Application.Options.defaults());
        otherDomain = apps.app(p -> p.setProperty("auth.allowedDomains", "NOT-THIS-ONE"), Application.Options.defaults());
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    record Login(NegotiateClient.Result result, String cookie, JsonNode me) {
    }

    /** Full handshake on /api/me; returns the session cookie and the parsed body. */
    static Login login(Application app, String scheme) throws Exception {
        try (RawHttp http = new RawHttp(app.port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, "/api/me", scheme, Map.of());
            String cookie = null;
            for (String sc : r.response().header("Set-Cookie")) {
                if (sc.startsWith("JSESSIONID=")) {
                    cookie = sc;
                }
            }
            JsonNode me = r.response().body().isEmpty() ? null : Json.mapper().readTree(r.response().body());
            return new Login(r, cookie, me);
        }
    }

    static String cookieValue(String setCookie) {
        return setCookie.split(";", 2)[0];
    }

    static RawHttp.Response send(Application app, String method, String path, Map<String, String> headers, String body) throws Exception {
        try (RawHttp http = new RawHttp(app.port())) {
            return http.send(method, path, headers, body);
        }
    }

    @Test
    void unauthenticatedRequestsGetANegotiateChallengeWithoutBasic() throws Exception {
        RawHttp.Response r = send(admin, "GET", "/api/me", Map.of(), null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("WWW-Authenticate")).contains("Negotiate", "NTLM").noneMatch(v -> v.toLowerCase().startsWith("basic"));
        RawHttp.Response k = send(kerberosOnly, "GET", "/api/me", Map.of(), null);
        assertThat(k.header("WWW-Authenticate")).containsExactly("Negotiate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Negotiate", "NTLM"})
    void signInGivesIdentityFromTheWindowsToken(String scheme) throws Exception {
        Login l = login(admin, scheme);
        assertThat(l.result().response().status()).as(l.result().response().body()).isEqualTo(200);
        JsonNode user = l.me().path("user");
        assertThat(user.path("account").asText()).isEqualToIgnoringCase(NegotiateClient.currentUserFqn());
        assertThat(user.path("sid").asText()).isEqualTo(NegotiateClient.currentUserSid());
        assertThat(user.path("dataAdmin").asBoolean()).isTrue();
        assertThat(user.path("authMechanism").asText()).isEqualTo("NTLM");
        assertThat(l.me().path("csrfToken").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(l.me().path("capabilities").path("internal").asBoolean()).isTrue();
        assertThat(l.cookie()).contains("HttpOnly").containsIgnoringCase("SameSite=Strict");
        List<Map<String, Object>> logins = apps.events("app_login", NegotiateClient.currentUserSid());
        assertThat(logins).isNotEmpty();
        assertThat(String.valueOf(logins.get(0).get("details"))).contains("NTLM");
        assertThat(logins.get(0).get("app_session_hash")).asString().hasSize(32);
        assertThat(logins.get(0).get("data_admin")).isEqualTo(true);
    }

    @Test
    void theSessionCookieAloneAuthenticatesLaterRequests() throws Exception {
        Login l = login(admin, "Negotiate");
        RawHttp.Response r = send(admin, "GET", "/api/me", Map.of("Cookie", cookieValue(l.cookie())), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(Json.mapper().readTree(r.body()).path("csrfToken").asText()).isEqualTo(l.me().path("csrfToken").asText());
        // A made-up session ID is not adopted.
        RawHttp.Response forged = send(admin, "GET", "/api/me", Map.of("Cookie", "JSESSIONID=ABCDEF0123456789ABCDEF0123456789"), null);
        assertThat(forged.status()).isEqualTo(401);
    }

    @Test
    void unsupportedSchemesGet401NotA500() throws Exception {
        Random r = Seeds.random("schemes");
        for (String scheme : List.of("Basic", "Bearer", "Digest", "AWS4-HMAC-SHA256")) {
            byte[] junk = new byte[8 + r.nextInt(200)];
            r.nextBytes(junk);
            RawHttp.Response res = send(admin, "GET", "/api/me", Map.of("Authorization", scheme + " " + Base64.getEncoder().encodeToString(junk)), null);
            assertThat(res.status()).as(scheme).isEqualTo(401);
        }
    }

    @Test
    void dataAdminIsFalseOutsideTheConfiguredGroup() throws Exception {
        Login l = login(plain, "Negotiate");
        assertThat(l.me().path("user").path("dataAdmin").asBoolean()).isFalse();
        assertThat(l.me().path("capabilities").path("internal").asBoolean()).isFalse();
        assertThat(l.me().path("capabilities").path("external").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Negotiate", "NTLM"})
    void kerberosOnlyModeRefusesNtlm(String scheme) throws Exception {
        Login l = login(kerberosOnly, scheme);
        assertThat(l.result().response().status()).isEqualTo(401);
        assertThat(l.result().response().body()).contains("KERBEROS_REQUIRED");
        assertThat(l.result().response().header("WWW-Authenticate")).containsExactly("Negotiate");
        assertThat(l.cookie()).isNull();
    }

    @Test
    void usersFromOtherDomainsAreRefusedAndAudited() throws Exception {
        Login l = login(otherDomain, "Negotiate");
        assertThat(l.result().response().status()).isEqualTo(403);
        assertThat(l.result().response().body()).contains("DOMAIN_NOT_ALLOWED");
        assertThat(apps.events("authz_denied", NegotiateClient.currentUserSid()))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("domain_not_allowed"));
    }

    @Test
    void theAbsoluteSessionLifetimeIsEnforced() throws Exception {
        Login l = login(admin, "Negotiate");
        clock.advance(Duration.ofHours(8).plusMinutes(1));
        RawHttp.Response r = send(admin, "GET", "/api/me", Map.of("Cookie", cookieValue(l.cookie())), null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).contains("SESSION_EXPIRED");
        assertThat(apps.events("app_logout", NegotiateClient.currentUserSid()))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("absolute_lifetime"));
        // A new sign-in works again.
        assertThat(login(admin, "Negotiate").result().response().status()).isEqualTo(200);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Login l = login(admin, "Negotiate");
        String cookie = cookieValue(l.cookie());
        String origin = "http://localhost:" + admin.port();
        RawHttp.Response out = send(admin, "POST", "/api/logout",
                Map.of("Cookie", cookie, "Origin", origin, "X-CSRF-Token", l.me().path("csrfToken").asText(), "Content-Type", "application/json"), "{}");
        assertThat(out.status()).as(out.body()).isEqualTo(204);
        assertThat(send(admin, "GET", "/api/me", Map.of("Cookie", cookie), null).status()).isEqualTo(401);
        assertThat(apps.events("app_logout", NegotiateClient.currentUserSid()))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("\"user\""));
    }

    @Test
    void securityHeadersOnEveryResponse() throws Exception {
        Login l = login(admin, "Negotiate");
        String cookie = cookieValue(l.cookie());
        for (RawHttp.Response r : List.of(
                send(admin, "GET", "/api/me", Map.of("Cookie", cookie), null),
                send(admin, "GET", "/api/me", Map.of(), null),
                send(admin, "GET", "/api/nowhere", Map.of("Cookie", cookie), null),
                send(admin, "GET", "/api/health", Map.of(), null))) {
            assertThat(r.header("Content-Security-Policy")).as("status " + r.status()).containsExactly(SecurityHeadersFilter.API_CSP);
            assertThat(r.header("X-Frame-Options")).containsExactly("DENY");
            assertThat(r.header("X-Content-Type-Options")).contains("nosniff");
            assertThat(r.header("Referrer-Policy")).containsExactly("no-referrer");
            assertThat(r.header("Cross-Origin-Opener-Policy")).containsExactly("same-origin");
            assertThat(r.header("Strict-Transport-Security")).isEmpty(); // HTTP in tests; set only under TLS
        }
    }

    @Test
    void healthEndpointsNeedNoAuthentication() throws Exception {
        assertThat(send(admin, "GET", "/api/health", Map.of(), null).status()).isEqualTo(200);
        assertThat(send(admin, "GET", "/api/ready", Map.of(), null).status()).isEqualTo(200);
        // Exact paths only: no prefix or suffix tricks.
        assertThat(send(admin, "GET", "/api/health/../me", Map.of(), null).status()).isIn(400, 401);
        assertThat(send(admin, "GET", "/api/healthx", Map.of(), null).status()).isEqualTo(401);
        assertThat(send(admin, "GET", "/api/health;x=1", Map.of(), null).status()).isIn(200, 400, 401);
    }

    @Test
    void mockAuthenticationIsRefusedInProdMode() throws Exception {
        java.util.Properties p = TestConfig.base(tmp.resolve("prod"));
        p.setProperty("pgweb.mode", "prod");
        p.setProperty("auth.protocols", "Negotiate");
        p.setProperty("auth.kerberosOnly", "true");
        p.setProperty("server.publicHost", "pgweb.corp.example");
        p.setProperty("server.tls.keystore", java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(tmp.resolve("prod")).resolve("ks.p12"), "x").toString());
        p.setProperty("server.tls.keystorePassword", "env:PATH");
        p.setProperty("audit.password", "env:PATH");
        p.setProperty("audit.hmacKey", "env:PATH");
        p.setProperty("profile.external.sslMode", "disable");
        var cfg = pgweb.config.ConfigLoader.load(p, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Application.start(cfg,
                        new Application.Options(new pgweb.testsupport.MockAuthFilter(), r -> { }, java.time.Clock.systemUTC())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("T26");
    }
}
