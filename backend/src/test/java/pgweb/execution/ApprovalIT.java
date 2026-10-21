package pgweb.execution;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-person approval (admin Phase 14) on a real PostgreSQL 18 cluster: a profile's listed reasons need a second
 * person from the approver group; approvals are bound to the requester, session and exact text, used once; the
 * requester can't approve their own request; everything is audited without the text.
 */
class ApprovalIT {

    static final String APPROVERS = "S-1-5-21-6-6-6-900";

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static Application app;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("ada", "bob", "cyd")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE appr_db OWNER ada");
        }
        try (Connection c = apps.pg().superuser("appr_db"); Statement s = c.createStatement()) {
            s.execute("SET ROLE ada");
            s.execute("CREATE TABLE t (x int)");
            s.execute("INSERT INTO t SELECT generate_series(1, 10)");
            s.execute("CREATE TABLE t2 (x int)");
        }
        app = apps.app(p -> {
            p.setProperty("execution.enabled", "true");
            p.setProperty("auth.approverSid", APPROVERS);
            p.setProperty("profile.external.approvalReasons", "drop, truncate");
        }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static MockClient client(String role, String rid, String... groups) throws Exception {
        MockClient c = MockClient.user(app.port(), "TEST\\" + role, "S-1-5-21-6-6-6-" + rid, groups);
        c.me();
        return c;
    }

    static String open(MockClient c) throws Exception {
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"appr_db\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return Json.mapper().readTree(r.body()).path("id").asText();
    }

    static HttpResponse<String> run(MockClient c, String id, String sql) throws Exception {
        return c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql, "confirmed", true)));
    }

    static JsonNode json(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return Json.mapper().readTree(r.body());
    }

    static JsonNode request(MockClient c, String id, String sql, boolean script) throws Exception {
        return json(c.post("/api/db-sessions/" + id + "/approval", Json.write(Map.of("sql", sql, "script", script))), 201);
    }

    static HttpResponse<String> decide(MockClient c, String approval, boolean approve) throws Exception {
        return c.post("/api/approvals/" + approval + "/decision", Json.write(Map.of("approve", approve, "comment", "checked")));
    }

    static long rows(String table) throws Exception {
        try (Connection c = apps.pg().superuser("appr_db"); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void aSecondPersonApprovesExactlyThisTextForThisSessionOnce() throws Exception {
        MockClient ada = client("ada", "1", APPROVERS); // an approver too, but never for herself
        MockClient bob = client("bob", "2", APPROVERS);
        MockClient cyd = client("cyd", "3");
        assertThat(ada.me().path("capabilities").path("approver").asBoolean()).isTrue();
        assertThat(cyd.me().path("capabilities").path("approver").asBoolean()).isFalse();
        String id = open(ada);

        // Without approval: refused with the reason, nothing runs. Statements without a listed reason run as usual.
        JsonNode refused = json(run(ada, id, "TRUNCATE t"), 409);
        assertThat(refused.path("error").path("code").asText()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(refused.path("error").path("reasons").toString()).isEqualTo("[\"truncate\"]");
        assertThat(rows("t")).isEqualTo(10);
        assertThat(run(ada, id, "SELECT count(*) FROM t").statusCode()).isEqualTo(200);
        assertThat(ada.post("/api/db-sessions/" + id + "/approval", Json.write(Map.of("sql", "SELECT 1"))).body()).contains("APPROVAL_NOT_NEEDED");
        assertThat(ada.post("/api/db-sessions/" + id + "/approval", Json.write(Map.of("sql", "SELEC 1"))).statusCode()).isEqualTo(400);

        JsonNode req = request(ada, id, "TRUNCATE t", false);
        String approval = req.path("id").asText();
        assertThat(req.path("state").asText()).isEqualTo("pending");

        // Not by the requester, not by someone outside the group; the group sees the text it is asked to approve.
        assertThat(decide(ada, approval, true).body()).contains("OWN_REQUEST");
        assertThat(decide(cyd, approval, true).statusCode()).isEqualTo(404);
        assertThat(json(cyd.get("/api/approvals"), 200).path("toDecide").size()).isZero();
        JsonNode list = json(bob.get("/api/approvals"), 200);
        assertThat(list.path("approver").asBoolean()).isTrue();
        assertThat(list.path("toDecide").get(0).path("text").asText()).isEqualTo("TRUNCATE t");
        assertThat(list.path("toDecide").get(0).path("requester").asText()).isEqualTo("TEST\\ada");
        JsonNode granted = json(decide(bob, approval, true), 200);
        assertThat(granted.path("state").asText()).isEqualTo("approved");
        assertThat(granted.path("decidedBy").asText()).isEqualTo("TEST\\bob");
        assertThat(decide(bob, approval, false).body()).contains("NOT_PENDING");

        // Other text, another session, or a script of the same text: still not approved.
        assertThat(json(run(ada, id, "TRUNCATE  t"), 409).path("error").path("code").asText()).isEqualTo("APPROVAL_REQUIRED");
        String other = open(ada);
        assertThat(run(ada, other, "TRUNCATE t").statusCode()).isEqualTo(409);
        assertThat(ada.post("/api/db-sessions/" + id + "/script", Json.write(Map.of("sql", "TRUNCATE t", "confirmed", true))).statusCode()).isEqualTo(409);
        // Read differently (backslashes in strings change meaning with standard_conforming_strings): not approved.
        assertThat(run(ada, id, "SET standard_conforming_strings = off").statusCode()).isEqualTo(200);
        assertThat(json(run(ada, id, "TRUNCATE t"), 409).path("error").path("code").asText()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(run(ada, id, "SET standard_conforming_strings = on").statusCode()).isEqualTo(200);
        // The approved text runs once.
        assertThat(run(ada, id, "TRUNCATE t").statusCode()).isEqualTo(200);
        assertThat(rows("t")).isZero();
        assertThat(run(ada, id, "TRUNCATE t").statusCode()).isEqualTo(409);
        assertThat(json(ada.get("/api/approvals"), 200).path("mine").toString()).contains("\"state\":\"used\"");

        // Audited, without the text.
        assertThat(apps.events("approval_requested", "S-1-5-21-6-6-6-1")).filteredOn(e -> String.valueOf(e.get("details")).contains(approval)).hasSize(1);
        List<Map<String, Object>> grants = apps.events("approval_granted", "S-1-5-21-6-6-6-2");
        assertThat(grants).filteredOn(e -> String.valueOf(e.get("details")).contains(approval)).hasSize(1);
        assertThat(grants.toString()).doesNotContain("TRUNCATE");
        assertThat(apps.events("exec_intent", "S-1-5-21-6-6-6-1")).anyMatch(e -> String.valueOf(e.get("details")).contains(approval));
    }

    @Test
    void finishedRequestsDoNotFillTheStore() throws Exception {
        MockClient cyd = client("cyd", "3");
        String id = open(cyd);
        HttpResponse<String> last = null;
        for (int i = 0; i <= Approvals.MAX_REQUESTS; i++) {
            last = cyd.post("/api/db-sessions/" + id + "/approval", Json.write(Map.of("sql", "DROP TABLE IF EXISTS gone" + i)));
            assertThat(last.statusCode()).as("request %d: %s", i, last.body()).isEqualTo(201);
            String rid = Json.mapper().readTree(last.body()).path("id").asText();
            json(cyd.send("DELETE", "/api/approvals/" + rid, null,
                    Map.of("Origin", cyd.origin(), "X-CSRF-Token", cyd.csrf(), "Content-Type", "application/json")), 200);
        }
        assertThat(json(cyd.get("/api/approvals"), 200).path("mine").size()).isLessThanOrEqualTo(Approvals.MAX_REQUESTS);
    }

    @Test
    void rejectedWithdrawnAndScriptedRequests() throws Exception {
        MockClient ada = client("ada", "1", APPROVERS);
        MockClient bob = client("bob", "2", APPROVERS);
        String id = open(ada);
        String rejected = request(ada, id, "DROP TABLE t2", false).path("id").asText();
        assertThat(json(decide(bob, rejected, false), 200).path("state").asText()).isEqualTo("rejected");
        assertThat(run(ada, id, "DROP TABLE t2").statusCode()).isEqualTo(409);
        assertThat(rows("t2")).isZero(); // still there

        String withdrawn = request(ada, id, "DROP TABLE t2", false).path("id").asText();
        HttpResponse<String> w = ada.send("DELETE", "/api/approvals/" + withdrawn, null,
                Map.of("Origin", ada.origin(), "X-CSRF-Token", ada.csrf(), "Content-Type", "application/json"));
        assertThat(json(w, 200).path("state").asText()).isEqualTo("withdrawn");
        assertThat(decide(bob, withdrawn, true).body()).contains("NOT_PENDING");
        assertThat(bob.send("DELETE", "/api/approvals/" + withdrawn, null,
                Map.of("Origin", bob.origin(), "X-CSRF-Token", bob.csrf(), "Content-Type", "application/json")).statusCode()).isEqualTo(404);

        // A script is approved as a whole.
        String script = "INSERT INTO t2 VALUES (1);\nDROP TABLE t2;";
        assertThat(ada.post("/api/db-sessions/" + id + "/script", Json.write(Map.of("sql", script, "confirmed", true))).statusCode()).isEqualTo(409);
        String approval = request(ada, id, script, true).path("id").asText();
        json(decide(bob, approval, true), 200);
        HttpResponse<String> ran = ada.post("/api/db-sessions/" + id + "/script", Json.write(Map.of("sql", script, "confirmed", true)));
        assertThat(ran.statusCode()).as(ran.body()).isEqualTo(200);
        try (Connection c = apps.pg().superuser("appr_db"); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT to_regclass('public.t2') IS NULL")) {
            rs.next();
            assertThat(rs.getBoolean(1)).as("the approved script dropped t2").isTrue();
        }

        // At most five open requests per account.
        for (int i = 0; i < Approvals.MAX_PENDING_PER_USER; i++) {
            request(ada, id, "DROP TABLE IF EXISTS nope" + i, false);
        }
        assertThat(ada.post("/api/db-sessions/" + id + "/approval", Json.write(Map.of("sql", "DROP TABLE IF EXISTS nope9"))).body()).contains("TOO_MANY_REQUESTS");
        for (JsonNode r : json(ada.get("/api/approvals"), 200).path("mine")) {
            if ("pending".equals(r.path("state").asText())) {
                json(ada.send("DELETE", "/api/approvals/" + r.path("id").asText(), null,
                        Map.of("Origin", ada.origin(), "X-CSRF-Token", ada.csrf(), "Content-Type", "application/json")), 200);
            }
        }
        assertThat(apps.events("approval_rejected", "S-1-5-21-6-6-6-2")).hasSize(1);
        assertThat(apps.events("approval_withdrawn", "S-1-5-21-6-6-6-1")).hasSize(1 + Approvals.MAX_PENDING_PER_USER);
        assertThat(bob.post("/api/approvals/" + approval + "/decision", "{}").body()).contains("INVALID_DECISION");
        assertThat(bob.get("/api/approvals/x").statusCode()).isEqualTo(404);
    }
}
