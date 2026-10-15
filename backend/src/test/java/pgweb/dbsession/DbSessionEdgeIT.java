package pgweb.dbsession;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.config.Config;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.MutableClock;
import pgweb.testsupport.PasswordTestConnector;
import pgweb.testsupport.TestApps;
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Edge cases of DB sessions on their own cluster (this class restarts PostgreSQL). */
class DbSessionEdgeIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static MutableClock clock;
    static Application app;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        clock = new MutableClock(Instant.now());
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("ann", "ben", "cat")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE edge_db");
            s.execute("REVOKE CONNECT ON DATABASE postgres FROM PUBLIC"); // cat can't list databases
            s.execute("GRANT CONNECT ON DATABASE postgres TO ann, ben");
        }
        app = apps.app(p -> {
            noInternal(p);
            p.setProperty("limits.sessionsGlobal", "2");
            p.setProperty("limits.sessionsPerUser", "2");
        }, new Application.Options(new MockAuthFilter(), r -> { }, clock, connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static void noInternal(java.util.Properties p) {
        p.stringPropertyNames().stream().filter(k -> k.startsWith("profile.internal.")).toList().forEach(p::remove);
    }

    static MockClient user(String name, String rid) throws Exception {
        MockClient c = MockClient.user(app.port(), "TEST\\" + name, "S-1-5-21-4-4-4-" + rid);
        c.me();
        return c;
    }

    static String open(MockClient c, String db) throws Exception {
        HttpResponse<String> r = c.post("/api/db-sessions/external", Json.write(Map.of("database", db)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return Json.mapper().readTree(r.body()).path("id").asText();
    }

    @Test
    void theGlobalCapRefusesNewSessionsWhenTheServerIsFull() throws Exception {
        MockClient a = user("ann", "1");
        MockClient b = user("ben", "2");
        String s1 = open(a, "edge_db");
        String s2 = open(b, "edge_db");
        HttpResponse<String> full = a.post("/api/db-sessions/external", "{\"database\":\"edge_db\"}");
        assertThat(full.statusCode()).isEqualTo(503);
        assertThat(full.body()).contains("SERVER_BUSY");
        a.send("DELETE", "/api/db-sessions/" + s1, null, Map.of("Origin", a.origin(), "X-CSRF-Token", a.csrf()));
        b.send("DELETE", "/api/db-sessions/" + s2, null, Map.of("Origin", b.origin(), "X-CSRF-Token", b.csrf()));
    }

    @Test
    void anUnconfiguredProfileAndOddPathsAre404() throws Exception {
        MockClient a = user("ann", "1");
        assertThat(a.get("/api/profiles").body()).doesNotContain("internal");
        assertThat(a.post("/api/db-sessions/internal", "{\"database\":\"edge_db\"}").statusCode()).isIn(403, 404);
        assertThat(a.post("/api/profiles/external/other", "{}").statusCode()).isEqualTo(404);
        assertThat(a.get("/api/db-sessions/").statusCode()).isEqualTo(200);
        assertThat(a.post("/api/db-sessions/x/y/z", "{}").statusCode()).isEqualTo(404);
        assertThat(a.send("DELETE", "/api/db-sessions/a/b", null, Map.of("Origin", a.origin(), "X-CSRF-Token", a.csrf())).statusCode()).isEqualTo(404);
        assertThat(a.send("DELETE", "/api/db-sessions", null, Map.of("Origin", a.origin(), "X-CSRF-Token", a.csrf())).statusCode()).isEqualTo(404);
    }

    @Test
    void listingFailuresAreGenericAndCountTowardsTheLimit() throws Exception {
        MockClient c = user("cat", "3");
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = c.post("/api/profiles/external/databases", "{}");
            assertThat(r.statusCode()).isEqualTo(422);
            assertThat(r.body()).contains("CONNECT_FAILED");
        }
        HttpResponse<String> blocked = c.post("/api/profiles/external/databases", "{}");
        assertThat(blocked.statusCode()).isEqualTo(429);
    }

    @Test
    void transactionAgeIsTrackedAndEnforced() throws Exception {
        MockClient b = user("ben", "2");
        String id = open(b, "edge_db");
        DbSessionManager m = app.sessions();
        DbSession s = m.all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        s.main().setAutoCommit(false);
        try (Statement st = s.main().createStatement()) {
            st.execute("SELECT 1");
        }
        Instant t0 = clock.instant();
        s.transactionObserved(true, t0);
        s.transactionObserved(true, t0.plusSeconds(5)); // keeps the original start
        assertThat(s.transactionStartedAt()).isEqualTo(t0);
        // Stay active and heartbeating for 61 minutes: only the transaction-age rule applies.
        for (int i = 0; i < 61; i++) {
            clock.advance(Duration.ofMinutes(1));
            b.post("/api/db-sessions/" + id + "/heartbeat", "{}");
        }
        assertThat(m.reap()).isEqualTo(1);
        assertThat(apps.events("session_reaped", "S-1-5-21-4-4-4-2")).anyMatch(e -> String.valueOf(e.get("details")).contains("max_transaction_age"));
        s.transactionObserved(false, clock.instant());
        assertThat(s.transactionStartedAt()).isNull();
        assertThat(s.closeReason()).isEqualTo("max_transaction_age");
        assertThat(s.companion()).isNotNull();
        assertThat(s.companionPid()).isNotNull();
    }

    @Test
    void aServerRestartLosesSessionsVisibly() throws Exception {
        MockClient a = user("ann", "1");
        String viaHeartbeat = open(a, "edge_db");
        String viaReaper = open(a, "edge_db");
        apps.pg().stop();
        apps.pg().restart();
        HttpResponse<String> hb = a.post("/api/db-sessions/" + viaHeartbeat + "/heartbeat", "{}");
        assertThat(hb.statusCode()).isEqualTo(410);
        assertThat(hb.body()).contains("SESSION_LOST");
        assertThat(app.sessions().reap()).isEqualTo(1);
        assertThat(a.get("/api/db-sessions/" + viaReaper).statusCode()).isEqualTo(404);
        assertThat(apps.events("session_lost", "S-1-5-21-4-4-4-1")).hasSizeGreaterThanOrEqualTo(2);
        // Connections are never reused: a new session opens normally.
        String fresh = open(a, "edge_db");
        assertThat(fresh).isNotIn(viaHeartbeat, viaReaper);
        a.send("DELETE", "/api/db-sessions/" + fresh, null, Map.of("Origin", a.origin(), "X-CSRF-Token", a.csrf()));
    }

    @Test
    void shutdownClosesEverySession() throws Exception {
        Application other = apps.app(DbSessionEdgeIT::noInternal, new Application.Options(new MockAuthFilter(), r -> { }, clock, connector));
        MockClient c = MockClient.user(other.port(), "TEST\\ben", "S-1-5-21-4-4-4-2");
        c.me();
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"edge_db\"}");
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(other.sessions().count()).isEqualTo(1);
        other.close();
        assertThat(other.sessions().count()).isZero();
        // Stopping Tomcat ends its app sessions first, so the listener closes them ("app_session_ended"); the
        // manager's own shutdown sweep ("shutdown") catches anything left. Either way, nothing stays open.
        String closed = Json.mapper().readTree(r.body()).path("id").asText();
        assertThat(apps.events("db_disconnect", "S-1-5-21-4-4-4-2")).anyMatch(e -> closed.equals(e.get("db_session_id"))
                && (String.valueOf(e.get("details")).contains("shutdown") || String.valueOf(e.get("details")).contains("app_session_ended")));
    }

    @Test
    void theProductionConnectorNeedsADelegatedIdentityAndUsesTheProfileSettings() throws Exception {
        Path ca = Files.writeString(tmp.resolve("ca.pem"), "x");
        Config.DbProfile p = new Config.DbProfile("external", "X", "pg.corp.example", 5432, "verify-full", Optional.of(ca), "postgres", false, Duration.ofSeconds(60));
        assertThatThrownBy(() -> new SspiDelegatingConnector().open(null, p, "db", "ann", "pgweb"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28000"));
        var props = SspiDelegatingConnector.baseProperties(p, "ann", "pgweb");
        assertThat(props.getProperty("sslmode")).isEqualTo("verify-full");
        assertThat(props.getProperty("sslrootcert")).isEqualTo(ca.toAbsolutePath().toString());
        assertThat(props.getProperty("preferQueryMode")).isEqualTo("extended");
        assertThat(props.getProperty("password")).isNull();
        assertThat(SspiDelegatingConnector.url(p, "sales data/é")).isEqualTo("jdbc:postgresql://pg.corp.example:5432/sales%20data%2F%C3%A9");
        assertThatThrownBy(() -> DbSessionManager.validateDatabaseName(null)).isInstanceOf(pgweb.web.ApiException.class);
        assertThatThrownBy(() -> DbSessionManager.validateDatabaseName("x\u007f")).isInstanceOf(pgweb.web.ApiException.class);
        DbSessionManager.validateDatabaseName("ok_name");
    }

    @Test
    void viewsDescribeSessionsWithoutCredentials() throws Exception {
        MockClient a = user("ann", "1");
        String id = open(a, "edge_db");
        JsonNode v = Json.mapper().readTree(a.get("/api/db-sessions/" + id).body());
        assertThat(v.has("password")).isFalse();
        assertThat(v.path("state").asText()).isEqualTo("OPEN");
        assertThat(v.path("lastActivity").asText()).isNotEmpty();
        a.send("DELETE", "/api/db-sessions/" + id, null, Map.of("Origin", a.origin(), "X-CSRF-Token", a.csrf()));
    }
}
