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
import pgweb.testsupport.PasswordTestConnector;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestApps;
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The execution engine on a real PostgreSQL 18 cluster (plan Phase 7): every outcome kind, the single-statement gate
 * with the server log as proof that rejected text never reached PostgreSQL, limits, timeouts, cancel and escalation,
 * fail-closed audit and literal-free audit text.
 */
class ExecutionIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static Application app;
    /** Same, but its cancel does nothing, so escalation must terminate the backend. */
    static Application stubborn;
    /** Execution switched off. */
    static Application off;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("xena", "yuri", "zoe")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE exec_db OWNER xena");
            s.execute("GRANT CONNECT ON DATABASE exec_db TO yuri, zoe");
        }
        try (Connection c = apps.pg().superuser("exec_db"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE public.items (id int PRIMARY KEY, name text)");
            s.execute("INSERT INTO public.items SELECT g, 'item ' || g FROM generate_series(1, 50) g");
            s.execute("ALTER TABLE public.items OWNER TO xena");
            s.execute("GRANT ALL ON SCHEMA public TO xena, yuri, zoe");
            s.execute("GRANT SELECT, UPDATE ON public.items TO yuri, zoe");
        }
        java.util.function.Consumer<java.util.Properties> fast = p -> {
            p.setProperty("execution.enabled", "true");
            p.setProperty("profile.external.statementTimeoutSeconds", "3");
            p.setProperty("limits.cancelGraceSeconds", "1");
        };
        app = apps.app(fast, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
        stubborn = apps.app(fast.andThen(p -> p.setProperty("profile.external.statementTimeoutSeconds", "1")),
                new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector, st -> { }));
        off = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    /** Sessions opened by the current test; closed after it (the per-user cap is 3). */
    static final List<Session> OPENED = new java.util.concurrent.CopyOnWriteArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        for (Session s : OPENED) {
            s.client().send("DELETE", "/api/db-sessions/" + s.id(), null, Map.of("Origin", s.client().origin(), "X-CSRF-Token", s.client().csrf()));
        }
        OPENED.clear();
    }

    record Session(MockClient client, String id) {
        /** Runs {@code sql} with destructive statements already confirmed (these tests are about execution itself). */
        HttpResponse<String> run(String sql) throws Exception {
            return client.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql, "confirmed", true)));
        }

        /** Runs {@code sql} as the editor first sends it: without a confirmation. */
        HttpResponse<String> runUnconfirmed(String sql) throws Exception {
            return client.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql)));
        }

        HttpResponse<String> tx(String action, String isolation, boolean readOnly) throws Exception {
            Map<String, Object> body = new java.util.HashMap<>(Map.of("action", action, "readOnly", readOnly));
            if (isolation != null) {
                body.put("isolation", isolation);
            }
            return client.post("/api/db-sessions/" + id + "/transaction", Json.write(body));
        }

        JsonNode ok(String sql) throws Exception {
            HttpResponse<String> r = run(sql);
            assertThat(r.statusCode()).as(sql + " -> " + r.body()).isEqualTo(200);
            return Json.mapper().readTree(r.body());
        }
    }

    static Session session(Application a, String role, String rid) throws Exception {
        MockClient c = MockClient.user(a.port(), "TEST\\" + role, "S-1-5-21-6-6-6-" + rid);
        c.me();
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"exec_db\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        Session s = new Session(c, Json.mapper().readTree(r.body()).path("id").asText());
        OPENED.add(s);
        return s;
    }

    static String serverLog() throws Exception {
        return Files.readString(apps.pg().root().resolve("data").resolve("log").resolve("pg.log"), StandardCharsets.UTF_8);
    }

    static String marker() {
        return "mk_" + UUID.randomUUID().toString().replace("-", "");
    }

    // ------------------------------------------------------------------

    @Test
    void rowsCommandsAndErrorsAreReportedDistinctly() throws Exception {
        Session s = session(app, "xena", "1");
        JsonNode rows = s.ok("SELECT id, name FROM items WHERE id <= 3 ORDER BY id");
        assertThat(rows.path("kind").asText()).isEqualTo("rows");
        assertThat(rows.path("statementClass").asText()).isEqualTo("select");
        JsonNode r0 = rows.path("results").get(0);
        assertThat(r0.path("columns").toString()).contains("\"name\":\"id\"", "\"type\":\"int4\"", "\"name\":\"name\"", "\"type\":\"text\"");
        assertThat(r0.path("rows").toString()).isEqualTo("[[\"1\",\"item 1\"],[\"2\",\"item 2\"],[\"3\",\"item 3\"]]");
        assertThat(r0.path("truncated").asBoolean()).isFalse();
        assertThat(rows.path("retrievedAt").asText()).isNotEmpty();

        JsonNode empty = s.ok("SELECT id FROM items WHERE false");
        assertThat(empty.path("results").get(0).path("rowCount").asInt()).isZero();
        assertThat(empty.path("results").get(0).path("columns").size()).isEqualTo(1);

        JsonNode upd = s.ok("UPDATE items SET name = name WHERE id <= 7");
        assertThat(upd.path("kind").asText()).isEqualTo("command");
        assertThat(upd.path("statementClass").asText()).isEqualTo("dml");
        assertThat(upd.path("results").get(0).path("rowCount").asInt()).isEqualTo(7);

        String t = "t_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        JsonNode ddl = s.ok("CREATE TABLE " + t + " (a int)");
        assertThat(ddl.path("kind").asText()).isEqualTo("command");
        assertThat(ddl.path("statementClass").asText()).isEqualTo("ddl");

        JsonNode err = s.ok("SELECT * FROM no_such_table_" + t);
        assertThat(err.path("kind").asText()).isEqualTo("error");
        assertThat(err.path("error").path("sqlstate").asText()).isEqualTo("42P01");
        assertThat(err.path("error").path("position").asInt()).isEqualTo(15);
        JsonNode div = s.ok("SELECT 1 / 0");
        assertThat(div.path("error").path("sqlstate").asText()).isEqualTo("22012");
        assertThat(div.path("transaction").asText()).isEqualTo("IDLE");
    }

    @Test
    void theGateStopsMultipleAndEmptyStatementsBeforeTheServerSeesThem() throws Exception {
        Session s = session(app, "yuri", "2");
        String m = marker();
        for (String bad : List.of("SELECT '" + m + "a'; SELECT 2", "SELECT '" + m + "b';DROP TABLE items", "SELECT 1; SELECT '" + m + "c' --x")) {
            HttpResponse<String> r = s.run(bad);
            assertThat(r.statusCode()).as(bad).isEqualTo(400);
            assertThat(r.body()).contains("MULTIPLE_STATEMENTS");
        }
        for (String empty : List.of("", "   ", ";", ";;", "-- only a comment", "/* c */")) {
            HttpResponse<String> r = s.run(empty);
            assertThat(r.statusCode()).as("[" + empty + "]").isEqualTo(400);
            assertThat(r.body()).contains("EMPTY_STATEMENT");
        }
        HttpResponse<String> nul = s.run("SELECT '" + m + "e'\u0000; SELECT 2");
        assertThat(nul.statusCode()).isEqualTo(400);
        assertThat(nul.body()).contains("SYNTAX_ERROR").contains("NUL");
        HttpResponse<String> syn = s.run("SELEC '" + m + "d'");
        assertThat(syn.statusCode()).isEqualTo(400);
        assertThat(syn.body()).contains("SYNTAX_ERROR").contains("\"position\":1");
        assertThat(s.run("COPY items TO STDOUT").body()).contains("UNSUPPORTED");
        assertThat(s.run("LISTEN chan").body()).contains("UNSUPPORTED");
        // The marker reached PostgreSQL only if it is in its statement log.
        s.ok("SELECT '" + m + "_sentinel'");
        await().atMost(Duration.ofSeconds(10)).until(() -> serverLog().contains(m + "_sentinel"));
        String log = serverLog();
        assertThat(log).doesNotContain(m + "a").doesNotContain(m + "b").doesNotContain(m + "c").doesNotContain(m + "d").doesNotContain(m + "e");
        assertThat(apps.events("exec_rejected", "S-1-5-21-6-6-6-2")).hasSizeGreaterThanOrEqualTo(11);
    }

    @Test
    void theParserCorpusAgreesWithTheServerAndOnlySingleStatementsRun() throws Exception {
        Session s = session(app, "xena", "1");
        for (ParserCorpus.Item item : ParserCorpus.ITEMS) {
            s.ok("SET standard_conforming_strings = " + (item.scsOff() ? "off" : "on"));
            String sql = item.instantiate();
            HttpResponse<String> r = s.run(sql);
            String body = r.body();
            if (item.expected() == 1) {
                assertThat(r.statusCode()).as(item.category() + ": " + sql + " -> " + body).isEqualTo(200);
                assertThat(Json.mapper().readTree(body).path("kind").asText()).as(sql).isIn("rows", "command", "error");
            } else if (item.expected() == 0) {
                assertThat(body).as(sql).contains("EMPTY_STATEMENT");
            } else if (item.expected() > 1) {
                assertThat(body).as(sql).contains("MULTIPLE_STATEMENTS").contains("found " + item.expected());
            } else {
                assertThat(body).as(sql).contains("SYNTAX_ERROR");
            }
        }
        s.ok("SET standard_conforming_strings = on");
    }

    @Test
    void largeResultsAreBoundedAndCellsTruncated() throws Exception {
        Session s = session(app, "zoe", "3");
        JsonNode big = s.ok("SELECT g FROM generate_series(1, 100000) g");
        JsonNode r = big.path("results").get(0);
        assertThat(r.path("rowCount").asInt()).isEqualTo(1000);
        assertThat(r.path("truncated").asBoolean()).isTrue();
        assertThat(r.path("truncatedBy").asText()).isEqualTo("rows");

        JsonNode wide = s.ok("SELECT repeat('x', 30000) FROM generate_series(1, 1000)");
        assertThat(wide.path("results").get(0).path("truncatedBy").asText()).isEqualTo("bytes");
        assertThat(wide.toString().length()).isLessThan(12 * 1024 * 1024);

        JsonNode cell = s.ok("SELECT repeat('é', 40000) AS long_text, 'short' AS s");
        JsonNode cr = cell.path("results").get(0);
        assertThat(cr.path("truncatedCells").toString()).isEqualTo("[[0,0]]");
        assertThat(cr.path("rows").get(0).get(0).asText().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
        assertThat(cr.path("rows").get(0).get(1).asText()).isEqualTo("short");

        JsonNode returning = s.ok("UPDATE items SET name = name RETURNING id");
        assertThat(returning.path("kind").asText()).isEqualTo("rows");
    }

    @Test
    void typesAreReturnedExactlyAsPostgresWritesThem() throws Exception {
        Session s = session(app, "zoe", "3");
        JsonNode r = s.ok("SELECT NULL::text AS n, ''::text AS e, 'NULL'::text AS literal_null, 1234567890.123456789012345::numeric AS num, "
                + "'\\xdeadbeef'::bytea AS b, '{\"a\": [1, 2]}'::jsonb AS j, ARRAY[1,2,3] AS arr, true AS t, "
                + "'2026-10-16 01:02:03.456789+00'::timestamptz AS ts, '1 day 02:03:04'::interval AS iv, "
                + "'5f1d0d6a-0000-4000-8000-000000000000'::uuid AS u, 'é漢🙂'::text AS uni").path("results").get(0);
        JsonNode row = r.path("rows").get(0);
        assertThat(row.get(0).isNull()).isTrue();
        assertThat(row.get(1).asText()).isEmpty();
        assertThat(row.get(2).asText()).isEqualTo("NULL");
        assertThat(row.get(3).asText()).isEqualTo("1234567890.123456789012345");
        assertThat(row.get(4).asText()).isEqualTo("\\xdeadbeef");
        assertThat(row.get(5).asText()).isEqualTo("{\"a\": [1, 2]}");
        assertThat(row.get(6).asText()).isEqualTo("{1,2,3}");
        assertThat(row.get(7).asText()).isIn("t", "true");
        assertThat(row.get(8).asText()).startsWith("2026-10-16");
        assertThat(row.get(9).asText()).isEqualTo("1 day 02:03:04");
        assertThat(row.get(10).asText()).isEqualTo("5f1d0d6a-0000-4000-8000-000000000000");
        assertThat(row.get(11).asText()).isEqualTo("é漢🙂");
        assertThat(r.path("columns").toString()).contains("numeric", "bytea", "jsonb", "_int4", "timestamptz", "uuid");
    }

    @Test
    void theTimeoutHoldsEvenAfterTheUserDisablesServerTimeouts() throws Exception {
        Session s = session(app, "xena", "1");
        for (String setup : List.of("SET statement_timeout = 0", "SET lock_timeout = 0", "RESET ALL")) {
            s.ok(setup);
            long t0 = System.nanoTime();
            JsonNode r = s.ok("SELECT pg_sleep(30)");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(r.path("kind").asText()).as(setup).isEqualTo("timeout");
            assertThat(ms).as(setup).isBetween(2500L, 10_000L);
            assertThat(s.ok("SELECT 1").path("kind").asText()).isEqualTo("rows"); // session still usable
        }
        assertThat(apps.events("exec_outcome", "S-1-5-21-6-6-6-1")).anyMatch(e -> "timeout".equals(e.get("outcome")));
    }

    @Test
    void cancelStopsTheStatementAndReleasesItsLocks() throws Exception {
        Session holder = session(app, "yuri", "2");
        Session waiter = session(app, "zoe", "3");
        Random rnd = Seeds.random("cancel");
        int id = 1 + rnd.nextInt(50);
        CompletableFuture<JsonNode> running = CompletableFuture.supplyAsync(() -> {
            try {
                return holder.ok("SELECT pg_sleep(20) FROM (SELECT id FROM items WHERE id = " + id + " FOR UPDATE) x");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> activeQueries("yuri") > 0);
        HttpResponse<String> busy = holder.run("SELECT 1");
        assertThat(busy.statusCode()).isEqualTo(409);
        assertThat(busy.body()).contains("SESSION_BUSY");
        HttpResponse<String> c = holder.client().post("/api/db-sessions/" + holder.id() + "/cancel", "{}");
        assertThat(c.statusCode()).as(c.body()).isEqualTo(200);
        JsonNode r = running.get(20, TimeUnit.SECONDS);
        assertThat(r.path("kind").asText()).isEqualTo("cancelled");
        await().atMost(Duration.ofSeconds(10)).until(() -> activeQueries("yuri") == 0);
        // Another session can now take the row lock the cancelled statement held.
        assertThat(waiter.ok("UPDATE items SET name = name WHERE id = " + id).path("results").get(0).path("rowCount").asInt()).isEqualTo(1);
        assertThat(holder.client().post("/api/db-sessions/" + holder.id() + "/cancel", "{}").statusCode()).isEqualTo(409);
        await().atMost(Duration.ofSeconds(5)).until(() -> apps.events("cancel_result", "S-1-5-21-6-6-6-2").stream()
                .anyMatch(e -> "confirmed".equals(e.get("cancel_outcome"))));
    }

    static JsonNode activity(Session s) throws Exception {
        return Json.mapper().readTree(s.client().get("/api/db-sessions/" + s.id() + "/activity").body());
    }

    @Test
    void aLockWaitIsVisibleWhileTheStatementRuns() throws Exception {
        Session holder = session(app, "xena", "1");
        Session waiter = session(app, "yuri", "2");
        int id = 1 + Seeds.random("lock-wait").nextInt(50);
        assertThat(activity(waiter).path("running").asBoolean()).isFalse();
        holder.ok("BEGIN");
        holder.ok("UPDATE items SET name = name WHERE id = " + id);
        CompletableFuture<JsonNode> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                return waiter.ok("UPDATE items SET name = name WHERE id = " + id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50)).until(() -> activity(waiter).path("lockWait").asBoolean());
            JsonNode a = activity(waiter);
            assertThat(a.path("running").asBoolean()).isTrue();
            assertThat(a.path("waitEventType").asText()).isEqualTo("Lock");
            assertThat(a.path("elapsedMs").asLong()).isNotNegative();
            assertThat(a.path("executionId").asText()).isNotEmpty();
        } finally {
            holder.ok("ROLLBACK");
        }
        assertThat(blocked.get(20, TimeUnit.SECONDS).path("results").get(0).path("rowCount").asInt()).isEqualTo(1);
        assertThat(waiter.client().get("/api/db-sessions/" + waiter.id() + "/nonsense").statusCode()).isEqualTo(404);
    }

    @Test
    void aDegradedSessionReportsRunningWithoutWaitDetails() throws Exception {
        connector.failCompanion("zoe", true);
        Session s;
        try {
            s = session(app, "zoe", "3");
        } finally {
            connector.failCompanion("zoe", false);
        }
        CompletableFuture<JsonNode> running = CompletableFuture.supplyAsync(() -> {
            try {
                return s.ok("SELECT pg_sleep(1)");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> activity(s).path("running").asBoolean());
        assertThat(activity(s).has("waitEventType")).isFalse();
        assertThat(running.get(20, TimeUnit.SECONDS).path("kind").asText()).isEqualTo("rows");
    }

    @Test
    void anIneffectiveCancelEscalatesToTerminationAndQuarantine() throws Exception {
        Session s = session(stubborn, "xena", "1");
        int pid = Json.mapper().readTree(s.client().get("/api/db-sessions/" + s.id()).body()).path("backendPid").asInt();
        JsonNode r = s.ok("SELECT pg_sleep(30)");
        assertThat(r.path("kind").asText()).isEqualTo("timeout");
        assertThat(r.path("cancelOutcome").asText()).isEqualTo("escalated_terminated");
        assertThat(r.path("sessionEnded").asBoolean()).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> !backendExists(pid));
        assertThat(s.client().get("/api/db-sessions/" + s.id()).statusCode()).isEqualTo(404);
    }

    @Test
    void withoutACompanionTheStuckConnectionIsClosedAndTheOutcomeUnconfirmed() throws Exception {
        connector.failCompanion("yuri", true);
        Session s;
        try {
            s = session(stubborn, "yuri", "2");
        } finally {
            connector.failCompanion("yuri", false);
        }
        assertThat(Json.mapper().readTree(s.client().get("/api/db-sessions/" + s.id()).body()).path("companion").asBoolean()).isFalse();
        int pid = Json.mapper().readTree(s.client().get("/api/db-sessions/" + s.id()).body()).path("backendPid").asInt();
        JsonNode r = s.ok("SELECT pg_sleep(30)");
        assertThat(r.path("kind").asText()).isEqualTo("timeout");
        assertThat(r.path("cancelOutcome").asText()).isEqualTo("unconfirmed");
        assertThat(r.path("sessionEnded").asBoolean()).isTrue();
        assertThat(apps.events("session_quarantined", "S-1-5-21-6-6-6-2")).isNotEmpty();
        // Finding P7-2: PostgreSQL on Windows can't detect the closed socket during a statement
        // (client_connection_check_interval must be 0 there), so the backend runs on. Documented; cleaned up here.
        assertThat(backendExists(pid)).isTrue();
        try (Connection c = apps.pg().superuser("postgres"); PreparedStatement ps = c.prepareStatement("SELECT pg_terminate_backend(?)")) {
            ps.setInt(1, pid);
            ps.executeQuery().close();
        }
    }

    @Test
    void aMissingSqlFieldIsAnEmptyStatement() throws Exception {
        Session s = session(app, "zoe", "3");
        HttpResponse<String> r = s.client().post("/api/db-sessions/" + s.id() + "/execute", "{}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("EMPTY_STATEMENT");
        assertThat(s.client().post("/api/db-sessions/" + s.id() + "/execute", "{\"sql\":\"SELECT 1\",\"host\":\"x\"}").statusCode())
                .as("unknown fields are rejected").isEqualTo(400);
    }

    @Test
    void aConnectionLostWhileIdleIsReportedAndTheSessionEnds() throws Exception {
        Session s = session(app, "xena", "1");
        int pid = Json.mapper().readTree(s.client().get("/api/db-sessions/" + s.id()).body()).path("backendPid").asInt();
        try (Connection c = apps.pg().superuser("postgres"); PreparedStatement ps = c.prepareStatement("SELECT pg_terminate_backend(?)")) {
            ps.setInt(1, pid);
            ps.executeQuery().close();
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> !backendExists(pid));
        HttpResponse<String> r = s.run("SELECT 1");
        // Either the statement finds the loss (the driver only learns of it when it sends: indeterminate), or the
        // reaper (every 10 s) has already removed the lost session (404). Never a result from a dead session.
        if (r.statusCode() == 200) {
            JsonNode doc = Json.mapper().readTree(r.body());
            assertThat(doc.path("kind").asText()).isEqualTo("indeterminate");
            assertThat(doc.path("sessionEnded").asBoolean()).isTrue();
        } else {
            assertThat(r.statusCode()).isIn(404, 410);
        }
        assertThat(s.run("SELECT 1").statusCode()).isIn(404, 410);
    }

    @Test
    void aLostConnectionIsIndeterminate() throws Exception {
        Session s = session(app, "zoe", "3");
        int pid = Json.mapper().readTree(s.client().get("/api/db-sessions/" + s.id()).body()).path("backendPid").asInt();
        CompletableFuture<JsonNode> running = CompletableFuture.supplyAsync(() -> {
            try {
                return s.ok("SELECT pg_sleep(10)");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> activeQueries("zoe") > 0);
        try (Connection c = apps.pg().superuser("postgres"); PreparedStatement ps = c.prepareStatement("SELECT pg_terminate_backend(?)")) {
            ps.setInt(1, pid);
            ps.executeQuery().close();
        }
        JsonNode r = running.get(20, TimeUnit.SECONDS);
        assertThat(r.path("kind").asText()).isEqualTo("indeterminate");
        assertThat(r.path("sessionEnded").asBoolean()).isTrue();
        assertThat(apps.events("exec_outcome", "S-1-5-21-6-6-6-3")).anyMatch(e -> "indeterminate".equals(e.get("outcome")));
    }

    @Test
    void transactionStateIsTrackedAcrossRequests() throws Exception {
        Session s = session(app, "yuri", "2");
        assertThat(s.ok("BEGIN").path("transaction").asText()).isEqualTo("OPEN");
        assertThat(s.ok("UPDATE items SET name = 'x' WHERE id = 1").path("transaction").asText()).isEqualTo("OPEN");
        assertThat(s.ok("SELECT 1/0").path("transaction").asText()).isEqualTo("FAILED");
        assertThat(s.ok("SELECT 1").path("error").path("sqlstate").asText()).isEqualTo("25P02");
        assertThat(s.ok("ROLLBACK").path("transaction").asText()).isEqualTo("IDLE");
        assertThat(s.ok("SELECT name FROM items WHERE id = 1").path("results").get(0).path("rows").get(0).get(0).asText()).isNotEqualTo("x");
    }

    @Test
    void auditIntentComesFirstAndCarriesNoLiterals() throws Exception {
        Session s = session(app, "xena", "1");
        String secret = "s3cr3t-" + UUID.randomUUID();
        String sql = "SELECT '" + secret + "' AS a, 42 AS answer /* note " + secret + " */";
        s.ok(sql);
        Map<String, Object> intent = apps.events("exec_intent", "S-1-5-21-6-6-6-1").get(0);
        assertThat(String.valueOf(intent.get("sql_normalised"))).doesNotContain(secret).doesNotContain("42").contains("$1").contains("$2");
        assertThat(intent.get("sql_hmac")).isEqualTo(app.audit().sqlHmac(sql));
        assertThat(intent.get("statement_class")).isEqualTo("select");
        Map<String, Object> outcome = apps.events("exec_outcome", "S-1-5-21-6-6-6-1").get(0);
        assertThat(outcome.get("intent_id")).isEqualTo(intent.get("execution_id"));
        assertThat(((Number) outcome.get("seq")).longValue()).isGreaterThan(((Number) intent.get("seq")).longValue());
        // With standard_conforming_strings off, no text is stored at all (S2 N3).
        s.ok("SET standard_conforming_strings = off");
        s.ok("SELECT 'x" + secret + "'");
        assertThat(apps.events("exec_intent", "S-1-5-21-6-6-6-1").get(0).get("sql_normalised")).isNull();
        s.ok("SET standard_conforming_strings = on");
    }

    @Test
    void noAuditMeansNoExecution() throws Exception {
        Session s = session(app, "yuri", "2");
        String m = marker();
        apps.auditDown(true);
        try {
            HttpResponse<String> r = s.run("SELECT '" + m + "'");
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("AUDIT_UNAVAILABLE");
        } finally {
            apps.auditDown(false);
        }
        // The audit pool needs a moment to reconnect; until then every attempt is refused (503) and runs nothing.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> s.run("SELECT '" + m + "_after'").statusCode() == 200);
        await().atMost(Duration.ofSeconds(10)).until(() -> serverLog().contains(m + "_after"));
        assertThat(serverLog()).doesNotContain("'" + m + "'");
    }

    @Test
    void anOutcomeThatCantBeWrittenIsSpooled() throws Exception {
        Session s = session(app, "zoe", "3");
        long before = app.audit().spooled();
        CompletableFuture<JsonNode> running = CompletableFuture.supplyAsync(() -> {
            try {
                return s.ok("SELECT pg_sleep(1.5)");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> activeQueries("zoe") > 0);
        apps.auditDown(true);
        try {
            assertThat(running.get(20, TimeUnit.SECONDS).path("kind").asText()).isEqualTo("rows");
            assertThat(app.audit().spooled()).isGreaterThan(before);
        } finally {
            apps.auditDown(false);
        }
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            app.audit().replay();
            return app.audit().spooled() == 0;
        });
    }

    @Test
    void theKillSwitchStopsExecution() throws Exception {
        Session s = session(off, "xena", "1");
        HttpResponse<String> r = s.run("SELECT 1");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("EXECUTION_DISABLED");
    }

    @Test
    void oversizedTextIsRejectedUnsent() throws Exception {
        Session s = session(app, "xena", "1");
        String huge = "SELECT '" + "y".repeat(1024 * 1024) + "'";
        HttpResponse<String> r = s.run(huge);
        assertThat(r.statusCode()).isEqualTo(413);
    }

    static int activeQueries(String role) throws Exception {
        try (Connection c = apps.pg().superuser("postgres");
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE usename = ? AND state = 'active' AND query LIKE '%pg_sleep%'")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
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
