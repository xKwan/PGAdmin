package pgweb.web;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.TestApps;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Serving the SPA from the JAR (fixture in src/test/resources/static): nonce CSP, caching, fallback, traversal. */
class StaticContentIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static Application app;
    static MockClient user;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        app = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC()));
        user = MockClient.user(app.port(), "TEST\\web", "S-1-5-21-3-3-3-1001");
        user.me();
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static String nonceIn(String html) {
        Matcher m = Pattern.compile("<meta name=\"csp-nonce\" content=\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void indexHtmlCarriesAFreshNonceMatchingItsPolicy() throws Exception {
        HttpResponse<String> a = user.get("/");
        HttpResponse<String> b = user.get("/");
        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(a.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/html"));
        String n1 = nonceIn(a.body());
        String n2 = nonceIn(b.body());
        assertThat(n1).isNotNull().isNotEqualTo(n2);
        String csp = a.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).contains("'nonce-" + n1 + "'").contains("script-src 'self'").contains("frame-ancestors 'none'")
                .doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
        assertThat(a.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void assetsAreServedWithTypesAndLongCaching() throws Exception {
        HttpResponse<String> js = user.get("/assets/app-abc123.js");
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(js.body()).contains("fixture");
        assertThat(js.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/javascript"));
        assertThat(js.headers().firstValue("Cache-Control")).contains("public, max-age=31536000, immutable");
        assertThat(user.get("/assets/app-abc123.css").headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/css"));
        HttpResponse<String> head = user.send("HEAD", "/assets/app-abc123.js", null, Map.of());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
    }

    @Test
    void clientSideViewsFallBackToIndexButMissingFilesAndApiPathsDoNot() throws Exception {
        for (String view : List.of("/internal", "/external/workspace", "/settings")) {
            HttpResponse<String> r = user.get(view);
            assertThat(r.statusCode()).as(view).isEqualTo(200);
            assertThat(nonceIn(r.body())).isNotNull();
        }
        assertThat(user.send("HEAD", "/somewhere", null, Map.of()).statusCode()).isEqualTo(200);
        for (String missing : List.of("/assets/nope.js", "/favicon.ico", "/x.exe", "/api/nope", "/api")) {
            HttpResponse<String> r = user.get(missing);
            assertThat(r.statusCode()).as(missing).isEqualTo(404);
            assertThat(r.body()).contains("NOT_FOUND");
        }
    }

    @Test
    void traversalAndOddPathsAreRefused() throws Exception {
        for (String p : List.of("/assets/..%2f..%2fWEB-INF%2fweb.xml", "/assets/%2e%2e/index.html", "/a%5cb", "/assets//app-abc123.js",
                "/assets/app-abc123.js%00.html", "/assets/app%20abc.js", "/assets/..;/x")) {
            HttpResponse<String> r = user.get(p);
            assertThat(r.statusCode()).as(p).isIn(400, 404);
            assertThat(r.body()).doesNotContain("fixture").doesNotContain("Tomcat");
        }
    }

    @Test
    void writesAreRejectedAndAnonymousUsersMustSignIn() throws Exception {
        assertThat(user.post("/", "{}").statusCode()).isEqualTo(405);
        assertThat(user.post("/assets/app-abc123.js", "{}").statusCode()).isEqualTo(405);
        assertThat(MockClient.anonymous(app.port()).get("/").statusCode()).isEqualTo(401);
        assertThat(MockClient.anonymous(app.port()).get("/assets/app-abc123.js").statusCode()).isEqualTo(401);
    }

    @Test
    void extensionParsing() {
        assertThat(StaticContentServlet.extension("assets/app.JS")).isEqualTo("js");
        assertThat(StaticContentServlet.extension("assets.d/app")).isEmpty();
        assertThat(StaticContentServlet.extension("index.html")).isEqualTo("html");
    }
}
