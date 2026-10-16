package pgweb.metadata;

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

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The object browser on a real PostgreSQL 18 cluster (plan Phase 10): privilege filtering, hostile identifiers,
 * relation details, a busy or missing companion, ownership, committed-only visibility and audit.
 */
class MetadataIT {

    /** A table whose name is an injection payload: browsing it must never execute anything. */
    static final String PAYLOAD = "a\"; DROP TABLE shop.orders; --";
    static final String WEIRD_SCHEMA = "we\"ird / sch";
    static final String UNICODE = "表 名";

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
            for (String role : List.of("mia", "nik")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE ROLE owner_role NOLOGIN");
            s.execute("CREATE DATABASE meta_db OWNER owner_role");
            s.execute("GRANT CONNECT ON DATABASE meta_db TO mia, nik");
        }
        try (Connection c = apps.pg().superuser("meta_db"); Statement s = c.createStatement()) {
            for (String sql : List.of(
                    "CREATE SCHEMA shop AUTHORIZATION owner_role",
                    "CREATE SCHEMA hidden AUTHORIZATION owner_role",
                    "CREATE SCHEMA \"we\"\"ird / sch\" AUTHORIZATION owner_role",
                    "CREATE TABLE shop.customers (id int PRIMARY KEY)",
                    "CREATE TABLE shop.orders (id serial PRIMARY KEY, customer text NOT NULL DEFAULT 'walk-in', "
                            + "total numeric(10,2) CHECK (total >= 0), customer_id int REFERENCES shop.customers(id), "
                            + "code text UNIQUE, created timestamptz GENERATED ALWAYS AS ('2026-01-01'::timestamptz) STORED)",
                    "CREATE INDEX orders_customer_idx ON shop.orders (customer)",
                    "COMMENT ON TABLE shop.orders IS 'Customer orders'",
                    "CREATE TABLE shop.secret (x int)",
                    "CREATE VIEW shop.v_orders AS SELECT id, total FROM shop.orders",
                    "CREATE MATERIALIZED VIEW shop.mv AS SELECT count(*) AS n FROM shop.orders",
                    "CREATE SEQUENCE shop.seq_open",
                    "CREATE SEQUENCE shop.seq_closed",
                    "CREATE FUNCTION shop.f(a integer) RETURNS integer LANGUAGE sql AS 'SELECT a'",
                    "CREATE FUNCTION shop.g(t text) RETURNS text LANGUAGE sql AS 'SELECT t'",
                    "CREATE PROCEDURE shop.p() LANGUAGE sql AS 'SELECT 1'",
                    "CREATE TABLE hidden.h (x int)",
                    "CREATE TABLE \"we\"\"ird / sch\".\"a\"\"; DROP TABLE shop.orders; --\" (\"col \"\"quoted\"\"\" int)",
                    "CREATE TABLE \"we\"\"ird / sch\".\"表 名\" (\"列\" text)",
                    "ALTER TABLE shop.orders OWNER TO owner_role",
                    "REVOKE ALL ON FUNCTION shop.f(integer), shop.g(text) FROM PUBLIC",
                    "GRANT USAGE ON SCHEMA shop, \"we\"\"ird / sch\" TO mia, nik",
                    "GRANT SELECT ON shop.orders, shop.customers, shop.v_orders, shop.mv TO mia, nik",
                    "GRANT SELECT ON ALL TABLES IN SCHEMA \"we\"\"ird / sch\" TO mia",
                    "GRANT USAGE ON SEQUENCE shop.seq_open TO mia",
                    "GRANT EXECUTE ON FUNCTION shop.f(integer) TO mia",
                    "GRANT CREATE ON SCHEMA shop TO mia")) {
                s.execute(sql);
            }
        }
        app = apps.app(p -> p.setProperty("execution.enabled", "true"), new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static final List<String[]> OPENED = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (String[] o : OPENED) {
            MockClient c = CLIENTS.get(o[0]);
            c.send("DELETE", "/api/db-sessions/" + o[1], null, Map.of("Origin", c.origin(), "X-CSRF-Token", c.csrf()));
        }
        OPENED.clear();
    }

    static final Map<String, MockClient> CLIENTS = new java.util.concurrent.ConcurrentHashMap<>();

    static MockClient client(String role, String rid) throws Exception {
        MockClient c = CLIENTS.get(role);
        if (c == null) {
            c = MockClient.user(app.port(), "TEST\\" + role, "S-1-5-21-7-7-7-" + rid);
            c.me();
            CLIENTS.put(role, c);
        }
        return c;
    }

    static String open(String role, String rid) throws Exception {
        MockClient c = client(role, rid);
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"meta_db\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = Json.mapper().readTree(r.body()).path("id").asText();
        OPENED.add(new String[] {role, id});
        return id;
    }

    static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static HttpResponse<String> get(String role, String path) throws Exception {
        return CLIENTS.get(role).get(path);
    }

    static JsonNode ok(String role, String path) throws Exception {
        HttpResponse<String> r = get(role, path);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return Json.mapper().readTree(r.body());
    }

