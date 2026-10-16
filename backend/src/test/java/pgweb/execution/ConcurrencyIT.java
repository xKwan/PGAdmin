package pgweb.execution;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Transactions, isolation and concurrency on a real PostgreSQL 18 cluster (plan section 4, section 8.1, Phase 9).
 * PostgreSQL decides every outcome; these tests check that the app reports it truthfully. Coordination polls the
 * server (pg_stat_activity through the activity endpoint, row counts), never fixed sleeps.
 */
class ConcurrencyIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static Application app;
    /** Confirmations switched off for its external profile. */
    static Application noConfirm;
    /** Drives the reaper's clock. */
    static MutableClock clock;
    static Application timed;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("ann", "ben")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE tx_db OWNER ann");
            s.execute("GRANT CONNECT ON DATABASE tx_db TO ben");
        }
        try (Connection c = apps.pg().superuser("tx_db"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE public.acct (id int PRIMARY KEY, balance int NOT NULL CHECK (balance >= 0))");
            s.execute("INSERT INTO public.acct SELECT g, 100 FROM generate_series(1, 100) g");
            s.execute("ALTER TABLE public.acct OWNER TO ann");
            s.execute("GRANT ALL ON SCHEMA public TO ann, ben");
            s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.acct TO ben");
        }
        java.util.function.Consumer<java.util.Properties> base = p -> {
            p.setProperty("execution.enabled", "true");
            p.setProperty("profile.external.statementTimeoutSeconds", "15");
            p.setProperty("limits.cancelGraceSeconds", "1");
        };
        app = apps.app(base, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
        noConfirm = apps.app(base.andThen(p -> p.setProperty("profile.external.confirmDestructive", "false")),
                new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
        clock = new MutableClock(Instant.parse("2026-10-16T08:00:00Z"));
        timed = apps.app(base, new Application.Options(new MockAuthFilter(), r -> { }, clock, connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static final List<Session> OPENED = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        for (Session s : OPENED) {
            s.client().send("DELETE", "/api/db-sessions/" + s.id(), null, Map.of("Origin", s.client().origin(), "X-CSRF-Token", s.client().csrf()));
        }
        OPENED.clear();
    }

    record Session(MockClient client, String id) {
        HttpResponse<String> run(String sql, boolean confirmed) throws Exception {
            return client.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql, "confirmed", confirmed)));
        }

        JsonNode ok(String sql) throws Exception {
            HttpResponse<String> r = run(sql, true);
            assertThat(r.statusCode()).as(sql + " -> " + r.body()).isEqualTo(200);
            return Json.mapper().readTree(r.body());
        }

        JsonNode tx(String action, String isolation, boolean readOnly) throws Exception {
            Map<String, Object> body = new java.util.HashMap<>(Map.of("action", action, "readOnly", readOnly));
            if (isolation != null) {
                body.put("isolation", isolation);
            }
            HttpResponse<String> r = client.post("/api/db-sessions/" + id + "/transaction", Json.write(body));
            assertThat(r.statusCode()).as(action + " -> " + r.body()).isEqualTo(200);
            return Json.mapper().readTree(r.body());
        }

        JsonNode activity() throws Exception {
            return Json.mapper().readTree(client.get("/api/db-sessions/" + id + "/activity").body());
        }

        JsonNode heartbeat() throws Exception {
            return Json.mapper().readTree(client.post("/api/db-sessions/" + id + "/heartbeat", "{}").body());
        }

        CompletableFuture<JsonNode> async(String sql) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ok(sql);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    static Session session(Application a, String role, String rid) throws Exception {
        MockClient c = MockClient.user(a.port(), "TEST\\" + role, "S-1-5-21-9-9-9-" + rid);
        c.me();
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"tx_db\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        Session s = new Session(c, Json.mapper().readTree(r.body()).path("id").asText());
        OPENED.add(s);
        return s;
    }

    static int balance(int id) throws Exception {
        try (Connection c = apps.pg().superuser("tx_db"); PreparedStatement ps = c.prepareStatement("SELECT balance FROM acct WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    static int rows(String where) throws Exception {
        try (Connection c = apps.pg().superuser("tx_db"); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM acct WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static void awaitLockWait(Session s) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> s.activity().path("lockWait").asBoolean());
    }

    static String sqlstate(JsonNode result) {
        return result.path("error").path("sqlstate").asText();
    }

    // ------------------------------------------------------------------

    @Test
    void rollbackAcrossRequestsLeavesNothingAndCommitPersists() throws Exception {
        Session a = session(app, "ann", "1");
        int id = 1000 + Seeds.random("rollback").nextInt(1000);
        assertThat(a.tx("begin", null, false).path("transaction").asText()).isEqualTo("OPEN");
        assertThat(a.ok("INSERT INTO acct VALUES (" + id + ", 5)").path("transaction").asText()).isEqualTo("OPEN");
        assertThat(rows("id = " + id)).as("not visible to others before commit").isZero();
        assertThat(a.tx("rollback", null, false).path("transaction").asText()).isEqualTo("IDLE");
        assertThat(rows("id = " + id)).isZero();

        a.tx("begin", null, false);
        a.ok("INSERT INTO acct VALUES (" + id + ", 5)");
        assertThat(a.tx("commit", null, false).path("transaction").asText()).isEqualTo("IDLE");
        assertThat(rows("id = " + id)).isEqualTo(1);
        a.ok("DELETE FROM acct WHERE id = " + id);
    }

    @Test
    void isolationAndReadOnlyAreAppliedAndAFailedCommitSaysItRolledBack() throws Exception {
        Session a = session(app, "ann", "1");
        a.tx("begin", "repeatable read", true);
        assertThat(a.ok("SHOW transaction_isolation").path("results").get(0).path("rows").get(0).get(0).asText()).isEqualTo("repeatable read");
        assertThat(a.ok("SHOW transaction_read_only").path("results").get(0).path("rows").get(0).get(0).asText()).isEqualTo("on");
        JsonNode write = a.ok("UPDATE acct SET balance = balance WHERE id = 1");
        assertThat(sqlstate(write)).isEqualTo("25006");
        assertThat(write.path("transaction").asText()).isEqualTo("FAILED");
        assertThat(sqlstate(a.ok("SELECT 1"))).isEqualTo("25P02");
        JsonNode commit = a.tx("commit", null, false);
        assertThat(commit.path("transaction").asText()).isEqualTo("IDLE");
        assertThat(commit.path("notice").asText()).contains("rolled it back");
        a.tx("begin", "serializable", false);
        assertThat(a.ok("SHOW transaction_isolation").path("results").get(0).path("rows").get(0).get(0).asText()).isEqualTo("serializable");
        a.tx("rollback", null, false);
        a.tx("begin", "read committed", false);
        a.tx("rollback", null, false);
        HttpResponse<String> bad = a.client().post("/api/db-sessions/" + a.id() + "/transaction", "{\"action\":\"begin\",\"isolation\":\"read uncommitted; DROP TABLE acct\"}");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("INVALID_ISOLATION");
        assertThat(a.client().post("/api/db-sessions/" + a.id() + "/transaction", "{\"action\":\"savepoint\"}").body()).contains("INVALID_ACTION");
    }

    @Test
    void aSecondWriterWaitsShowsTheLockWaitAndProceedsAfterCommit() throws Exception {
        Session a = session(app, "ann", "1");
        Session b = session(app, "ben", "2");
        int id = 1 + Seeds.random("same-row").nextInt(100);
        int before = balance(id);
        a.tx("begin", null, false);
        a.ok("UPDATE acct SET balance = balance + 1 WHERE id = " + id);
        CompletableFuture<JsonNode> second = b.async("UPDATE acct SET balance = balance + 1 WHERE id = " + id);
        awaitLockWait(b);
        assertThat(b.activity().path("waitEventType").asText()).isEqualTo("Lock");
        assertThat(second).isNotDone();
        a.tx("commit", null, false);
        JsonNode r = second.get(20, TimeUnit.SECONDS);
        assertThat(r.path("results").get(0).path("rowCount").asInt()).isEqualTo(1);
        assertThat(balance(id)).as("both increments applied (READ COMMITTED re-checks the row)").isEqualTo(before + 2);
    }

    @Test
    void aLockTimeoutReportsLockNotAvailable() throws Exception {
        Session a = session(app, "ann", "1");
        Session b = session(app, "ben", "2");
        int id = 1 + Seeds.random("lock-timeout").nextInt(100);
        a.tx("begin", null, false);
        a.ok("SELECT * FROM acct WHERE id = " + id + " FOR UPDATE");
        b.ok("SET lock_timeout = '200ms'");
        JsonNode r = b.ok("UPDATE acct SET balance = balance WHERE id = " + id);
        assertThat(sqlstate(r)).isEqualTo("55P03");
        assertThat(r.path("transaction").asText()).isEqualTo("IDLE");
        a.tx("rollback", null, false);
    }

    @Test
    void aDeadlockAbortsExactlyOneTransaction() throws Exception {
        Session a = session(app, "ann", "1");
        Session b = session(app, "ben", "2");
        Random r = Seeds.random("deadlock");
        int x = 1 + r.nextInt(50);
        int y = 51 + r.nextInt(50);
        a.tx("begin", null, false);
        b.tx("begin", null, false);
        a.ok("UPDATE acct SET balance = balance WHERE id = " + x);
        b.ok("UPDATE acct SET balance = balance WHERE id = " + y);
        CompletableFuture<JsonNode> aWaits = a.async("UPDATE acct SET balance = balance WHERE id = " + y);
        awaitLockWait(a); // barrier: a is now blocked on b
        JsonNode bResult = b.ok("UPDATE acct SET balance = balance WHERE id = " + x);
        JsonNode aResult = aWaits.get(20, TimeUnit.SECONDS);
        List<JsonNode> both = List.of(aResult, bResult);
        assertThat(both.stream().filter(n -> "40P01".equals(sqlstate(n))).count()).as("one victim").isEqualTo(1);
        JsonNode victim = both.stream().filter(n -> "40P01".equals(sqlstate(n))).findFirst().orElseThrow();
        JsonNode survivor = both.stream().filter(n -> !"40P01".equals(sqlstate(n))).findFirst().orElseThrow();
        assertThat(victim.path("transaction").asText()).isEqualTo("FAILED");
        assertThat(survivor.path("kind").asText()).isEqualTo("command");
        assertThat(survivor.path("transaction").asText()).isEqualTo("OPEN");
        a.tx("rollback", null, false);
        b.tx("rollback", null, false);
    }

    @Test
    void aConcurrentChangeUnderRepeatableReadIsASerializationFailure() throws Exception {
        Session a = session(app, "ann", "1");
        Session b = session(app, "ben", "2");
        int id = 1 + Seeds.random("serialization").nextInt(100);
        a.tx("begin", "repeatable read", false);
        a.ok("SELECT balance FROM acct WHERE id = " + id); // takes the snapshot
        b.ok("UPDATE acct SET balance = balance + 0 WHERE id = " + id); // autocommit
        JsonNode r = a.ok("UPDATE acct SET balance = balance + 1 WHERE id = " + id);
        assertThat(sqlstate(r)).isEqualTo("40001");
        assertThat(r.path("transaction").asText()).isEqualTo("FAILED");
        a.tx("rollback", null, false);
    }

    @Test
    void cancellingABlockedStatementInsideATransactionLeavesItFailed() throws Exception {
        Session a = session(app, "ann", "1");
        Session b = session(app, "ben", "2");
        int id = 1 + Seeds.random("cancel-blocked").nextInt(100);
        a.tx("begin", null, false);
        a.ok("UPDATE acct SET balance = balance WHERE id = " + id);
        b.tx("begin", null, false);
        CompletableFuture<JsonNode> blocked = b.async("UPDATE acct SET balance = balance WHERE id = " + id);
        awaitLockWait(b);
        assertThat(b.client().post("/api/db-sessions/" + b.id() + "/cancel", "{}").statusCode()).isEqualTo(200);
        JsonNode r = blocked.get(20, TimeUnit.SECONDS);
        assertThat(r.path("kind").asText()).isEqualTo("cancelled");
        assertThat(r.path("transaction").asText()).isEqualTo("FAILED");
        b.tx("rollback", null, false);
        a.tx("rollback", null, false);
    }

    @Test
    void postgresErrorsArePassedThroughWithTheirDetails() throws Exception {
        Session a = session(app, "ann", "1");
        JsonNode dup = a.ok("INSERT INTO acct VALUES (1, 1)");
        assertThat(sqlstate(dup)).isEqualTo("23505");
        assertThat(dup.path("error").path("constraint").asText()).isEqualTo("acct_pkey");
        assertThat(dup.path("error").path("table").asText()).isEqualTo("acct");
        assertThat(dup.path("error").path("detail").asText()).contains("(id)=(1)");
        JsonNode check = a.ok("UPDATE acct SET balance = -1 WHERE id = 1");
        assertThat(sqlstate(check)).isEqualTo("23514");
        assertThat(check.path("error").path("constraint").asText()).isEqualTo("acct_balance_check");
        a.tx("begin", null, false);
        JsonNode vacuum = a.ok("VACUUM acct");
        assertThat(sqlstate(vacuum)).as("cannot run inside a transaction block").isEqualTo("25001");
        a.tx("rollback", null, false);
    }

    @Test
    void destructiveStatementsNeedAConfirmationThatIsNotASecurityControl() throws Exception {
        Session a = session(app, "ann", "1");
        int before = rows("true");
        for (Map.Entry<String, String> e : Map.of(
                "DELETE FROM acct", "no_where",
                "UPDATE acct SET balance = balance", "no_where",
                "WITH d AS (DELETE FROM acct RETURNING id) SELECT count(*) FROM d", "no_where",
                "DROP TABLE acct", "drop",
                "TRUNCATE acct", "truncate",
                "GRANT SELECT ON acct TO ben", "privileges",
                "CREATE TABLE t_unused (a int)", "ddl",
                "DO $$ BEGIN PERFORM 1; END $$", "procedural").entrySet()) {
            HttpResponse<String> r = a.run(e.getKey(), false);
            assertThat(r.statusCode()).as(e.getKey()).isEqualTo(409);
            assertThat(r.body()).as(e.getKey()).contains("CONFIRMATION_REQUIRED").contains(e.getValue());
        }
        assertThat(rows("true")).as("nothing ran").isEqualTo(before);
        assertThat(a.run("DELETE FROM acct WHERE id = -1", false).statusCode()).isEqualTo(200);
        assertThat(a.run("SELECT count(*) FROM acct", false).statusCode()).isEqualTo(200);
        // Confirmed, it runs (inside a transaction here, rolled back).
        a.tx("begin", null, false);
        JsonNode confirmed = Json.mapper().readTree(a.run("DELETE FROM acct", true).body());
        assertThat(confirmed.path("results").get(0).path("rowCount").asInt()).isEqualTo(before);
        a.tx("rollback", null, false);
        assertThat(rows("true")).isEqualTo(before);
        assertThat(apps.events("exec_rejected", "S-1-5-21-9-9-9-1")).anyMatch(ev -> "confirmation_required".equals(ev.get("parse_status")));
        // A profile can switch confirmations off.
        Session n = session(noConfirm, "ann", "1");
        n.tx("begin", null, false);
        assertThat(n.run("DELETE FROM acct", false).statusCode()).isEqualTo(200);
        n.tx("rollback", null, false);
    }

    @Test
    void closingASessionWhileAStatementRunsCancelsItFirst() throws Exception {
        Session a = session(app, "ann", "1");
        int pid = Json.mapper().readTree(a.client().get("/api/db-sessions/" + a.id()).body()).path("backendPid").asInt();
        CompletableFuture<JsonNode> running = a.async("SELECT pg_sleep(20)");
        await().atMost(Duration.ofSeconds(10)).until(() -> a.activity().path("running").asBoolean());
        HttpResponse<String> del = a.client().send("DELETE", "/api/db-sessions/" + a.id(), null,
                Map.of("Origin", a.client().origin(), "X-CSRF-Token", a.client().csrf()));
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(running.get(20, TimeUnit.SECONDS).path("kind").asText()).isEqualTo("cancelled");
        await().atMost(Duration.ofSeconds(10)).until(() -> !backendExists(pid));
    }

    @Test
    void heartbeatsDoNotKeepAnIdleTransactionAliveAndWarnFirst() throws Exception {
        Session a = session(timed, "ann", "1");
        int id = 1 + Seeds.random("idle-tx").nextInt(100);
        a.tx("begin", null, false);
        a.ok("SELECT * FROM acct WHERE id = " + id + " FOR UPDATE");
        JsonNode hb = a.heartbeat();
        assertThat(hb.path("transaction").asText()).isEqualTo("OPEN");
        assertThat(hb.path("closeReason").asText()).isEqualTo("idle_in_transaction");
        assertThat(hb.path("warning").asBoolean()).isFalse();
        for (int minute = 1; minute <= 9; minute++) { // the page stays open: heartbeats every minute
            clock.advance(Duration.ofMinutes(1));
            hb = a.heartbeat();
            timed.sessions().reap();
        }
        assertThat(hb.path("warning").asBoolean()).as("warned at 8 of 10 minutes").isTrue();
        clock.advance(Duration.ofMinutes(2));
        a.heartbeat();
        assertThat(timed.sessions().reap()).isEqualTo(1);
        assertThat(a.client().get("/api/db-sessions/" + a.id()).statusCode()).isEqualTo(404);
        // The row lock is gone with the session.
        Session b = session(app, "ben", "2");
        b.ok("SET lock_timeout = '2s'");
        assertThat(sqlstate(b.ok("UPDATE acct SET balance = balance WHERE id = " + id))).isEmpty();
    }

    @Test
    void theMaximumTransactionAgeEndsABusyTransaction() throws Exception {
        Session a = session(timed, "ann", "1");
        a.tx("begin", null, false);
        JsonNode hb = null;
        for (int i = 0; i < 12; i++) { // active every 5 minutes, so never idle
            clock.advance(Duration.ofMinutes(5));
            a.ok("SELECT 1");
            hb = a.heartbeat();
            timed.sessions().reap();
        }
        assertThat(hb.path("closeReason").asText()).isEqualTo("max_transaction_age");
        assertThat(hb.path("warning").asBoolean()).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(timed.sessions().reap()).isEqualTo(1);
        assertThat(apps.events("session_reaped", "S-1-5-21-9-9-9-1")).anyMatch(e -> String.valueOf(e.get("details")).contains("max_transaction_age"));
    }

    static boolean backendExists(int pid) throws Exception {
        try (Connection c = apps.pg().superuser("postgres"); PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid = ?")) {
            ps.setInt(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
