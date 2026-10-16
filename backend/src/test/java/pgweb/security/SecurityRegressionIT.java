package pgweb.security;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.testsupport.LogCapture;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.PasswordTestConnector;
import pgweb.testsupport.TestApps;
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 11 security regression suite, the parts other suites don't already cover: oversized bodies on every
 * route that reads one, markup in API errors, and a secret-leak scan over everything the app writes or returns
 * (logs, audit rows, spool files, HTTP responses) after a session that touches every feature.
 * (Authorisation matrix: AuthorizationMatrixIT; CSRF: CsrfIT; headers and CSP: AuthIT, StaticContentIT;
 * failed-open throttling and caps: DbSessionIT.)
 */
class SecurityRegressionIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static Application app;
    static String rolePassword;
    static final List<String> RESPONSES = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        rolePassword = "RolePw-" + UUID.randomUUID();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE sam LOGIN PASSWORD '" + rolePassword + "'");
            s.execute("CREATE DATABASE sec_db OWNER sam");
        }
        connector.password("sam", rolePassword);
        app = apps.app(p -> p.setProperty("execution.enabled", "true"), new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static HttpResponse<String> keep(HttpResponse<String> r) {
        synchronized (RESPONSES) {
            RESPONSES.add(r.headers().map() + "\n" + r.body());
        }
        return r;
    }

    static MockClient signIn(String rid) throws Exception {
        MockClient c = MockClient.user(app.port(), "TEST\\sam", "S-1-5-21-6-5-4-" + rid);
        c.me();
        return c;
    }

    static String open(MockClient c) throws Exception {
        HttpResponse<String> r = keep(c.post("/api/db-sessions/external", "{\"database\":\"sec_db\"}"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return Json.mapper().readTree(r.body()).path("id").asText();
    }

    @Test
    void oversizedBodiesAreRefusedOnEveryRouteThatReadsOne() throws Exception {
        MockClient c = signIn("1");
        String id = open(c);
        String huge = "{\"sql\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}"; // over every route's limit (execute: about 6 MiB)
        // Every POST route, also those that never read a body (finding P11-1).
        for (String path : List.of("/api/db-sessions/external", "/api/profiles/external/databases", "/api/db-sessions/" + id + "/execute",
                "/api/db-sessions/" + id + "/transaction", "/api/db-sessions/" + id + "/heartbeat", "/api/db-sessions/" + id + "/cancel",
                "/api/logout")) {
            HttpResponse<String> r = keep(c.post(path, huge));
            assertThat(r.statusCode()).as(path).isEqualTo(413);
            assertThat(r.body()).as(path).contains("TOO_LARGE").doesNotContain("xxxxxxxx");
        }
        // Wrong content type is refused before the body is parsed.
        HttpResponse<String> form = keep(c.send("POST", "/api/db-sessions/" + id + "/execute", "sql=SELECT+1",
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Origin", c.origin(), "X-CSRF-Token", c.csrf())));
        assertThat(form.statusCode()).isEqualTo(415);
        // The session still works.
        assertThat(keep(c.post("/api/db-sessions/" + id + "/execute", "{\"sql\":\"SELECT 1\"}")).statusCode()).isEqualTo(200);
    }

    @Test
    void markupInErrorsComesBackAsJsonText() throws Exception {
        MockClient c = signIn("2");
        String id = open(c);
        String payload = "<img src=x onerror=alert(1)><script>alert(2)</script>";
        HttpResponse<String> r = keep(c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", "SELECT * FROM \"" + payload + "\""))));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        JsonNode err = Json.mapper().readTree(r.body()).path("error");
        assertThat(err.path("message").asText()).contains(payload); // as data, for the UI to show as text
        HttpResponse<String> path = keep(c.get("/api/%3Cscript%3Ealert(1)%3C%2Fscript%3E"));
        assertThat(path.statusCode()).isIn(400, 404); // Tomcat refuses an encoded slash (400); otherwise not found
        assertThat(path.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(path.body()).doesNotContain("<script>").doesNotContain("%3Cscript");
        HttpResponse<String> meta = keep(c.get("/api/metadata/" + id + "/objects?schema=" + java.net.URLEncoder.encode(payload, StandardCharsets.UTF_8)));
        assertThat(meta.statusCode()).isEqualTo(200);
        assertThat(meta.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    }

    @Test
    void noSecretAppearsInLogsAuditRowsSpoolOrResponses() throws Exception {
        String sqlSecret = "SqlLiteral-" + UUID.randomUUID();
        try (LogCapture logs = new LogCapture()) {
            MockClient c = signIn("3");
            String id = open(c);
            for (String sql : List.of("SELECT '" + sqlSecret + "' AS s", "ALTER ROLE no_such_role PASSWORD '" + sqlSecret + "'", "SELECT 1/0",
                    "SELEC '" + sqlSecret + "'", "SELECT '" + sqlSecret + "'; SELECT 2")) {
                keep(c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql, "confirmed", true))));
            }
            keep(c.get("/api/metadata/" + id + "/schemas"));
            keep(c.post("/api/db-sessions/" + id + "/heartbeat", "{}"));
            // A failed connection (wrong password) and an audit outage with a spooled outcome.
            connector.password("sam", "wrong-" + rolePassword);
            keep(c.post("/api/db-sessions/external", "{\"database\":\"sec_db\"}"));
            connector.password("sam", rolePassword);
            apps.auditDown(true);
            try {
                keep(c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", "SELECT '" + sqlSecret + "'"))));
                keep(c.post("/api/db-sessions/" + id + "/heartbeat", "{}"));
            } finally {
                apps.auditDown(false);
            }
            keep(c.post("/api/logout", "{}"));

            // Credentials and keys must appear nowhere. The SQL literal comes back in its own query's result (the
            // user asked for it), so it is checked in everything except responses: logs, audit rows and the spool.
            List<String> credentials = List.of(apps.pg().appPassword(), apps.pg().migratorPassword(), apps.pg().superPassword(), rolePassword,
                    "0123456789abcdef0123456789abcdef-test-key");
            StringBuilder everything = new StringBuilder(logs.text());
            try (Connection db = apps.pg().superuser("pgweb_audit"); Statement st = db.createStatement();
                 ResultSet rs = st.executeQuery("SELECT e::text FROM audit.event e")) {
                while (rs.next()) {
                    everything.append(rs.getString(1)).append('\n');
                }
            }
            try (Stream<Path> files = Files.walk(tmp)) {
                for (Path f : files.filter(Files::isRegularFile).filter(f -> f.toString().contains("spool") || f.toString().endsWith(".log")).toList()) {
                    everything.append(Files.readString(f, StandardCharsets.ISO_8859_1));
                }
            }
            String stored = everything.toString();
            assertThat(stored).as("something was captured").contains("exec_intent").contains("audit_failure");
            assertThat(stored).as("the SQL literal in logs, audit rows or spool").doesNotContain(sqlSecret);
            StringBuilder responses = new StringBuilder();
            synchronized (RESPONSES) {
                RESPONSES.forEach(responses::append);
            }
            String all = stored + responses;
            for (String s : credentials) {
                assertThat(all).as("credential " + s.substring(0, Math.min(6, s.length())) + "...").doesNotContain(s);
            }
        }
    }
}