    static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path("name").asText()));
        return out;
    }

    // ------------------------------------------------------------------

    @Test
    void schemasAreFilteredByPrivilegeUnlessAllIsAsked() throws Exception {
        String id = open("mia", "1");
        JsonNode usable = ok("mia", "/api/metadata/" + id + "/schemas");
        assertThat(names(usable.path("schemas"))).contains("shop", WEIRD_SCHEMA, "public").doesNotContain("hidden", "pg_catalog", "information_schema");
        assertThat(usable.path("loadedAt").asText()).isNotEmpty();
        JsonNode all = ok("mia", "/api/metadata/" + id + "/schemas?all=true");
        assertThat(names(all.path("schemas"))).contains("hidden", "pg_catalog", "information_schema");
        all.path("schemas").forEach(s -> {
            if (s.path("name").asText().equals("hidden")) {
                assertThat(s.path("accessible").asBoolean()).isFalse();
            }
        });
        assertThat(names(all.path("schemas"))).noneMatch(n -> n.startsWith("pg_toast") || n.startsWith("pg_temp_"));
    }

    @Test
    void objectsAreListedWithKindsSignaturesAndPrivileges() throws Exception {
        String id = open("mia", "1");
        JsonNode objects = ok("mia", "/api/metadata/" + id + "/objects?schema=shop").path("objects");
        Map<String, String> kinds = new java.util.HashMap<>();
        objects.forEach(o -> kinds.put(o.path("name").asText(), o.path("kind").asText()));
        assertThat(kinds).containsEntry("orders", "table").containsEntry("v_orders", "view").containsEntry("mv", "materialized_view")
                .containsEntry("seq_open", "sequence").containsEntry("f", "function").containsEntry("p", "procedure")
                .doesNotContainKeys("secret", "seq_closed", "g");
        objects.forEach(o -> {
            assertThat(o.path("accessible").asBoolean()).isTrue();
            if (o.path("name").asText().equals("f")) {
                assertThat(o.path("arguments").asText()).isEqualTo("a integer");
                assertThat(o.path("returns").asText()).isEqualTo("integer");
            }
            if (o.path("name").asText().equals("orders")) {
                assertThat(o.path("comment").asText()).isEqualTo("Customer orders");
            }
        });
        JsonNode all = ok("mia", "/api/metadata/" + id + "/objects?schema=shop&all=true").path("objects");
        List<String> hidden = new ArrayList<>();
        all.forEach(o -> {
            if (!o.path("accessible").asBoolean()) {
                hidden.add(o.path("name").asText());
            }
        });
        // orders_id_seq: the serial column's sequence, on which mia has no privilege either.
        assertThat(hidden).containsExactlyInAnyOrder("secret", "seq_closed", "orders_id_seq", "g");
        // Another role sees what its own privileges allow.
        String other = open("nik", "2");
        JsonNode nik = ok("nik", "/api/metadata/" + other + "/objects?schema=shop").path("objects");
        assertThat(names(nik)).contains("orders").doesNotContain("f", "seq_open");
    }

    @Test
    void aRelationShowsColumnsIndexesAndConstraints() throws Exception {
        String id = open("mia", "1");
        JsonNode r = ok("mia", "/api/metadata/" + id + "/relation?schema=shop&name=orders");
        assertThat(r.path("kind").asText()).isEqualTo("table");
        Map<String, JsonNode> cols = new java.util.HashMap<>();
        r.path("columns").forEach(c -> cols.put(c.path("name").asText(), c));
        assertThat(names(r.path("columns"))).containsExactly("id", "customer", "total", "customer_id", "code", "created");
        assertThat(cols.get("id").path("type").asText()).isEqualTo("integer");
        assertThat(cols.get("id").path("default").asText()).startsWith("nextval(");
        assertThat(cols.get("customer").path("nullable").asBoolean()).isFalse();
        assertThat(cols.get("customer").path("default").asText()).isEqualTo("'walk-in'::text");
        assertThat(cols.get("total").path("type").asText()).isEqualTo("numeric(10,2)");
        assertThat(cols.get("total").path("nullable").asBoolean()).isTrue();
        assertThat(cols.get("created").path("generated").asText()).isEqualTo("s");
        Map<String, String> constraints = new java.util.HashMap<>();
        r.path("constraints").forEach(c -> constraints.put(c.path("type").asText(), c.path("definition").asText()));
        assertThat(constraints).containsEntry("primary_key", "PRIMARY KEY (id)").containsEntry("check", "CHECK (total >= 0::numeric)")
                .containsEntry("unique", "UNIQUE (code)").containsEntry("foreign_key", "FOREIGN KEY (customer_id) REFERENCES shop.customers(id)");
        assertThat(names(r.path("indexes"))).contains("orders_pkey", "orders_customer_idx", "orders_code_key");
        r.path("indexes").forEach(i -> {
            if (i.path("name").asText().equals("orders_pkey")) {
                assertThat(i.path("primary").asBoolean()).isTrue();
            }
        });
        assertThat(get("mia", "/api/metadata/" + id + "/relation?schema=shop&name=nope").statusCode()).isEqualTo(404);
    }

    @Test
    void hostileIdentifiersAreOnlyEverParameters() throws Exception {
        String id = open("mia", "1");
        JsonNode objects = ok("mia", "/api/metadata/" + id + "/objects?schema=" + q(WEIRD_SCHEMA)).path("objects");
        assertThat(names(objects)).containsExactlyInAnyOrder(PAYLOAD, UNICODE);
        JsonNode payload = ok("mia", "/api/metadata/" + id + "/relation?schema=" + q(WEIRD_SCHEMA) + "&name=" + q(PAYLOAD));
        assertThat(names(payload.path("columns"))).containsExactly("col \"quoted\"");
        JsonNode uni = ok("mia", "/api/metadata/" + id + "/relation?schema=" + q(WEIRD_SCHEMA) + "&name=" + q(UNICODE));
        assertThat(names(uni.path("columns"))).containsExactly("列");
        // The payload never ran: the table it names is still there.
        try (Connection c = apps.pg().superuser("meta_db"); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT to_regclass('shop.orders') IS NOT NULL")) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
        }
        for (String bad : List.of("x" + "\u0000", "n".repeat(64), "")) {
            assertThat(get("mia", "/api/metadata/" + id + "/objects?schema=" + q(bad)).statusCode()).as("[" + bad.length() + "]").isEqualTo(400);
        }
        assertThat(get("mia", "/api/metadata/" + id + "/objects").statusCode()).isEqualTo(400);
        assertThat(get("mia", "/api/metadata/" + id + "/tables").statusCode()).isEqualTo(404);
        assertThat(get("mia", "/api/metadata/" + id).statusCode()).isEqualTo(404);
    }

    @Test
    void sessionsOfOthersAreNotFoundAndEveryBrowseIsAudited() throws Exception {
        String id = open("mia", "1");
        client("nik", "2");
        assertThat(get("nik", "/api/metadata/" + id + "/schemas").statusCode()).isEqualTo(404);
        assertThat(get("nik", "/api/metadata/nope/schemas").statusCode()).isEqualTo(404);
        ok("mia", "/api/metadata/" + id + "/objects?schema=shop");
        assertThat(apps.events("metadata_browse", "S-1-5-21-7-7-7-1")).anyMatch(e -> String.valueOf(e.get("details")).contains("\"objects\"")
                && String.valueOf(e.get("details")).contains("shop"));
    }

    @Test
    void theBrowserShowsCommittedStateOnly() throws Exception {
        String id = open("mia", "1");
        String t = "t_" + UUID.randomUUID().toString().substring(0, 8);
        MockClient c = CLIENTS.get("mia");
        for (String sql : List.of("BEGIN", "CREATE TABLE shop." + t + " (a int)")) {
            assertThat(c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql, "confirmed", true))).statusCode()).isEqualTo(200);
        }
        assertThat(names(ok("mia", "/api/metadata/" + id + "/objects?schema=shop").path("objects"))).doesNotContain(t);
        assertThat(c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", "COMMIT"))).statusCode()).isEqualTo(200);
        assertThat(names(ok("mia", "/api/metadata/" + id + "/objects?schema=shop").path("objects"))).contains(t);
    }

    @Test
    void aBusyOrMissingCompanionIsReportedNotWaitedOn() throws Exception {
        String id = open("mia", "1");
        int companionPid;
        try (Connection c = apps.pg().superuser("postgres");
             PreparedStatement ps = c.prepareStatement("SELECT pid FROM pg_stat_activity WHERE usename = 'mia' AND application_name = 'pgweb-companion'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                companionPid = rs.getInt(1);
            }
        }
        try (Connection lock = apps.pg().superuser("meta_db"); Statement st = lock.createStatement()) {
            lock.setAutoCommit(false);
            st.execute("LOCK TABLE pg_catalog.pg_class IN ACCESS EXCLUSIVE MODE");
            CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return get("mia", "/api/metadata/" + id + "/objects?schema=shop");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> waitsOnLock(companionPid));
            HttpResponse<String> second = get("mia", "/api/metadata/" + id + "/schemas");
            assertThat(second.statusCode()).isEqualTo(409);
            assertThat(second.body()).contains("COMPANION_BUSY");
            lock.rollback();
            assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        connector.failCompanion("nik", true);
        String degraded;
        try {
            degraded = open("nik", "2");
        } finally {
            connector.failCompanion("nik", false);
        }
        HttpResponse<String> none = get("nik", "/api/metadata/" + degraded + "/schemas");
        assertThat(none.statusCode()).isEqualTo(409);
        assertThat(none.body()).contains("METADATA_UNAVAILABLE");
    }

    static boolean waitsOnLock(int pid) throws Exception {
        try (Connection c = apps.pg().superuser("postgres"); PreparedStatement ps = c.prepareStatement("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?")) {
            ps.setInt(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "Lock".equals(rs.getString(1));
            }
        }
    }
}
