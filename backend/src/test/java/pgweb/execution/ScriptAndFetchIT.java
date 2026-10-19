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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Scripts (EXE-03, decided 2026-10-19) and further pages of a result (P9-3) on a real PostgreSQL 18 cluster: the
 * whole script is checked before anything runs, statements run one at a time and stop at the first failure, every
 * statement and every page has its own audit intent, cancel stops the rest, and a cursor exists only inside a
 * transaction without changing auto-commit behaviour.
 */
class ScriptAndFetchIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static PasswordTestConnector connector;
    static Application app;
    /** Execution switched off. */
    static Application off;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        connector = new PasswordTestConnector();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            for (String role : List.of("ada", "ben")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE script_db OWNER ada");
            s.execute("GRANT CONNECT ON DATABASE script_db TO ben");
        }
        try (Connection c = apps.pg().superuser("script_db"); Statement s = c.createStatement()) {
            s.execute("GRANT ALL ON SCHEMA public TO ada, ben");
        }
        app = apps.app(p -> {
            p.setProperty("execution.enabled", "true");
            p.setProperty("profile.external.statementTimeoutSeconds", "5");
            p.setProperty("limits.cancelGraceSeconds", "1");
            p.setProperty("limits.maxRows", "100");
            p.setProperty("limits.maxFetchedRows", "250");
            p.setProperty("limits.maxScriptStatements", "5");
        }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
        off = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
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
        HttpResponse<String> script(String sql, boolean confirmed) throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("sql", sql));
            if (confirmed) {
                body.put("confirmed", true);
            }
            return client.post("/api/db-sessions/" + id + "/script", Json.write(body));
        }

        JsonNode okScript(String sql) throws Exception {
            HttpResponse<String> r = script(sql, true);
            assertThat(r.statusCode()).as(sql + " -> " + r.body()).isEqualTo(200);
            return Json.mapper().readTree(r.body());
        }

        JsonNode ok(String sql) throws Exception {
            HttpResponse<String> r = client.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql, "confirmed", true)));
            assertThat(r.statusCode()).as(sql + " -> " + r.body()).isEqualTo(200);
            return Json.mapper().readTree(r.body());
        }

        HttpResponse<String> fetch(String executionId) throws Exception {
            return client.post("/api/db-sessions/" + id + "/fetch", Json.write(Map.of("executionId", executionId)));
        }

        JsonNode okFetch(String executionId) throws Exception {
            HttpResponse<String> r = fetch(executionId);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            return Json.mapper().readTree(r.body());
        }
    }

    static Session session(Application a, String role, String rid) throws Exception {
        MockClient c = MockClient.user(a.port(), "TEST\\" + role, "S-1-5-21-8-8-8-" + rid);
        c.me();
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"script_db\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        Session s = new Session(c, Json.mapper().readTree(r.body()).path("id").asText());
        OPENED.add(s);
        return s;
    }

    static String serverLog() throws Exception {
        return Files.readString(apps.pg().root().resolve("data").resolve("log").resolve("pg.log"), StandardCharsets.UTF_8);
    }

    static String name() {
        return "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static long count(String table) throws Exception {
        try (Connection c = apps.pg().superuser("script_db"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM public." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** True while one of the role's backends is running pg_sleep. */
    static boolean sleeping(String role) throws Exception {
        try (Connection c = apps.pg().superuser("postgres"); java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE usename = ? AND state = 'active' AND query LIKE '%pg_sleep%'")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    static List<String> column(JsonNode result) {
        List<String> out = new ArrayList<>();
        result.path("rows").forEach(r -> out.add(r.get(0).asText()));
        return out;
    }

    static List<String> range(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(Integer.toString(i));
        }
        return out;
    }

    // ---- scripts -------------------------------------------------------

    @Test
    void aScriptRunsItsStatementsInOrderEachWithItsOwnAuditIntent() throws Exception {
        Session s = session(app, "ada", "1");
        String t = name();
        String sql = "CREATE TABLE " + t + " (a int);\nINSERT INTO " + t + " VALUES (1), (2);\n-- the rows\nSELECT a FROM " + t + " ORDER BY a";
        JsonNode doc = s.okScript(sql);
        assertThat(doc.path("kind").asText()).isEqualTo("script");
        assertThat(doc.path("total").asInt()).isEqualTo(3);
        assertThat(doc.path("completed").asInt()).isEqualTo(3);
        assertThat(doc.has("stopReason")).isFalse();
        assertThat(doc.path("transaction").asText()).isEqualTo("IDLE");
        JsonNode st = doc.path("statements");
        assertThat(st.get(0).path("statementClass").asText()).isEqualTo("ddl");
        assertThat(st.get(1).path("results").get(0).path("rowCount").asInt()).isEqualTo(2);
        assertThat(column(st.get(2).path("results").get(0))).containsExactly("1", "2");
        for (int i = 0; i < 3; i++) {
            // Each statement's place in the editor text, so the page can point at it.
            int offset = st.get(i).path("offset").asInt();
            assertThat(sql.substring(offset, offset + st.get(i).path("length").asInt())).contains(List.of("CREATE", "INSERT", "SELECT").get(i));
        }

        String scriptId = doc.path("scriptId").asText();
        List<Map<String, Object>> intents = apps.events("exec_intent", "S-1-5-21-8-8-8-1");
        List<Map<String, Object>> mine = intents.stream().filter(e -> String.valueOf(e.get("details")).contains(scriptId)).toList();
        assertThat(mine).hasSize(3);
        for (int i = 0; i < 3; i++) {
            Map<String, Object> intent = mine.get(2 - i); // newest first
            assertThat(String.valueOf(intent.get("details"))).contains("\"statementIndex\": " + i).contains("\"statements\": 3");
            String executionId = String.valueOf(intent.get("execution_id"));
            assertThat(executionId).isEqualTo(st.get(i).path("executionId").asText());
            Map<String, Object> outcome = apps.events("exec_outcome", "S-1-5-21-8-8-8-1").stream()
                    .filter(e -> executionId.equals(String.valueOf(e.get("intent_id")))).findFirst().orElseThrow();
            assertThat(((Number) outcome.get("seq")).longValue()).isGreaterThan(((Number) intent.get("seq")).longValue());
            if (i < 2) {
                // The next statement's intent is written only after this statement ended.
                assertThat(((Number) mine.get(1 - i).get("seq")).longValue()).isGreaterThan(((Number) outcome.get("seq")).longValue());
            }
        }
        Map<String, Object> summary = apps.events("script_outcome", "S-1-5-21-8-8-8-1").get(0);
        assertThat(summary.get("outcome")).isEqualTo("success");
        assertThat(summary.get("sql_hmac")).isEqualTo(app.audit().sqlHmac(sql));
        assertThat(String.valueOf(summary.get("details"))).contains(scriptId).contains("\"completed\": 3");
    }

    @Test
    void aScriptStopsAtTheFirstErrorAndNothingAfterItReachesTheServer() throws Exception {
        Session s = session(app, "ada", "1");
        String t = name();
        s.ok("CREATE TABLE " + t + " (a text)");
        String marker = "mk_" + UUID.randomUUID().toString().replace("-", "");
        JsonNode doc = s.okScript("INSERT INTO " + t + " VALUES ('first'); SELECT 1 / 0; INSERT INTO " + t + " VALUES ('" + marker + "')");
        assertThat(doc.path("stopReason").asText()).isEqualTo("error");
        assertThat(doc.path("stoppedAt").asInt()).isEqualTo(1);
        assertThat(doc.path("completed").asInt()).isEqualTo(1);
        JsonNode st = doc.path("statements");
        assertThat(st.get(1).path("kind").asText()).isEqualTo("error");
        assertThat(st.get(1).path("error").path("sqlstate").asText()).isEqualTo("22012");
        assertThat(st.get(2).path("kind").asText()).isEqualTo("not_run");
        assertThat(st.get(2).has("executionId")).isFalse();
        // Auto-commit: the first statement committed on its own, as it would have if run alone.
        assertThat(count(t)).isEqualTo(1);
        assertThat(serverLog()).doesNotContain(marker);
        assertThat(apps.events("script_outcome", "S-1-5-21-8-8-8-1").get(0).get("outcome")).isEqualTo("error");
    }

    @Test
    void insideATransactionTheUserDecidesWhatHappensAfterAFailure() throws Exception {
        Session s = session(app, "ben", "2");
        String t = name();
        s.ok("CREATE TABLE " + t + " (a int PRIMARY KEY)");
        s.ok("BEGIN");
        JsonNode doc = s.okScript("INSERT INTO " + t + " VALUES (1); INSERT INTO " + t + " VALUES (1); INSERT INTO " + t + " VALUES (2)");
        assertThat(doc.path("stopReason").asText()).isEqualTo("error");
        assertThat(doc.path("statements").get(1).path("error").path("sqlstate").asText()).isEqualTo("23505");
        assertThat(doc.path("transaction").asText()).isEqualTo("FAILED");
        assertThat(count(t)).isZero(); // nothing committed for the user
        assertThat(s.ok("ROLLBACK").path("transaction").asText()).isEqualTo("IDLE");
        assertThat(count(t)).isZero();
        // A script with its own BEGIN and COMMIT behaves like the same statements run one by one.
        JsonNode atomic = s.okScript("BEGIN; INSERT INTO " + t + " VALUES (5); INSERT INTO " + t + " VALUES (6); COMMIT");
        assertThat(atomic.path("completed").asInt()).isEqualTo(4);
        assertThat(atomic.path("transaction").asText()).isEqualTo("IDLE");
        assertThat(count(t)).isEqualTo(2);
    }

    @Test
    void theWholeScriptIsCheckedBeforeAnythingRuns() throws Exception {
        Session s = session(app, "ada", "1");
        String marker = "mk_" + UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> copy = s.script("SELECT '" + marker + "a';\nCOPY x FROM STDIN", true);
        assertThat(copy.statusCode()).isEqualTo(400);
        JsonNode err = Json.mapper().readTree(copy.body()).path("error");
        assertThat(err.path("code").asText()).isEqualTo("UNSUPPORTED");
        assertThat(err.path("statementIndex").asInt()).isEqualTo(1);
        assertThat(err.path("position").asInt()).isEqualTo(marker.length() + 13); // the C of COPY

        HttpResponse<String> syntax = s.script("SELECT '" + marker + "b'; SELEC 2", true);
        assertThat(syntax.statusCode()).isEqualTo(400);
        assertThat(syntax.body()).contains("SYNTAX_ERROR");

        HttpResponse<String> many = s.script("SELECT '" + marker + "c'; SELECT 2; SELECT 3; SELECT 4; SELECT 5; SELECT 6", true);
        assertThat(many.statusCode()).isEqualTo(400);
        assertThat(many.body()).contains("TOO_MANY_STATEMENTS").contains("at most 5");

        assertThat(s.script("  ;; -- nothing", true).body()).contains("EMPTY_STATEMENT");
        assertThat(serverLog()).doesNotContain(marker);
        Map<String, Object> rejected = apps.events("exec_rejected", "S-1-5-21-8-8-8-1").stream()
                .filter(e -> "unsupported".equals(e.get("parse_status"))).findFirst().orElseThrow();
        assertThat(rejected.get("statement_class")).isEqualTo("script");
        assertThat(String.valueOf(rejected.get("details"))).contains("\"statementIndex\": 1");
        assertThat(String.valueOf(rejected.get("details"))).doesNotContain(marker);
    }

    @Test
    void oneConfirmationCoversEveryDestructiveStatementInTheScript() throws Exception {
        Session s = session(app, "ada", "1");
        String t = name();
        HttpResponse<String> r = s.script("CREATE TABLE " + t + " (a int); DELETE FROM " + t + "; DROP TABLE " + t, false);
        assertThat(r.statusCode()).isEqualTo(409);
        JsonNode err = Json.mapper().readTree(r.body()).path("error");
        assertThat(err.path("code").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(err.path("statements").asInt()).isEqualTo(3);
        assertThat(err.path("statementClass").asText()).isEqualTo("script");
        List<String> reasons = new ArrayList<>();
        err.path("reasons").forEach(x -> reasons.add(x.asText()));
        assertThat(reasons).containsExactly("ddl", "no_where", "drop");
        assertThat(s.script("CREATE TABLE " + t + " (a int); DELETE FROM " + t + "; DROP TABLE " + t, true).statusCode()).isEqualTo(200);
        // A script with nothing destructive needs no confirmation.
        assertThat(s.script("SELECT 1; SELECT 2", false).statusCode()).isEqualTo(200);
    }

    @Test
    void cancelStopsTheRunningStatementAndTheRestNeverStarts() throws Exception {
        Session s = session(app, "ben", "2");
        String marker = "mk_" + UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<JsonNode> running = CompletableFuture.supplyAsync(() -> {
            try {
                return s.okScript("SELECT 1; SELECT pg_sleep(4); SELECT '" + marker + "'");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            JsonNode a = Json.mapper().readTree(s.client().get("/api/db-sessions/" + s.id() + "/activity").body());
            return a.path("scriptStatement").asInt() == 2 && a.path("scriptStatements").asInt() == 3 && sleeping("ben");
        });
        HttpResponse<String> c = s.client().post("/api/db-sessions/" + s.id() + "/cancel", "{}");
        assertThat(c.statusCode()).as(c.body()).isEqualTo(200);
        JsonNode doc = running.get(20, TimeUnit.SECONDS);
        assertThat(doc.path("stopReason").asText()).isEqualTo("cancelled");
        assertThat(doc.path("statements").get(1).path("kind").asText()).isEqualTo("cancelled");
        assertThat(doc.path("statements").get(2).path("kind").asText()).isEqualTo("not_run");
        assertThat(serverLog()).doesNotContain(marker);
        assertThat(apps.events("cancel_requested", "S-1-5-21-8-8-8-2").get(0).get("details").toString()).contains(doc.path("scriptId").asText());
        assertThat(s.client().post("/api/db-sessions/" + s.id() + "/cancel", "{}").statusCode()).isEqualTo(409);
    }

    @Test
    void changingHowStringsAreReadStopsTheScript() throws Exception {
        Session s = session(app, "ada", "1");
        String marker = "mk_" + UUID.randomUUID().toString().replace("-", "");
        // The statement boundaries were found with standard_conforming_strings on; after it changes they can't be trusted.
        JsonNode doc = s.okScript("SET standard_conforming_strings = off; SELECT '" + marker + "'");
        assertThat(doc.path("stopReason").asText()).isEqualTo("settings_changed");
        assertThat(doc.path("stoppedAt").asInt()).isEqualTo(1);
        assertThat(doc.path("statements").get(1).path("kind").asText()).isEqualTo("not_run");
        assertThat(serverLog()).doesNotContain(marker);
        s.ok("SET standard_conforming_strings = on");
    }

    @Test
    void withoutAuditTheScriptStopsBeforeTheStatementThatCouldNotBeRecorded() throws Exception {
        Session s = session(app, "ben", "2");
        String marker = "mk_" + UUID.randomUUID().toString().replace("-", "");
        apps.auditDown(true);
        JsonNode doc;
        try {
            doc = s.okScript("SELECT '" + marker + "'; SELECT 2");
        } finally {
            apps.auditDown(false);
        }
        assertThat(doc.path("stopReason").asText()).isEqualTo("audit_unavailable");
        assertThat(doc.path("completed").asInt()).isZero();
        assertThat(doc.path("statements").get(0).path("error").path("code").asText()).isEqualTo("AUDIT_UNAVAILABLE");
        assertThat(doc.path("statements").get(1).path("kind").asText()).isEqualTo("not_run");
        assertThat(serverLog()).doesNotContain(marker);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            app.audit().replay();
            return app.audit().spooled() == 0;
        });
    }

    @Test
    void theKillSwitchStopsScriptsAndFetches() throws Exception {
        Session s = session(off, "ada", "1");
        HttpResponse<String> r = s.script("SELECT 1", true);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("EXECUTION_DISABLED");
        assertThat(s.fetch(UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
    }

    // ---- further pages (P9-3) ----------------------------------------------

    @Test
    void insideATransactionTheRestOfALargeResultComesPageByPage() throws Exception {
        Session s = session(app, "ada", "1");
        s.ok("BEGIN READ ONLY");
        JsonNode first = s.ok("SELECT g FROM generate_series(1, 1000) g");
        JsonNode r0 = first.path("results").get(0);
        assertThat(column(r0)).isEqualTo(range(1, 100));
        assertThat(r0.path("more").asBoolean()).isTrue();
        String executionId = first.path("executionId").asText();

        JsonNode second = s.okFetch(executionId);
        assertThat(second.path("executionId").asText()).isEqualTo(executionId);
        assertThat(second.path("rowsBefore").asInt()).isEqualTo(100);
        assertThat(column(second.path("results").get(0))).isEqualTo(range(101, 200));
        assertThat(second.path("results").get(0).path("more").asBoolean()).isTrue();
        assertThat(second.path("transaction").asText()).isEqualTo("OPEN");

        // limits.maxFetchedRows = 250: the third page stops there and the cursor is closed.
        JsonNode third = s.okFetch(executionId);
        JsonNode r2 = third.path("results").get(0);
        assertThat(column(r2)).isEqualTo(range(201, 250));
        assertThat(r2.has("more")).isFalse();
        assertThat(r2.path("truncatedBy").asText()).isEqualTo("limit");
        HttpResponse<String> closed = s.fetch(executionId);
        assertThat(closed.statusCode()).isEqualTo(409);
        assertThat(closed.body()).contains("CURSOR_CLOSED");

        // Other tests use the same account: only this statement's pages count.
        List<Map<String, Object>> fetches = apps.events("exec_fetch", "S-1-5-21-8-8-8-1").stream()
                .filter(e -> executionId.equals(String.valueOf(e.get("intent_id")))).toList();
        assertThat(fetches).hasSize(2);
        List<String> fetchIds = fetches.stream().map(e -> String.valueOf(e.get("execution_id"))).toList();
        assertThat(fetchIds).contains(second.path("fetchId").asText(), third.path("fetchId").asText());
        List<Map<String, Object>> outcomes = apps.events("exec_fetch_outcome", "S-1-5-21-8-8-8-1").stream()
                .filter(e -> fetchIds.contains(String.valueOf(e.get("intent_id")))).toList();
        assertThat(outcomes).extracting(e -> ((Number) e.get("row_count")).longValue()).containsExactly(50L, 100L);
        assertThat(s.ok("COMMIT").path("transaction").asText()).isEqualTo("IDLE");
    }

    @Test
    void aResultThatEndsWithinTheFirstPagesLeavesNoCursor() throws Exception {
        Session s = session(app, "ada", "1");
        s.ok("BEGIN");
        JsonNode exact = s.ok("SELECT g FROM generate_series(1, 100) g");
        assertThat(exact.path("results").get(0).has("more")).isFalse();
        assertThat(exact.path("results").get(0).path("truncated").asBoolean()).isFalse();
        JsonNode two = s.ok("SELECT g FROM generate_series(1, 150) g");
        JsonNode last = s.okFetch(two.path("executionId").asText());
        assertThat(column(last.path("results").get(0))).isEqualTo(range(101, 150));
        assertThat(last.path("results").get(0).has("more")).isFalse();
        assertThat(last.path("results").get(0).path("truncated").asBoolean()).isFalse();
        assertThat(s.fetch(two.path("executionId").asText()).statusCode()).isEqualTo(409);
        s.ok("ROLLBACK");
    }

    @Test
    void theNextStatementOrTheEndOfTheTransactionClosesTheCursor() throws Exception {
        Session s = session(app, "ben", "2");
        s.ok("BEGIN");
        String a = s.ok("SELECT g FROM generate_series(1, 1000) g").path("executionId").asText();
        s.ok("SELECT 1");
        assertThat(s.fetch(a).body()).contains("CURSOR_CLOSED");
        String b = s.ok("SELECT g FROM generate_series(1, 1000) g").path("executionId").asText();
        s.ok("COMMIT");
        assertThat(s.fetch(b).body()).contains("CURSOR_CLOSED");
        assertThat(s.fetch("not-an-id").statusCode()).isEqualTo(409);
    }

    @Test
    void inAutoCommitNothingIsKeptAndAutoCommitBehaviourIsUnchanged() throws Exception {
        Session s = session(app, "ada", "1");
        JsonNode r = s.ok("SELECT g FROM generate_series(1, 1000) g");
        assertThat(r.path("results").get(0).path("truncatedBy").asText()).isEqualTo("rows");
        assertThat(r.path("results").get(0).has("more")).isFalse();
        assertThat(s.fetch(r.path("executionId").asText()).body()).contains("CURSOR_CLOSED");

        // After a transaction that used a cursor, statements commit on their own again and statements that can't run
        // inside a transaction still work: pgJDBC was not left in manual-commit mode.
        String t = name();
        s.ok("CREATE TABLE " + t + " (a int)");
        s.ok("BEGIN");
        s.ok("SELECT g FROM generate_series(1, 1000) g");
        s.ok("COMMIT");
        s.ok("INSERT INTO " + t + " VALUES (1)");
        assertThat(count(t)).isEqualTo(1);
        assertThat(s.ok("VACUUM " + t).path("kind").asText()).isEqualTo("command");
        // The same after a failed transaction rolled back.
        s.ok("BEGIN");
        s.ok("SELECT 1 / 0");
        s.ok("ROLLBACK");
        s.ok("INSERT INTO " + t + " VALUES (2)");
        assertThat(count(t)).isEqualTo(2);
    }

    @Test
    void fetchingIsFailClosedAndLimitedToTheSessionsOwner() throws Exception {
        Session s = session(app, "ada", "1");
        Session other = session(app, "ben", "2");
        s.ok("BEGIN");
        String id = s.ok("SELECT g FROM generate_series(1, 1000) g").path("executionId").asText();
        assertThat(other.client().post("/api/db-sessions/" + s.id() + "/fetch", Json.write(Map.of("executionId", id))).statusCode()).isEqualTo(404);
        apps.auditDown(true);
        try {
            HttpResponse<String> down = s.fetch(id);
            assertThat(down.statusCode()).isEqualTo(503);
            assertThat(down.body()).contains("AUDIT_UNAVAILABLE");
        } finally {
            apps.auditDown(false);
        }
        // Nothing was read while the audit was down: the next page still starts at row 101.
        List<HttpResponse<String>> after = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            HttpResponse<String> r = s.fetch(id);
            if (r.statusCode() == 503) {
                return false;
            }
            after.add(r);
            return true;
        });
        assertThat(after.get(0).statusCode()).as(after.get(0).body()).isEqualTo(200);
        assertThat(column(Json.mapper().readTree(after.get(0).body()).path("results").get(0))).isEqualTo(range(101, 200));
        s.ok("ROLLBACK");
        assertThat(s.client().post("/api/db-sessions/" + s.id() + "/fetch", "{\"executionId\":\"x\",\"extra\":1}").statusCode()).isEqualTo(400);
    }
}
