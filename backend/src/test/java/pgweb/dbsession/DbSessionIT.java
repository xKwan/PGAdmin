package pgweb.dbsession;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.MutableClock;
import pgweb.testsupport.PasswordTestConnector;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestApps;
import pgweb.testsupport.TestConfig;
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DB sessions on a real PostgreSQL 18 cluster (plan Phase 6), with mock Windows users and the test password
 * connector standing in for Kerberos delegation. The delegation path itself is covered by DelegatedSessionIT.
 */
class DbSessionIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static MutableClock clock;
    static Application app;
    static String runId;

    static final String ADMIN_GROUP = TestConfig.USERS_SID;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        clock = new MutableClock(Instant.now());
        runId = Long.toString(Math.abs(Seeds.random("db-session-run").nextLong()) % 100000);
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("alice", "bob", "carol", "superman", "eve", "dave")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN " + (role.equals("superman") ? "SUPERUSER" : "NOSUPERUSER") + " PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE alice_db OWNER alice");
            s.execute("REVOKE CONNECT ON DATABASE alice_db FROM PUBLIC");
            s.execute("CREATE DATABASE bob_db OWNER bob");
            s.execute("REVOKE CONNECT ON DATABASE bob_db FROM PUBLIC");
            s.execute("CREATE DATABASE shared_db");
            s.execute("REVOKE CONNECT ON DATABASE shared_db FROM PUBLIC");
            s.execute("GRANT CONNECT ON DATABASE shared_db TO alice, bob");
            s.execute("CREATE DATABASE public_db");
        }
        app = apps.app(p -> {
            p.setProperty("limits.sessionsPerUser", "3");
            p.setProperty("limits.failedOpensPerWindow", "3");
        }, new Application.Options(new MockAuthFilter(), r -> { }, clock, connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static MockClient alice() throws Exception {
        MockClient c = MockClient.user(app.port(), "TEST\\Alice", "S-1-5-21-2-2-2-1001", ADMIN_GROUP);
        c.me();
        return c;
    }

    static MockClient bob() throws Exception {
        MockClient c = MockClient.user(app.port(), "TEST\\bob", "S-1-5-21-2-2-2-1002");
        c.me();
        return c;
    }

    static MockClient user(String name, String rid, String... groups) throws Exception {
        MockClient c = MockClient.user(app.port(), "TEST\\" + name, "S-1-5-21-2-2-2-" + rid, groups);
        c.me();
        return c;
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.mapper().readTree(r.body());
    }

    static HttpResponse<String> open(MockClient c, String profile, String database) throws Exception {
        return c.post("/api/db-sessions/" + profile, Json.write(Map.of("database", database)));
    }

    static List<String> backendsOf(String role) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = apps.pg().superuser("postgres");
             PreparedStatement ps = c.prepareStatement("SELECT application_name FROM pg_stat_activity WHERE usename = ? AND pid <> pg_backend_pid()")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    static void awaitNoBackends(String role) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() -> backendsOf(role).isEmpty());
    }

    static void closeAll(MockClient c) throws Exception {
        for (JsonNode s : json(c.get("/api/db-sessions")).path("sessions")) {
            c.send("DELETE", "/api/db-sessions/" + s.path("id").asText(), null, Map.of("Origin", c.origin(), "X-CSRF-Token", c.csrf()));
        }
    }

    // ------------------------------------------------------------------

    @Test
    void profilesShowInternalOnlyToDataAdmins() throws Exception {
        assertThat(alice().get("/api/profiles").body()).contains("\"internal\"").contains("\"external\"");
        assertThat(bob().get("/api/profiles").body()).doesNotContain("\"internal\"").contains("\"external\"");
    }

    @Test
    void databaseListsFollowConnectPrivilege() throws Exception {
        JsonNode a = json(alice().post("/api/profiles/external/databases", "{}")).path("databases");
        List<String> names = new ArrayList<>();
        a.forEach(d -> names.add(d.path("name").asText()));
        assertThat(names).contains("alice_db", "shared_db", "public_db", "postgres").doesNotContain("bob_db", "pgweb_audit", "template0", "template1");
        a.forEach(d -> {
            if (d.path("name").asText().equals("alice_db")) {
                assertThat(d.path("owned").asBoolean()).isTrue();
            }
        });
        JsonNode b = json(bob().post("/api/profiles/external/databases", "{}")).path("databases");
        assertThat(b.toString()).contains("bob_db").contains("shared_db").doesNotContain("alice_db");
        assertThat(apps.events("metadata_browse", "S-1-5-21-2-2-2-1001")).isNotEmpty();
    }

    @Test
    void anExternalSessionRunsAsTheMappedRoleWithACompanion() throws Exception {
        MockClient a = alice();
        HttpResponse<String> r = open(a, "external", "alice_db");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode s = json(r);
        assertThat(s.path("role").asText()).isEqualTo("alice"); // lower-cased sAMAccountName
        assertThat(s.path("database").asText()).isEqualTo("alice_db");
        assertThat(s.path("id").asText()).matches("[A-Za-z0-9_-]{22}");
        assertThat(s.path("companion").asBoolean()).isTrue();
        assertThat(s.path("serverVersion").asText()).startsWith("18");
        assertThat(backendsOf("alice")).contains("pgweb", "pgweb-companion");
        assertThat(apps.events("db_connect_result", "S-1-5-21-2-2-2-1001")).anyMatch(e -> "success".equals(e.get("outcome")) && "alice".equals(e.get("pg_session_user")));
        HttpResponse<String> got = a.get("/api/db-sessions/" + s.path("id").asText());
        assertThat(json(got).path("transaction").asText()).isEqualTo("IDLE");
        closeAll(a);
        awaitNoBackends("alice");
    }

    @Test
    void theClientCantChooseHostPortRoleOrCredentials() throws Exception {
        MockClient a = alice();
        for (String field : List.of("host", "port", "user", "role", "password", "sslmode", "url", "username")) {
            HttpResponse<String> r = a.post("/api/db-sessions/external", "{\"database\":\"alice_db\",\"" + field + "\":\"x\"}");
            assertThat(r.statusCode()).as(field).isEqualTo(400);
            assertThat(r.body()).contains("UNKNOWN_FIELD");
        }
        for (String bad : List.of("", " ", "a".repeat(64), "bad\u0001name", "é".repeat(40))) {
            assertThat(open(a, "external", bad).statusCode()).as("[" + bad + "]").isEqualTo(400);
        }
        assertThat(a.post("/api/db-sessions/elsewhere", "{\"database\":\"alice_db\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void noConnectPrivilegeGivesAGenericFailureAndIsAudited() throws Exception {
        HttpResponse<String> r = open(bob(), "external", "alice_db");
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("CONNECT_FAILED").doesNotContain("alice_db").doesNotContain("bob").doesNotContain("localhost")
                .doesNotContain("42501");
        assertThat(apps.events("db_connect_result", "S-1-5-21-2-2-2-1002")).anyMatch(e -> "failure".equals(e.get("outcome")));
    }

    @Test
    void superusersAreRefused() throws Exception {
        MockClient s = user("superman", "1003");
        HttpResponse<String> r = open(s, "external", "public_db");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("SUPERUSER_REFUSED");
        assertThat(s.post("/api/profiles/external/databases", "{}").body()).contains("SUPERUSER_REFUSED");
        awaitNoBackends("superman");
    }

    @Test
    void theInternalServerNeedsDataAdminOnEveryRequest() throws Exception {
        assertThat(open(bob(), "internal", "shared_db").statusCode()).isEqualTo(403);
        assertThat(bob().post("/api/profiles/internal/databases", "{}").statusCode()).isEqualTo(403);
        MockClient a = alice();
        HttpResponse<String> r = open(a, "internal", "shared_db");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = json(r).path("id").asText();
        // Same user and session, but the DataAdmin group is gone from the Windows token: refused from now on.
        HttpResponse<String> revoked = a.send("GET", "/api/db-sessions/" + id, null,
                Map.of(MockAuthFilter.HEADER, MockAuthFilter.header("TEST\\Alice", "S-1-5-21-2-2-2-1001")));
        assertThat(revoked.statusCode()).isEqualTo(403);
        assertThat(a.get("/api/db-sessions/" + id).statusCode()).isEqualTo(200);
        closeAll(a);
    }

    @Test
    void otherUsersAndOtherAppSessionsGet404() throws Exception {
        MockClient a = alice();
        String id = json(open(a, "external", "shared_db")).path("id").asText();
        MockClient b = bob();
        MockClient aliceElsewhere = alice(); // same Windows user, different app session (other browser)
        for (MockClient other : List.of(b, aliceElsewhere)) {
            HttpResponse<String> probe = other.get("/api/db-sessions/" + id);
            assertThat(probe.statusCode()).as((other == b ? "bob" : "alice elsewhere") + ": " + probe.body()).isEqualTo(404);
            assertThat(other.post("/api/db-sessions/" + id + "/heartbeat", "{}").statusCode()).isEqualTo(404);
            assertThat(other.send("DELETE", "/api/db-sessions/" + id, null, Map.of("Origin", other.origin(), "X-CSRF-Token", other.csrf())).statusCode())
                    .isEqualTo(404);
            assertThat(json(other.get("/api/db-sessions")).path("sessions").toString()).doesNotContain(id);
        }
        Random r = Seeds.random("guess");
        for (int i = 0; i < 20; i++) {
            byte[] g = new byte[16];
            r.nextBytes(g);
            String guess = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(g);
            assertThat(b.get("/api/db-sessions/" + guess).statusCode()).isEqualTo(404);
        }
        assertThat(a.get("/api/db-sessions/" + id).statusCode()).isEqualTo(200);
        assertThat(a.get("/api/db-sessions/" + id + "/extra").statusCode()).isEqualTo(404);
        closeAll(a);
    }

    @Test
    void perUserCapsAndFailedOpenLimitsApply() throws Exception {
        MockClient c = user("carol", "1004");
        for (int i = 0; i < 3; i++) {
            assertThat(open(c, "external", "public_db").statusCode()).isEqualTo(201);
        }
        HttpResponse<String> over = open(c, "external", "public_db");
        assertThat(over.statusCode()).isEqualTo(409);
        assertThat(over.body()).contains("SESSION_LIMIT");
        closeAll(c);
        awaitNoBackends("carol");

        MockClient m = user("mallory", "1005"); // no such PostgreSQL role
        for (int i = 0; i < 3; i++) {
            assertThat(open(m, "external", "public_db").statusCode()).isEqualTo(422);
        }
        HttpResponse<String> blocked = open(m, "external", "public_db");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("RATE_LIMITED");
    }

    @Test
    void closingEndsTheBackends() throws Exception {
        MockClient b = bob();
        String id = json(open(b, "external", "bob_db")).path("id").asText();
        assertThat(backendsOf("bob")).hasSize(2);
        HttpResponse<String> del = b.send("DELETE", "/api/db-sessions/" + id, null, Map.of("Origin", b.origin(), "X-CSRF-Token", b.csrf()));
        assertThat(del.statusCode()).isEqualTo(204);
        awaitNoBackends("bob");
        assertThat(b.get("/api/db-sessions/" + id).statusCode()).isEqualTo(404);
        assertThat(apps.events("db_disconnect", "S-1-5-21-2-2-2-1002")).anyMatch(e -> String.valueOf(e.get("details")).contains("\"user\""));
    }

    @Test
    void signingOutEndsEveryDbSessionOfThatAppSession() throws Exception {
        MockClient e = user("eve", "1006");
        open(e, "external", "public_db");
        open(e, "external", "postgres");
        assertThat(backendsOf("eve")).hasSize(4);
        assertThat(e.post("/api/logout", "{}").statusCode()).isEqualTo(204);
        awaitNoBackends("eve");
        assertThat(apps.events("db_disconnect", "S-1-5-21-2-2-2-1006")).anyMatch(x -> String.valueOf(x.get("details")).contains("app_session_ended"));
    }

    @Test
    void theReaperClosesIdleIdleInTransactionAndAbandonedSessions() throws Exception {
        MockClient a = user("dave", "1007");
        DbSessionManager m = app.sessions();

        String idle = json(open(a, "external", "public_db")).path("id").asText();
        String inTx = json(open(a, "external", "postgres")).path("id").asText();
        String abandoned = json(open(a, "external", "public_db")).path("id").asText();
        for (String id : List.of(inTx, abandoned)) {
            DbSession s = m.all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            s.main().setAutoCommit(false);
            try (Statement st = s.main().createStatement()) {
                st.execute("SELECT 1");
            }
            assertThat(s.transactionState()).isEqualTo("OPEN");
        }
        assertThat(m.reap()).isZero();

        // 3 minutes, activity on all but heartbeats stopped: only the one in a transaction without heartbeats goes.
        clock.advance(Duration.ofMinutes(3));
        a.get("/api/db-sessions/" + idle);
        a.get("/api/db-sessions/" + inTx);
        a.post("/api/db-sessions/" + inTx + "/heartbeat", "{}");
        a.get("/api/db-sessions/" + abandoned);
        assertThat(m.reap()).isEqualTo(1);
        assertThat(a.get("/api/db-sessions/" + abandoned).statusCode()).isEqualTo(404);

        // 11 more minutes without activity: the idle transaction is closed; the idle session stays until 15 min.
        clock.advance(Duration.ofMinutes(11));
        assertThat(m.reap()).isEqualTo(1);
        assertThat(a.get("/api/db-sessions/" + inTx).statusCode()).isEqualTo(404);
        clock.advance(Duration.ofMinutes(16));
        assertThat(m.reap()).isEqualTo(1);
        assertThat(a.get("/api/db-sessions/" + idle).statusCode()).isEqualTo(404);
        awaitNoBackends("dave");
        List<Map<String, Object>> reaped = apps.events("session_reaped", "S-1-5-21-2-2-2-1007");
        assertThat(reaped).anyMatch(e -> String.valueOf(e.get("details")).contains("abandoned"))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("idle_in_transaction"))
                .anyMatch(e -> String.valueOf(e.get("details")).contains("\"idle\""));
    }

    @Test
    void aMissingCompanionGivesADegradedSession() throws Exception {
        connector.failCompanion("bob", true);
        try {
            MockClient b = bob();
            HttpResponse<String> r = open(b, "external", "shared_db");
            assertThat(r.statusCode()).isEqualTo(201);
            assertThat(json(r).path("companion").asBoolean()).isFalse();
            assertThat(apps.events("db_companion_failed", "S-1-5-21-2-2-2-1002")).isNotEmpty();
            closeAll(b);
        } finally {
            connector.failCompanion("bob", false);
        }
    }

    @Test
    void aConnectionAsAnotherRoleIsClosedAndRefused() throws Exception {
        connector.redirect("carol", "bob");
        try {
            HttpResponse<String> r = open(user("carol", "1004"), "external", "public_db");
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).contains("ROLE_MISMATCH");
        } finally {
            connector.redirect("carol", "carol");
        }
        awaitNoBackends("bob");
    }

    @Test
    void sessionIdsAreRandom() {
        assertThat(DbSessionManager.newId()).matches("[A-Za-z0-9_-]{22}").isNotEqualTo(DbSessionManager.newId());
        assertThat(runId).isNotEmpty();
    }
}
