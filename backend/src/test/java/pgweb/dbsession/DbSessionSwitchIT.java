package pgweb.dbsession;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.PasswordTestConnector;
import pgweb.testsupport.TestApps;
import pgweb.testsupport.TestConfig;
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Switching a session to another database (FN-03, admin Phase 1) on a real PostgreSQL 18 cluster: the new session
 * opens through the normal path first, the old one closes only after that succeeded, an open transaction is never
 * discarded without confirmation, and the caps, ownership checks and audit records still apply.
 */
class DbSessionSwitchIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static Application app;

    static final String ALICE_SID = "S-1-5-21-3-3-3-1001";

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("alice", "bob")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE alice_db OWNER alice");
            s.execute("REVOKE CONNECT ON DATABASE alice_db FROM PUBLIC");
            s.execute("CREATE DATABASE alice_two OWNER alice");
            s.execute("REVOKE CONNECT ON DATABASE alice_two FROM PUBLIC");
            s.execute("CREATE DATABASE bob_db OWNER bob");
            s.execute("REVOKE CONNECT ON DATABASE bob_db FROM PUBLIC");
        }
        try (Connection c = apps.pg().superuser("alice_db"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE public.notes (id int PRIMARY KEY)");
            s.execute("ALTER TABLE public.notes OWNER TO alice");
        }
        app = apps.app(p -> {
            p.setProperty("execution.enabled", "true");
            p.setProperty("limits.sessionsPerUser", "3");
        }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    @AfterEach
    void closeAll() throws Exception {
        apps.auditDown(false);
        for (MockClient c : List.of(alice(), bob())) {
            for (JsonNode s : json(c.get("/api/db-sessions")).path("sessions")) {
                c.send("DELETE", "/api/db-sessions/" + s.path("id").asText(), null, Map.of("Origin", c.origin(), "X-CSRF-Token", c.csrf()));
            }
        }
        CLIENTS.clear();
    }

    /** One client per user for the whole test (sessions belong to an app session). */
    static final Map<String, MockClient> CLIENTS = new HashMap<>();

    static synchronized MockClient alice() throws Exception {
        MockClient c = CLIENTS.get("alice");
        if (c == null) {
            c = MockClient.user(app.port(), "TEST\\alice", ALICE_SID, TestConfig.USERS_SID);
            c.me();
            CLIENTS.put("alice", c);
        }
        return c;
    }

    static synchronized MockClient bob() throws Exception {
        MockClient c = CLIENTS.get("bob");
        if (c == null) {
            c = MockClient.user(app.port(), "TEST\\bob", "S-1-5-21-3-3-3-1002");
            c.me();
            CLIENTS.put("bob", c);
        }
        return c;
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.mapper().readTree(r.body());
    }

    static String open(MockClient c, String profile, String database) throws Exception {
        HttpResponse<String> r = c.post("/api/db-sessions/" + profile, Json.write(Map.of("database", database)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r).path("id").asText();
    }

    static HttpResponse<String> switchTo(MockClient c, String profile, String from, String database, Boolean discard) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("database", database, "replaces", from));
        if (discard != null) {
            body.put("discardTransaction", discard);
        }
        return c.post("/api/db-sessions/" + profile, Json.write(body));
    }

    static HttpResponse<String> run(MockClient c, String id, String sql) throws Exception {
        return c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql, "confirmed", true)));
    }

    static int rows(String database, String sql) throws Exception {
        try (Connection c = apps.pg().superuser(database); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------

    @Test
    void switchingOpensTheNewSessionAndThenClosesTheOldOne() throws Exception {
        MockClient a = alice();
        String first = open(a, "external", "alice_db");
        HttpResponse<String> r = switchTo(a, "external", first, "alice_two", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode s = json(r);
        assertThat(s.path("database").asText()).isEqualTo("alice_two");
        assertThat(s.path("role").asText()).isEqualTo("alice");
        String second = s.path("id").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(a.get("/api/db-sessions/" + first).statusCode()).isEqualTo(404);
        assertThat(json(a.get("/api/db-sessions")).path("sessions")).hasSize(1);
        assertThat(json(run(a, second, "SELECT current_database()")).toString()).contains("alice_two");

        Map<String, Object> closed = apps.events("db_disconnect", ALICE_SID).get(0);
        assertThat(closed.get("db_session_id")).isEqualTo(first);
        assertThat(closed.get("details").toString()).contains("\"reason\": \"switched\"");
        Map<String, Object> opened = apps.events("db_connect_result", ALICE_SID).get(0);
        assertThat(opened.get("db_session_id")).isEqualTo(second);
        assertThat(opened.get("outcome")).isEqualTo("success");
        assertThat(opened.get("details").toString()).contains("\"replaces\": \"" + first + "\"");
    }

    @Test
    void aFailedSwitchKeepsTheCurrentSession() throws Exception {
        MockClient a = alice();
        String id = open(a, "external", "alice_db");
        HttpResponse<String> r = switchTo(a, "external", id, "bob_db", null); // no CONNECT privilege
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("CONNECT_FAILED");
        JsonNode still = json(a.get("/api/db-sessions/" + id));
        assertThat(still.path("state").asText()).isEqualTo("OPEN");
        assertThat(still.path("database").asText()).isEqualTo("alice_db");
        assertThat(run(a, id, "SELECT 1").statusCode()).isEqualTo(200);
        assertThat(apps.events("db_connect_result", ALICE_SID).get(0).get("outcome")).isEqualTo("failure");

        assertThat(switchTo(a, "external", id, "", null).statusCode()).isEqualTo(400); // invalid name: nothing changes
        assertThat(json(a.get("/api/db-sessions")).path("sessions")).hasSize(1);
    }

    @Test
    void anOpenTransactionIsNeverDiscardedWithoutConfirmation() throws Exception {
        MockClient a = alice();
        String id = open(a, "external", "alice_db");
        assertThat(run(a, id, "BEGIN").statusCode()).isEqualTo(200);
        assertThat(run(a, id, "INSERT INTO public.notes VALUES (1)").statusCode()).isEqualTo(200);

        HttpResponse<String> refused = switchTo(a, "external", id, "alice_two", null);
        assertThat(refused.statusCode()).isEqualTo(409);
        JsonNode err = json(refused).path("error");
        assertThat(err.path("code").asText()).isEqualTo("TRANSACTION_OPEN");
        assertThat(err.path("transaction").asText()).isEqualTo("OPEN");
        assertThat(switchTo(a, "external", id, "alice_two", false).statusCode()).isEqualTo(409);
        JsonNode still = json(a.get("/api/db-sessions/" + id));
        assertThat(still.path("transaction").asText()).isEqualTo("OPEN"); // untouched: the transaction goes on
        assertThat(run(a, id, "INSERT INTO public.notes VALUES (2)").statusCode()).isEqualTo(200);

        HttpResponse<String> confirmed = switchTo(a, "external", id, "alice_two", true);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(201);
        // Closing the old connection made PostgreSQL roll the transaction back: nothing was committed.
        await().atMost(Duration.ofSeconds(10)).until(() -> rows("alice_db", "SELECT count(*) FROM pg_stat_activity WHERE usename = 'alice' AND datname = 'alice_db'") == 0);
        assertThat(rows("alice_db", "SELECT count(*) FROM public.notes")).isZero();
    }

    @Test
    void aFailedTransactionAlsoNeedsConfirmation() throws Exception {
        MockClient a = alice();
        String id = open(a, "external", "alice_db");
        run(a, id, "BEGIN");
        run(a, id, "SELECT 1/0");
        HttpResponse<String> refused = switchTo(a, "external", id, "alice_two", null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json(refused).path("error").path("transaction").asText()).isEqualTo("FAILED");
        assertThat(switchTo(a, "external", id, "alice_two", true).statusCode()).isEqualTo(201);
    }

    @Test
    void aSessionRunningAStatementIsNotSwitched() throws Exception {
        MockClient a = alice();
        String id = open(a, "external", "alice_db");
        CompletableFuture<HttpResponse<String>> sleeping = CompletableFuture.supplyAsync(() -> {
            try {
                return run(a, id, "SELECT pg_sleep(3)");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> json(a.get("/api/db-sessions/" + id)).path("busy").asBoolean());
        HttpResponse<String> r = switchTo(a, "external", id, "alice_two", true);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("SESSION_BUSY");
        assertThat(sleeping.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(json(a.get("/api/db-sessions/" + id)).path("state").asText()).isEqualTo("OPEN");
    }

    @Test
    void someoneElsesSessionCannotBeSwitchedOrClosedThisWay() throws Exception {
        String alices = open(alice(), "external", "alice_db");
        HttpResponse<String> r = switchTo(bob(), "external", alices, "bob_db", null);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(bob().get("/api/db-sessions")).path("sessions")).isEmpty(); // nothing opened for bob either
        assertThat(json(alice().get("/api/db-sessions/" + alices)).path("state").asText()).isEqualTo("OPEN");
        assertThat(switchTo(alice(), "external", "NoSuchSessionIdAAAAAAA", "alice_two", null).statusCode()).isEqualTo(404);
    }

    @Test
    void onlyWithinTheSameServerAndOnlyWithDataAdminForInternal() throws Exception {
        MockClient a = alice();
        String external = open(a, "external", "alice_db");
        HttpResponse<String> cross = switchTo(a, "internal", external, "alice_two", null);
        assertThat(cross.statusCode()).isEqualTo(400);
        assertThat(cross.body()).contains("INVALID_REPLACE");
        assertThat(json(a.get("/api/db-sessions/" + external)).path("state").asText()).isEqualTo("OPEN");

        String bobs = open(bob(), "external", "bob_db");
        HttpResponse<String> denied = switchTo(bob(), "internal", bobs, "bob_db", null); // not DataAdmin: refused by route access
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(bob().get("/api/db-sessions/" + bobs)).path("state").asText()).isEqualTo("OPEN");

        String internal = open(a, "internal", "alice_db");
        HttpResponse<String> ok = switchTo(a, "internal", internal, "alice_two", null);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(201);
        assertThat(json(ok).path("profile").asText()).isEqualTo("internal");
    }

    @Test
    void theSessionCapStillAppliesAndTheCurrentSessionSurvives() throws Exception {
        MockClient a = alice();
        String first = open(a, "external", "alice_db");
        open(a, "external", "alice_db");
        open(a, "external", "alice_two");
        HttpResponse<String> r = switchTo(a, "external", first, "alice_two", null);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("SESSION_LIMIT");
        assertThat(json(a.get("/api/db-sessions/" + first)).path("state").asText()).isEqualTo("OPEN");
        assertThat(json(a.get("/api/db-sessions")).path("sessions")).hasSize(3);
    }

    @Test
    void unknownFieldsAreStillRejected() throws Exception {
        MockClient a = alice();
        String id = open(a, "external", "alice_db");
        HttpResponse<String> r = a.post("/api/db-sessions/external", Json.write(Map.of("database", "alice_two", "replaces", id, "host", "elsewhere")));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(a.get("/api/db-sessions")).path("sessions")).hasSize(1);
    }

    @Test
    void duringAnAuditOutageASwitchBehavesLikeAnOpenAndIsRecordedAfterwards() throws Exception {
        MockClient a = alice();
        String first = open(a, "external", "alice_db");
        apps.auditDown(true);
        HttpResponse<String> r = switchTo(a, "external", first, "alice_two", null);
        // The approved policy: connecting is not fail-closed; its records are spooled and replayed (audit-policy.md).
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String second = json(r).path("id").asText();
        assertThat(app.audit().spooled()).isPositive();
        apps.auditDown(false);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            app.audit().replay();
            return apps.events("db_disconnect", ALICE_SID).stream().anyMatch(e -> first.equals(e.get("db_session_id")))
                    && apps.events("db_connect_result", ALICE_SID).stream().anyMatch(e -> second.equals(e.get("db_session_id")));
        });
        assertThat(app.audit().spooled()).isZero();
    }
}
