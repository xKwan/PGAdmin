package pgweb.server;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pgweb.Application;
import pgweb.audit.AuditMigrator;
import pgweb.config.Config;
import pgweb.testsupport.LogCapture;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.PgTestCluster;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestConfig;
import pgweb.web.ApiServlet;
import pgweb.web.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP layer of the real service on embedded Tomcat (plan Phase 3 acceptance): JSON error envelope without
 * internals, strict request parsing, request IDs, readiness tied to the audit DB, and redacted logs.
 */
class ServerIT {

    /** Test-only endpoints, added through the same route list the application uses. */
    public record Echo(String name, int count) {
    }

    static PgTestCluster pg;
    static Application app;
    static HttpClient http;
    @org.junit.jupiter.api.io.TempDir
    static Path tmp;
    static String secretInError;

    @BeforeAll
    static void start() throws Exception {
        pg = PgTestCluster.start();
        pg.bootstrapAudit();
        AuditMigrator.migrate(pg.jdbcUrl("pgweb_audit"), "pgweb_migrator", pg.migratorPassword(), "pgweb_app");
        Config cfg = TestConfig.load(TestConfig.forCluster(pg, "pgweb_audit", tmp));
        secretInError = "Sx" + Long.toHexString(Seeds.random("server-secret").nextLong());
        app = Application.start(cfg, new Application.Options(new MockAuthFilter(), routes -> routes
                .servlet("echo", new ApiServlet() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected Object post(HttpServletRequest req) {
                        Echo e = body(req, Echo.class);
                        return Map.of("name", e.name(), "count", e.count());
                    }
                }, pgweb.authz.Access.AUTHENTICATED, "/api/test/echo")
                .servlet("boom", new ApiServlet() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected Object get(HttpServletRequest req) {
                        throw new IllegalStateException("database password=" + secretInError + " at host internal-db");
                    }
                }, pgweb.authz.Access.AUTHENTICATED, "/api/test/boom"), java.time.Clock.systemUTC()));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).cookieHandler(new java.net.CookieManager()).build();
        HttpResponse<String> me = http.send(withUser(HttpRequest.newBuilder(uri("/api/me"))).GET().build(), HttpResponse.BodyHandlers.ofString());
        csrf = Json.mapper().readTree(me.body()).path("csrfToken").asText();
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) {
            app.close();
        }
        if (pg != null) {
            pg.close();
        }
    }

    static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + app.port() + path);
    }

    static String csrf;

    /** Every request acts as one test user (the mock provider stands in for Waffle) with the session's CSRF token. */
    static HttpRequest.Builder withUser(HttpRequest.Builder b) {
        return b.header(MockAuthFilter.HEADER, MockAuthFilter.header("TEST\srv-user", "S-1-5-21-1-2-3-1001", "S-1-5-32-545"));
    }

    static HttpRequest.Builder unsafe(HttpRequest.Builder b) {
        return withUser(b).header("Origin", "http://localhost:" + app.port()).header("X-CSRF-Token", csrf);
    }

    static HttpResponse<String> get(String path) throws Exception {
        return http.send(withUser(HttpRequest.newBuilder(uri(path))).GET().timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        HttpRequest.Builder b = unsafe(HttpRequest.newBuilder(uri(path))).POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(20));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        assertThat(r.headers().firstValue("Content-Type")).as(r.uri() + " -> " + r.statusCode() + " " + r.body()).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        return Json.mapper().readTree(r.body());
    }

    static void assertEnvelope(HttpResponse<String> r, int status, String code) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        if (status == 400 && r.body().isEmpty()) {
            return; // Tomcat's connector rejects malformed URIs itself, with an empty body: nothing leaks either way
        }
        JsonNode n = json(r);
        assertThat(n.path("error").path("code").asText()).isEqualTo(code);
        if (r.headers().firstValue("X-Request-Id").isPresent()) {
            assertThat(n.path("error").path("requestId").asText()).matches("[0-9a-f]{16}")
                    .isEqualTo(r.headers().firstValue("X-Request-Id").orElseThrow());
        } else {
            // Rejected by the connector before any filter ran (malformed URI): no request ID exists.
            assertThat(n.path("error").has("requestId")).isFalse();
        }
        assertThat(r.body()).doesNotContain("Tomcat").doesNotContain("Exception").doesNotContain("at pgweb.").doesNotContain("java.");
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void healthAndReadiness() throws Exception {
        HttpResponse<String> h = get("/api/health");
        assertThat(h.statusCode()).isEqualTo(200);
        assertThat(json(h).path("status").asText()).isEqualTo("UP");
        assertThat(h.headers().firstValue("X-Request-Id")).isPresent();
        HttpResponse<String> r = get("/api/ready");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).path("status").asText()).isEqualTo("READY");
        assertThat(h.body() + r.body()).doesNotContain("version").doesNotContain("0.1.0");
    }

    @Test
    void unknownPathsGetAJsonEnvelopeWithoutServerDetails() throws Exception {
        Random rnd = Seeds.random("unknown-paths");
        for (int i = 0; i < 5; i++) {
            String p = List.of("/api/nope", "/x", "/api/test/echo/extra", "/WEB-INF/web.xml", "/%2e%2e/etc").get(rnd.nextInt(5)) + "?q=" + rnd.nextInt();
            HttpResponse<String> r = get(p);
            assertThat(r.statusCode()).as(p).isIn(400, 404);
            try {
                assertEnvelope(r, r.statusCode(), r.statusCode() == 404 ? "NOT_FOUND" : "BAD_REQUEST");
            } catch (AssertionError e) {
                throw new AssertionError(p + " -> " + r.statusCode() + " " + r.headers().map() + " " + r.body(), e);
            }
            assertThat(r.headers().firstValue("Server").orElse("")).doesNotContainPattern("\\d");
        }
    }

    @Test
    void wrongMethodIs405Envelope() throws Exception {
        assertEnvelope(post("/api/health", "application/json", "{}"), 405, "METHOD_NOT_ALLOWED");
        HttpResponse<String> del = http.send(unsafe(HttpRequest.newBuilder(uri("/api/me"))).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEnvelope(del, 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void validJsonIsAccepted() throws Exception {
        Random rnd = Seeds.random("echo");
        String name = "n" + rnd.nextInt(1000) + "é漢";
        int count = rnd.nextInt();
        HttpResponse<String> r = post("/api/test/echo", "application/json; charset=utf-8", Json.write(Map.of("name", name, "count", count)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).path("name").asText()).isEqualTo(name);
        assertThat(json(r).path("count").asInt()).isEqualTo(count);
    }

    @Test
    void strictRequestParsing() throws Exception {
        assertEnvelope(post("/api/test/echo", "application/json", "{\"name\":\"a\",\"count\":1,\"admin\":true}"), 400, "UNKNOWN_FIELD");
        assertThat(post("/api/test/echo", "application/json", "{\"name\":\"a\",\"count\":1,\"role\":\"x\"}").body()).contains("Unknown field: role");
        assertEnvelope(post("/api/test/echo", "application/json", "{\"name\":\"a\",\"count\":1"), 400, "BAD_JSON");
        assertEnvelope(post("/api/test/echo", "application/json", "{\"name\":\"a\",\"count\":\"1\"}"), 400, "BAD_JSON");
        assertEnvelope(post("/api/test/echo", "application/json", "{\"name\":\"a\",\"name\":\"b\",\"count\":1}"), 400, "BAD_JSON");
        assertEnvelope(post("/api/test/echo", "application/json", "{\"name\":\"a\",\"count\":1} {}"), 400, "BAD_JSON");
        assertEnvelope(post("/api/test/echo", "application/json", "null"), 400, "BAD_JSON");
        assertEnvelope(post("/api/test/echo", "text/plain", "{\"name\":\"a\",\"count\":1}"), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertEnvelope(post("/api/test/echo", null, "{\"name\":\"a\",\"count\":1}"), 415, "UNSUPPORTED_MEDIA_TYPE");
        String big = "{\"name\":\"" + "x".repeat(70_000) + "\",\"count\":1}";
        assertEnvelope(post("/api/test/echo", "application/json", big), 413, "TOO_LARGE");
    }

    @Test
    void chunkedBodyOverTheLimitIsRejected() throws Exception {
        byte[] big = ("{\"name\":\"" + "y".repeat(80_000) + "\",\"count\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest req = unsafe(HttpRequest.newBuilder(uri("/api/test/echo"))).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isIn(400, 413);
        assertThat(r.body()).doesNotContain("Exception");
    }

    @Test
    void unhandledErrorsGiveAGenericEnvelopeAndARedactedLog() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            HttpResponse<String> r = get("/api/test/boom");
            assertEnvelope(r, 500, "INTERNAL");
            assertThat(r.body()).doesNotContain(secretInError).doesNotContain("internal-db");
            String rid = r.headers().firstValue("X-Request-Id").orElseThrow();
            String text = logs.text();
            assertThat(text).contains(rid).contains("IllegalStateException").contains("password=***");
            assertThat(text).doesNotContain(secretInError);
        }
    }

    @Test
    void requestIdsAreFreshAndNotTakenFromTheClient() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri("/api/health")).header("X-Request-Id", "attacker-chosen-id").build();
        HttpResponse<String> a = http.send(req, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> b = get("/api/health");
        String ida = a.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(ida).matches("[0-9a-f]{16}").isNotEqualTo(b.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test
    void readinessFollowsTheAuditDatabase() throws Exception {
        // Make the audit DB unreachable for the runtime role, then restore it.
        try (Connection c = pg.superuser("postgres"); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE pgweb_app NOLOGIN");
            s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'pgweb_app'");
        }
        try {
            HttpResponse<String> r = null;
            for (int i = 0; i < 20; i++) {
                r = get("/api/ready");
                if (r.statusCode() == 503) {
                    break;
                }
                Thread.sleep(250);
            }
            assertEnvelope(r, 503, "AUDIT_UNAVAILABLE");
            assertThat(get("/api/health").statusCode()).isEqualTo(200);
        } finally {
            try (Connection c = pg.superuser("postgres"); Statement s = c.createStatement()) {
                s.execute("ALTER ROLE pgweb_app LOGIN");
            }
        }
        assertThat(get("/api/ready").statusCode()).isEqualTo(200);
    }
}
