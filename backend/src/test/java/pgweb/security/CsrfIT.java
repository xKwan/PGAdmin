package pgweb.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.authz.Access;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestApps;
import pgweb.web.ApiServlet;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** CSRF protection on state-changing requests (threat T05, SEC-09). */
class CsrfIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static Application app;
    static final AtomicInteger SIDE_EFFECTS = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        app = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), routes -> routes
                .servlet("t_change", new ApiServlet() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected Object post(HttpServletRequest req) {
                        SIDE_EFFECTS.incrementAndGet();
                        return Map.of("changed", true);
                    }

                    @Override
                    protected Object get(HttpServletRequest req) {
                        return Map.of("read", true);
                    }
                }, Access.AUTHENTICATED, "/api/test/change"), Clock.systemUTC()));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static MockClient signedIn(Random r) throws Exception {
        int id = 1000 + r.nextInt(9000);
        MockClient c = MockClient.user(app.port(), "TEST\\csrf" + id, "S-1-5-21-5-5-5-" + id);
        c.me();
        return c;
    }

    HttpResponse<String> post(MockClient c, Map<String, String> headers) throws Exception {
        Map<String, String> h = new HashMap<>(headers);
        h.put("Content-Type", "application/json");
        return c.send("POST", "/api/test/change", "{}", h);
    }

    @Test
    void validRequestsPass() throws Exception {
        MockClient c = signedIn(Seeds.random("csrf-ok"));
        int before = SIDE_EFFECTS.get();
        assertThat(post(c, Map.of("Origin", c.origin(), "X-CSRF-Token", c.csrf())).statusCode()).isEqualTo(200);
        assertThat(post(c, Map.of("Sec-Fetch-Site", "same-origin", "X-CSRF-Token", c.csrf())).statusCode()).isEqualTo(200);
        assertThat(SIDE_EFFECTS.get()).isEqualTo(before + 2);
        assertThat(c.get("/api/test/change").statusCode()).isEqualTo(200); // GET never needs the token
    }

    @Test
    void forgedRequestsAreRefusedAndAudited() throws Exception {
        Random r = Seeds.random("csrf-forged");
        MockClient c = signedIn(r);
        MockClient other = signedIn(r);
        String ok = c.origin();
        List<Map<String, String>> attacks = List.of(
                Map.of("Origin", ok),                                                         // no token
                Map.of("Origin", ok, "X-CSRF-Token", (c.csrf().charAt(0) == 'x' ? "y" : "x") + c.csrf().substring(1)), // wrong token
                Map.of("Origin", ok, "X-CSRF-Token", other.csrf()),                           // another session's token
                Map.of("Origin", "http://evil.example", "X-CSRF-Token", c.csrf()),            // foreign origin
                Map.of("Origin", "http://localhost:" + (app.port() + 1), "X-CSRF-Token", c.csrf()), // other port
                Map.of("Origin", "null", "X-CSRF-Token", c.csrf()),                           // opaque origin
                Map.of("Sec-Fetch-Site", "cross-site", "X-CSRF-Token", c.csrf()),             // cross-site, no Origin
                Map.of("X-CSRF-Token", c.csrf()));                                            // neither header
        int before = SIDE_EFFECTS.get();
        for (Map<String, String> a : attacks) {
            HttpResponse<String> res = post(c, a);
            assertThat(res.statusCode()).as(a.toString()).isEqualTo(403);
            assertThat(res.body()).contains("CSRF_FAILED");
        }
        assertThat(SIDE_EFFECTS.get()).as("no forged request reached the handler").isEqualTo(before);
        String sid = c.me().path("user").path("sid").asText();
        assertThat(apps.events("authz_denied", sid)).hasSizeGreaterThanOrEqualTo(attacks.size())
                .anyMatch(e -> String.valueOf(e.get("details")).contains("csrf_token"))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("csrf_origin"))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("csrf_no_origin"));
    }

    @Test
    void aRequestThatSignsInAgainWithAStaleTokenIsToldToReload() throws Exception {
        // The page's app session expired; the browser signed in again by itself on an unsafe request, which therefore
        // carries the old session's token (finding P11-2). Nothing runs; SESSION_RENEWED makes the page reload.
        MockClient old = signedIn(Seeds.random("csrf-renew"));
        String staleToken = old.csrf();
        MockClient renewed = MockClient.user(app.port(), "TEST\\csrfrenew", "S-1-5-21-5-5-5-77"); // no session yet
        int before = SIDE_EFFECTS.get();
        HttpResponse<String> r = post(renewed, Map.of("Origin", renewed.origin(), "X-CSRF-Token", staleToken));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("SESSION_RENEWED");
        assertThat(r.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(SIDE_EFFECTS.get()).isEqualTo(before);
        // A cross-site request in the same situation is still refused as CSRF.
        HttpResponse<String> forged = post(MockClient.user(app.port(), "TEST\\csrfrenew", "S-1-5-21-5-5-5-77"),
                Map.of("Origin", "http://evil.example", "X-CSRF-Token", staleToken));
        assertThat(forged.statusCode()).isEqualTo(403);
        assertThat(forged.body()).contains("CSRF_FAILED");
        // With the session established, a wrong token is plain CSRF again.
        renewed.me();
        assertThat(post(renewed, Map.of("Origin", renewed.origin(), "X-CSRF-Token", staleToken)).body()).contains("CSRF_FAILED");
    }

    @Test
    void otherUnsafeMethodsAreProtectedToo() throws Exception {
        MockClient c = signedIn(Seeds.random("csrf-methods"));
        for (String m : List.of("PUT", "DELETE", "PATCH")) {
            HttpResponse<String> r = c.send(m, "/api/test/change", "{}", Map.of("Content-Type", "application/json", "Origin", "http://evil.example"));
            assertThat(r.statusCode()).as(m).isEqualTo(403);
        }
    }
}
