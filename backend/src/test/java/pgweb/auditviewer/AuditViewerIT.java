package pgweb.auditviewer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.PasswordTestConnector;
import pgweb.testsupport.TestApps;
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit viewer (admin Phase 9) on a real PostgreSQL 18 cluster: it reads the audit trail only as the signed-in
 * user, so only members of pgweb_audit_reader see anything; every search is recorded first; filters are values,
 * never SQL; paging; validation; and it is off unless configured.
 */
class AuditViewerIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static Application app;
    static Application off;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("aud", "ada")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("GRANT pgweb_audit_reader TO aud");
            s.execute("GRANT CONNECT ON DATABASE pgweb_audit TO ada");
        }
        app = apps.app(p -> {
            p.setProperty("auditViewer.enabled", "true");
            p.setProperty("auditViewer.host", "localhost");
            p.setProperty("auditViewer.port", String.valueOf(apps.pg().port()));
        }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
        off = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static Map<String, Object> filters(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", Instant.now().minus(Duration.ofHours(1)).toString());
        m.put("to", Instant.now().plus(Duration.ofMinutes(5)).toString());
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static HttpResponse<String> search(MockClient c, Map<String, Object> f) throws Exception {
        return c.post("/api/audit/search", Json.write(f));
    }

    static JsonNode ok(MockClient c, Map<String, Object> f) throws Exception {
        HttpResponse<String> r = search(c, f);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.mapper().readTree(r.body());
    }

    static List<String> types(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.path("events").forEach(e -> out.add(e.path("type").asText()));
        return out;
    }

    @Test
    void anAuditorReadsTheTrailNewestFirstAndEachSearchIsRecorded() throws Exception {
        MockClient aud = MockClient.user(app.port(), "TEST\\aud", "S-1-5-21-9-9-9-71");
        JsonNode me = aud.me();
        assertThat(me.path("capabilities").path("auditViewer").asBoolean()).isTrue();
        JsonNode first = ok(aud, filters("user", "aud"));
        // The search itself was recorded before it ran, so it is in its own result.
        assertThat(types(first)).contains("audit_view");
        JsonNode view = first.path("events").get(0);
        assertThat(view.path("type").asText()).isEqualTo("audit_view");
        assertThat(view.path("windowsUser").asText()).isEqualTo("TEST\\aud");
        assertThat(view.path("details").asText()).contains("\"user\": \"aud\"");
        // Newest first; paging continues below the last sequence number.
        List<Long> seqs = new ArrayList<>();
        first.path("events").forEach(e -> seqs.add(e.path("seq").asLong()));
        assertThat(seqs).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        JsonNode one = ok(aud, filters("limit", 1));
        assertThat(one.path("events").size()).isEqualTo(1);
        assertThat(one.path("more").asBoolean()).isTrue();
        long top = one.path("events").get(0).path("seq").asLong();
        JsonNode older = ok(aud, filters("limit", 5, "before", top));
        older.path("events").forEach(e -> assertThat(e.path("seq").asLong()).isLessThan(top));
        // Filters are values: a type filter, an outcome, and text that would be SQL or a LIKE pattern elsewhere.
        assertThat(types(ok(aud, filters("type", "audit_view")))).containsOnly("audit_view");
        assertThat(ok(aud, filters("user", "%' OR 1=1 --")).path("events").size()).isZero();
        assertThat(ok(aud, filters("user", "_ud")).path("events").size()).isZero(); // '_' is not a wildcard
        assertThat(ok(aud, filters("database", "nope")).path("events").size()).isZero();
        assertThat(apps.events("audit_view", "S-1-5-21-9-9-9-71")).hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    void aRoleThatIsNotAnAuditorSeesNothingButTheAttemptIsRecorded() throws Exception {
        MockClient ada = MockClient.user(app.port(), "TEST\\ada", "S-1-5-21-9-9-9-72");
        ada.me();
        HttpResponse<String> r = search(ada, filters());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(r.body()).contains("AUDIT_READ_DENIED").doesNotContain("audit_view\"");
        assertThat(apps.events("audit_view", "S-1-5-21-9-9-9-72")).hasSize(1);
        // A role without a login on the audit database can't connect at all.
        MockClient eve = MockClient.user(app.port(), "TEST\\eve", "S-1-5-21-9-9-9-73");
        eve.me();
        HttpResponse<String> none = search(eve, filters());
        assertThat(none.statusCode()).isEqualTo(422);
        assertThat(none.body()).contains("CONNECT_FAILED");
    }

    @Test
    void searchesAreValidatedAndBounded() throws Exception {
        MockClient aud = MockClient.user(app.port(), "TEST\\aud", "S-1-5-21-9-9-9-71");
        aud.me();
        Map<String, Object> noRange = new LinkedHashMap<>();
        assertThat(search(aud, noRange).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("from", "yesterday")).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("from", Instant.now().minus(Duration.ofDays(40)).toString())).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("to", Instant.now().minus(Duration.ofDays(1)).toString())).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("type", "x'; DROP TABLE audit.event; --")).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("outcome", "Not An Outcome")).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("limit", 201)).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("before", 0)).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("user", "x".repeat(257))).statusCode()).isEqualTo(400);
        assertThat(search(aud, filters("user", "a\u0000b")).statusCode()).isEqualTo(400);
        try (Connection c = apps.pg().superuser("pgweb_audit"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('audit.event') IS NOT NULL")) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
        }
    }

    @Test
    void itIsOffUnlessConfigured() throws Exception {
        MockClient aud = MockClient.user(off.port(), "TEST\\aud", "S-1-5-21-9-9-9-71");
        JsonNode me = aud.me();
        assertThat(me.path("capabilities").path("auditViewer").asBoolean()).isFalse();
        assertThat(search(aud, filters()).statusCode()).isEqualTo(404);
    }

    @Test
    void likePatternsAreEscapedAndErrorsExplained() {
        assertThat(AuditViewer.likeEscape("a%b_c!d")).isEqualTo("a!%b!_c!!d");
        assertThat(AuditViewer.readError("42501").code()).isEqualTo("AUDIT_READ_DENIED");
        assertThat(AuditViewer.readError("57014").code()).isEqualTo("AUDIT_SEARCH_TIMEOUT");
        assertThat(AuditViewer.readError("XX000")).isNull();
        assertThat(AuditViewer.readError(null)).isNull();
    }

    @Test
    void statementEventsShowTheirOutcomeRowsAndDuration() throws Exception {
        app.audit().require(pgweb.audit.AuditEvent.of("exec_outcome").user("TEST\\zed", "S-1-5-21-9-9-9-79", false)
                .target("external", "adm_db", "zed", 42, "s1").statement("select", "SELECT $1", "0".repeat(64), 9, "ok")
                .outcome("success", null, 5L, false, 12L));
        MockClient aud = MockClient.user(app.port(), "TEST\\aud", "S-1-5-21-9-9-9-71");
        aud.me();
        JsonNode e =ok(aud, filters("user", "S-1-5-21-9-9-9-79", "type", "exec_outcome", "outcome", "success", "database", "adm_db")).path("events").get(0);
        assertThat(e.path("rowCount").asLong()).isEqualTo(5);
        assertThat(e.path("durationMs").asLong()).isEqualTo(12);
        assertThat(e.path("sqlNormalised").asText()).isEqualTo("SELECT $1");
        assertThat(e.path("database").asText()).isEqualTo("adm_db");
        assertThat(e.path("profile").asText()).isEqualTo("external");
    }
}
