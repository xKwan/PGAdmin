package pgweb.authz;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.server.WebServer;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestApps;
import pgweb.testsupport.TestConfig;
import pgweb.web.ApiServlet;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The direct-API authorisation matrix (plan §8.1, SEC-01/03), generated from the application's own endpoint
 * inventory: every route × {anonymous, user, DataAdmin} × {GET, POST}. A route added later is covered automatically.
 */
class AuthorizationMatrixIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static Application app;

    static final String ADMIN_GROUP = TestConfig.USERS_SID;

    /** Answers 200 for GET and POST; used for the test routes. */
    static final class Ok extends ApiServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected Object get(HttpServletRequest req) {
            return Map.of("ok", true);
        }

        @Override
        protected Object post(HttpServletRequest req) {
            return Map.of("ok", true);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        app = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), routes -> routes
                .servlet("t_user", new Ok(), Access.AUTHENTICATED, "/api/test/user")
                .servlet("t_admin", new Ok(), Access.DATA_ADMIN, "/api/test/admin")
                .servlet("t_public_wrongly", new Ok(), Access.PUBLIC, "/api/test/public")
                .servlet("t_undeclared", new Ok(), null, "/api/test/undeclared"), Clock.systemUTC()));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    enum Who { ANONYMOUS, USER, DATA_ADMIN }

    static MockClient client(Who who, Random r) throws Exception {
        int id = 1000 + r.nextInt(9000);
        return switch (who) {
            case ANONYMOUS -> MockClient.anonymous(app.port());
            case USER -> {
                MockClient c = MockClient.user(app.port(), "TEST\\user" + id, "S-1-5-21-7-7-7-" + id, "S-1-5-21-7-7-7-513");
                c.me();
                yield c;
            }
            case DATA_ADMIN -> {
                MockClient c = MockClient.user(app.port(), "TEST\\admin" + id, "S-1-5-21-8-8-8-" + id, ADMIN_GROUP);
                c.me();
                yield c;
            }
        };
    }

    static String concretePath(WebServer.Routes.Route r) {
        String p = r.patterns().get(0);
        return p.equals("/") ? "/api/no-such-endpoint" : p.replace("/*", "/x");
    }

    @Test
    void everyRouteForEveryIdentityAndMethod() throws Exception {
        Random rnd = Seeds.random("matrix");
        List<String> failures = new ArrayList<>();
        int cells = 0;
        for (WebServer.Routes.Route route : app.inventory()) {
            String path = concretePath(route);
            boolean publicPath = pgweb.security.PublicPaths.PATHS.contains(path);
            for (Who who : Who.values()) {
                for (String method : List.of("GET", "POST")) {
                    if (route.name().equals("logout") && method.equals("POST") && who != Who.ANONYMOUS) {
                        continue; // logging out would end the client's session mid-matrix; covered in AuthIT
                    }
                    MockClient c = client(who, rnd);
                    HttpResponse<String> r = method.equals("GET") ? c.get(path) : c.post(path, "{}");
                    int s = r.statusCode();
                    String cell = route.name() + " " + route.access() + " " + who + " " + method + " -> " + s;
                    cells++;
                    if (publicPath) {
                        if (s == 401 || s == 403) {
                            failures.add(cell + " (public endpoint refused)");
                        }
                    } else if (who == Who.ANONYMOUS) {
                        if (s != 401) {
                            failures.add(cell + " (anonymous must get 401)");
                        }
                    } else if (route.access() == null || route.access() == Access.PUBLIC) {
                        if (s != 403) {
                            failures.add(cell + " (undeclared or misdeclared route must be denied)");
                        }
                    } else if (route.access() == Access.DATA_ADMIN && who == Who.USER) {
                        if (s != 403) {
                            failures.add(cell + " (non-DataAdmin must get 403)");
                        }
                    } else if ((s == 401 && !r.body().contains("DELEGATION_HANDSHAKE")) || s == 403) {
                        // Opening a DB connection answers an allowed user with a Windows handshake challenge
                        // (DELEGATION_HANDSHAKE): authorisation passed, the handshake comes next.
                        failures.add(cell + " (should be allowed)");
                    }
                }
            }
        }
        assertThat(failures).as("authorisation matrix (%d cells)", cells).isEmpty();
        assertThat(cells).isGreaterThanOrEqualTo(app.inventory().size() * 5);
    }

    @Test
    void forgedIdentityFieldsAreIgnored() throws Exception {
        MockClient u = client(Who.USER, Seeds.random("forged"));
        Map<String, String> forged = Map.of("X-Roles", "DataAdmin", "X-Data-Admin", "true", "X-Forwarded-User", "TEST\\admin",
                "X-Remote-User", "TEST\\admin");
        HttpResponse<String> me = u.send("GET", "/api/me?dataAdmin=true&user=admin", null, forged);
        assertThat(me.body()).contains("\"dataAdmin\":false");
        assertThat(u.send("GET", "/api/test/admin?dataAdmin=true", null, forged).statusCode()).isEqualTo(403);
        HttpResponse<String> post = u.post("/api/test/admin", "{\"dataAdmin\":true}");
        assertThat(post.statusCode()).isEqualTo(403);
    }

    @Test
    void deniedRequestsAreAudited() throws Exception {
        Random r = Seeds.random("denied-audit");
        MockClient u = client(Who.USER, r);
        u.get("/api/test/admin");
        u.get("/api/test/undeclared");
        String sid = u.me().path("user").path("sid").asText();
        List<Map<String, Object>> denied = apps.events("authz_denied", sid);
        assertThat(denied).anyMatch(e -> String.valueOf(e.get("details")).contains("not_data_admin"))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("undeclared_route"));
        assertThat(denied).allSatisfy(e -> assertThat(e.get("request_id")).asString().matches("[0-9a-f]{16}"));
    }

    @Test
    void aSessionIsBoundToItsUserAndRotatedAtSignIn() throws Exception {
        Random r = Seeds.random("binding");
        MockClient a = client(Who.USER, r);
        HttpResponse<String> first = MockClient.user(app.port(), "TEST\\rot", "S-1-5-21-9-9-9-1", "S-1-5-21-9-9-9-513").get("/api/me");
        String pre = first.headers().firstValue(MockAuthFilter.PRE_SESSION_HEADER).orElseThrow();
        String set = first.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(set).doesNotContain(pre); // the pre-sign-in session ID was replaced
        HttpResponse<String> replay = MockClient.anonymous(app.port()).send("GET", "/api/me", null, Map.of("Cookie", "JSESSIONID=" + pre));
        assertThat(replay.statusCode()).isEqualTo(401);

        // The same session used with a different user's identity is invalidated.
        String cookie = "JSESSIONID=" + set.split(";", 2)[0].substring("JSESSIONID=".length());
        HttpResponse<String> other = MockClient.anonymous(app.port()).send("GET", "/api/me", null,
                Map.of("Cookie", cookie, MockAuthFilter.HEADER, MockAuthFilter.header("TEST\\someone", "S-1-5-21-9-9-9-2")));
        assertThat(other.statusCode()).isEqualTo(401);
        assertThat(other.body()).contains("SESSION_INVALID");
        assertThat(a.get("/api/me").statusCode()).isEqualTo(200);
    }
}
