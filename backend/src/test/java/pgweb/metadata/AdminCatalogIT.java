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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Read-only administration (admin Phase 3) on a real PostgreSQL 18 cluster: database properties and grants,
 * activity and blocking, sizes, roles and memberships, object definitions (a table's reconstructed definition is run
 * again and must describe the same table), a relation's privileges, EXPLAIN confirmation, isolation and audit.
 */
class AdminCatalogIT {

    static final String WEIRD = "we\"ird / sch";
    static final String PAYLOAD = "a\"; DROP TABLE adm.orders; --";

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
            for (String role : List.of("ada", "bob")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE ROLE adm_owner NOLOGIN");
            s.execute("CREATE ROLE readers NOLOGIN");
            s.execute("CREATE ROLE lonely NOLOGIN NOINHERIT");
            s.execute("GRANT readers TO bob WITH ADMIN OPTION");
            s.execute("GRANT pg_read_all_stats TO bob"); // sees everyone's sessions, may signal none of them
            s.execute("CREATE DATABASE adm_db OWNER adm_owner");
            s.execute("GRANT CONNECT ON DATABASE adm_db TO ada, bob");
            s.execute("COMMENT ON DATABASE adm_db IS 'Administration tests'");
            s.execute("CREATE DATABASE adm_closed");
            s.execute("REVOKE CONNECT ON DATABASE adm_closed FROM PUBLIC");
        }
        try (Connection c = apps.pg().superuser("adm_db"); Statement s = c.createStatement()) {
            for (String sql : List.of(
                    "CREATE SCHEMA adm AUTHORIZATION adm_owner",
                    "CREATE SCHEMA \"we\"\"ird / sch\" AUTHORIZATION adm_owner",
                    "CREATE TABLE adm.customers (id int PRIMARY KEY)",
                    "INSERT INTO adm.customers VALUES (1), (2), (3)",
                    "CREATE TABLE adm.orders (id serial PRIMARY KEY, customer text NOT NULL DEFAULT 'walk-in', "
                            + "total numeric(10,2) CHECK (total >= 0), customer_id int REFERENCES adm.customers(id))",
                    "INSERT INTO adm.orders (customer, total) SELECT 'c' || g, g FROM generate_series(1, 500) g",
                    "CREATE INDEX orders_customer_idx ON adm.orders (customer)",
                    "COMMENT ON TABLE adm.orders IS 'Customer''s orders'",
                    "CREATE TABLE adm.secret (x int)",
                    "CREATE TABLE adm.pairs (b int, a int, v text, PRIMARY KEY (a, b))",
                    "CREATE VIEW adm.v_orders AS SELECT id, total FROM adm.orders WHERE total > 10",
                    "CREATE MATERIALIZED VIEW adm.mv AS SELECT count(*) AS n FROM adm.orders",
                    "CREATE SEQUENCE adm.seq AS integer INCREMENT BY 5 MINVALUE 10 MAXVALUE 1000 START WITH 20 CACHE 2 CYCLE",
                    "CREATE FUNCTION adm.f(a integer, b text DEFAULT 'x') RETURNS integer LANGUAGE sql AS 'SELECT a'",
                    "CREATE FUNCTION adm.f(a text) RETURNS text LANGUAGE sql AS 'SELECT a || ''!'''",
                    "CREATE PROCEDURE adm.p() LANGUAGE sql AS 'SELECT 1'",
                    "CREATE AGGREGATE adm.agg(int) (SFUNC = int4pl, STYPE = int)",
                    "CREATE TABLE adm.events (at date NOT NULL, what text) PARTITION BY RANGE (at)",
                    "CREATE TABLE adm.events_2026 PARTITION OF adm.events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')",
                    "CREATE TABLE adm.events_2027 PARTITION OF adm.events FOR VALUES FROM ('2027-01-01') TO ('2028-01-01') PARTITION BY LIST (what)",
                    "CREATE UNLOGGED TABLE adm.scratch (id int GENERATED BY DEFAULT AS IDENTITY, note text)",
                    "COMMENT ON VIEW adm.v_orders IS 'Big orders'",
                    "COMMENT ON MATERIALIZED VIEW adm.mv IS 'Order count'",
                    "CREATE FUNCTION adm.touch() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'",
                    "CREATE TRIGGER orders_touch BEFORE UPDATE ON adm.orders FOR EACH ROW EXECUTE FUNCTION adm.touch()",
                    "CREATE TABLE \"we\"\"ird / sch\".\"a\"\"; DROP TABLE adm.orders; --\" (\"col \"\"q\"\"\" int)",
                    "ALTER TABLE adm.orders OWNER TO adm_owner",
                    "GRANT USAGE ON SCHEMA adm, \"we\"\"ird / sch\" TO ada, bob",
                    "GRANT SELECT ON adm.orders, adm.customers, adm.v_orders, adm.mv, adm.events TO ada",
                    "GRANT DELETE ON adm.customers TO ada",
                    "GRANT SELECT ON adm.pairs TO ada",
                    "GRANT INSERT ON adm.orders TO ada WITH GRANT OPTION",
                    "GRANT SELECT ON adm.orders TO bob",
                    "GRANT SELECT ON ALL TABLES IN SCHEMA \"we\"\"ird / sch\" TO ada",
                    "GRANT CREATE ON SCHEMA adm TO ada",
                    "ANALYZE adm.orders")) {
                s.execute(sql);
            }
        }
        app = apps.app(p -> p.setProperty("execution.enabled", "true"), new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
        off = apps.app(p -> p.setProperty("execution.enabled", "false"), new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static final List<String[]> OPENED = new CopyOnWriteArrayList<>();
    static final Map<String, MockClient> CLIENTS = new ConcurrentHashMap<>();

    @AfterEach
    void close() throws Exception {
        for (String[] o : OPENED) {
            MockClient c = CLIENTS.get(o[0]);
            c.send("DELETE", "/api/db-sessions/" + o[1], null, Map.of("Origin", c.origin(), "X-CSRF-Token", c.csrf()));
        }
        OPENED.clear();
    }

    static MockClient client(String role, String rid) throws Exception {
        MockClient c = CLIENTS.get(role);
        if (c == null) {
            c = MockClient.user(app.port(), "TEST\\" + role, "S-1-5-21-9-9-9-" + rid);
            c.me();
            CLIENTS.put(role, c);
        }
        return c;
    }

    static String open(String role, String rid) throws Exception {
        MockClient c = client(role, rid);
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"adm_db\"}");
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

    static String definition(String role, String id, String kind, String schema, String name) throws Exception {
        return ok(role, "/api/metadata/" + id + "/definition?kind=" + kind + "&schema=" + q(schema) + "&name=" + q(name)).path("definition").asText();
    }

    static List<String> texts(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    // ------------------------------------------------------------------

    @Test
    void databasePropertiesShowYourPrivilegesAndTheGrants() throws Exception {
        String id = open("ada", "1");
        JsonNode db = ok("ada", "/api/metadata/" + id + "/database").path("database");
        assertThat(db.path("name").asText()).isEqualTo("adm_db");
        assertThat(db.path("owner").asText()).isEqualTo("adm_owner");
        assertThat(db.path("currentRole").asText()).isEqualTo("ada");
        assertThat(db.path("encoding").asText()).isEqualTo("UTF8");
        assertThat(db.path("comment").asText()).isEqualTo("Administration tests");
        assertThat(db.path("sizeBytes").asLong()).isPositive();
        assertThat(db.path("canCreateSchemas").asBoolean()).isFalse();
        assertThat(db.path("canCreateTemp").asBoolean()).isTrue();
        assertThat(db.path("serverVersion").asText()).startsWith("18");
        List<String> grants = new ArrayList<>();
        db.path("grants").forEach(g -> grants.add(g.path("grantee").asText() + ":" + g.path("privilege").asText()));
        assertThat(grants).contains("ada:CONNECT", "bob:CONNECT", "PUBLIC:TEMPORARY", "adm_owner:CREATE");
    }

    @Test
    void activityShowsYourSessionsTheirBlockersAndTheLocks() throws Exception {
        String id = open("ada", "1");
        MockClient ada = CLIENTS.get("ada");
        try (Connection holder = apps.pg().superuser("adm_db"); Statement st = holder.createStatement()) {
            holder.setAutoCommit(false);
            st.execute("LOCK TABLE adm.customers IN ACCESS EXCLUSIVE MODE");
            int holderPid;
            try (ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                holderPid = rs.getInt(1);
            }
            CompletableFuture<HttpResponse<String>> blocked = CompletableFuture.supplyAsync(() -> {
                try {
                    return ada.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", "SELECT count(*) FROM adm.customers")));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            JsonNode[] seen = new JsonNode[1];
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                seen[0] = ok("ada", "/api/metadata/" + id + "/activity").path("activity");
                for (JsonNode s : seen[0].path("sessions")) {
                    if ("session".equals(s.path("mine").asText()) && s.has("blockedBy")) {
                        return true;
                    }
                }
                return false;
            });
            JsonNode activity = seen[0];
            JsonNode mine = null;
            JsonNode companion = null;
            JsonNode other = null;
            for (JsonNode s : activity.path("sessions")) {
                switch (s.path("mine").asText()) {
                    case "session" -> mine = s;
                    case "companion" -> companion = s;
                    default -> {
                        if (s.path("pid").asInt() == holderPid) {
                            other = s;
                        }
                    }
                }
            }
            assertThat(mine).isNotNull();
            assertThat(mine.path("visible").asBoolean()).isTrue();
            assertThat(mine.path("query").asText()).contains("adm.customers");
            assertThat(mine.path("waitEventType").asText()).isEqualTo("Lock");
            assertThat(mine.path("blockedBy").size()).isEqualTo(1);
            assertThat(mine.path("blockedBy").get(0).asInt()).isEqualTo(holderPid);
            assertThat(companion).isNotNull();
            // Another role's session: PostgreSQL hides its details from ada.
            assertThat(other).isNotNull();
            assertThat(other.path("visible").asBoolean()).isFalse();
            assertThat(other.has("query")).isFalse();
            assertThat(other.has("state")).isFalse();
            boolean held = false;
            boolean waiting = false;
            for (JsonNode l : activity.path("locks")) {
                if (l.path("pid").asInt() == holderPid && "adm.customers".equals(l.path("relation").asText())) {
                    held = l.path("granted").asBoolean() && "AccessExclusiveLock".equals(l.path("mode").asText());
                }
                if (l.path("pid").asInt() == mine.path("pid").asInt() && !l.path("granted").asBoolean()) {
                    waiting = "adm.customers".equals(l.path("relation").asText());
                }
            }
            assertThat(held).as(activity.path("locks").toString()).isTrue();
            assertThat(waiting).as(activity.path("locks").toString()).isTrue();
            holder.rollback();
            assertThat(blocked.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(apps.events("metadata_browse", "S-1-5-21-9-9-9-1")).anyMatch(e -> String.valueOf(e.get("details")).contains("\"activity\""));
    }

    @Test
    void sizesCoverOnlyWhatYouMayUse() throws Exception {
        String id = open("ada", "1");
        JsonNode sizes = ok("ada", "/api/metadata/" + id + "/sizes").path("sizes");
        Map<String, JsonNode> dbs = new HashMap<>();
        sizes.path("databases").forEach(d -> dbs.put(d.path("name").asText(), d));
        assertThat(dbs.get("adm_db").path("sizeBytes").asLong()).isPositive();
        assertThat(dbs.get("adm_closed")).isNotNull();
        assertThat(dbs.get("adm_closed").has("sizeBytes")).isFalse();
        List<String> tables = new ArrayList<>();
        sizes.path("tables").forEach(t -> tables.add(t.path("schema").asText() + "." + t.path("name").asText()));
        assertThat(tables).contains("adm.orders", "adm.mv").doesNotContain("adm.secret");
        assertThat(tables.get(0)).isEqualTo("adm.orders"); // the largest first
        sizes.path("tables").forEach(t -> {
            if (t.path("name").asText().equals("orders")) {
                assertThat(t.path("totalBytes").asLong()).isGreaterThanOrEqualTo(t.path("tableBytes").asLong() + t.path("indexBytes").asLong());
                assertThat(t.path("estimatedRows").asLong()).isEqualTo(500);
            }
        });
    }

    @Test
    void rolesAndMembershipsAreShownWithoutPasswords() throws Exception {
        String id = open("bob", "2");
        HttpResponse<String> r = get("bob", "/api/metadata/" + id + "/roles");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).doesNotContainIgnoringCase("password").doesNotContain("********");
        JsonNode roles = Json.mapper().readTree(r.body()).path("roles");
        Map<String, JsonNode> byName = new HashMap<>();
        roles.path("roles").forEach(x -> byName.put(x.path("name").asText(), x));
        assertThat(byName).containsKeys("ada", "bob", "readers", "adm_owner").doesNotContainKey("pg_read_all_stats");
        assertThat(byName.get("bob").path("current").asBoolean()).isTrue();
        assertThat(byName.get("bob").path("attributes").toString()).contains("login");
        assertThat(byName.get("readers").path("member").asBoolean()).isTrue();
        assertThat(byName.get("lonely").path("attributes").toString()).isEqualTo("[\"noinherit\"]");
        assertThat(byName.get("adm_owner").path("member").asBoolean()).isFalse();
        assertThat(byName.values()).anyMatch(x -> x.path("attributes").toString().contains("superuser")); // the test cluster's own
        boolean found = false;
        for (JsonNode m : roles.path("memberships")) {
            if (m.path("role").asText().equals("readers") && m.path("member").asText().equals("bob")) {
                found = m.path("admin").asBoolean();
            }
        }
        assertThat(found).isTrue();
        JsonNode all = ok("bob", "/api/metadata/" + id + "/roles?all=true").path("roles");
        assertThat(texts(all.path("roles"), "name")).contains("pg_read_all_stats");
    }

    @Test
    void definitionsOfViewsRoutinesAndSequences() throws Exception {
        String id = open("ada", "1");
        String view = definition("ada", id, "view", "adm", "v_orders");
        assertThat(view).startsWith("CREATE OR REPLACE VIEW adm.v_orders AS\n").contains("FROM adm.orders").contains("total > 10")
                .endsWith("\nCOMMENT ON VIEW adm.v_orders IS 'Big orders';\n");
        String mv = definition("ada", id, "materialized_view", "adm", "mv");
        assertThat(mv).startsWith("CREATE MATERIALIZED VIEW adm.mv AS\n").contains("count(*)")
                .endsWith("\nCOMMENT ON MATERIALIZED VIEW adm.mv IS 'Order count';\n");
        String f = ok("ada", "/api/metadata/" + id + "/definition?kind=function&schema=adm&name=f&arguments=" + q("a integer, b text")).path("definition").asText();
        assertThat(f).startsWith("CREATE OR REPLACE FUNCTION adm.f(a integer, b text DEFAULT 'x'::text)").contains("SELECT a").endsWith(";\n");
        String overload = ok("ada", "/api/metadata/" + id + "/definition?kind=function&schema=adm&name=f&arguments=" + q("a text")).path("definition").asText();
        assertThat(overload).contains("RETURNS text");
        String p = ok("ada", "/api/metadata/" + id + "/definition?kind=procedure&schema=adm&name=p&arguments=").path("definition").asText();
        assertThat(p).startsWith("CREATE OR REPLACE PROCEDURE adm.p()");
        String seq = definition("ada", id, "sequence", "adm", "seq");
        assertThat(seq).isEqualTo("CREATE SEQUENCE adm.seq\n    AS integer\n    INCREMENT BY 5\n    MINVALUE 10\n    MAXVALUE 1000\n"
                + "    START WITH 20\n    CACHE 2\n    CYCLE;\n");
        assertThat(definition("ada", id, "sequence", "adm", "orders_id_seq")).contains("AS integer").endsWith("    NO CYCLE;\n");
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=sequence&schema=adm&name=nope").statusCode()).isEqualTo(404);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=table&schema=adm&name=nope").statusCode()).isEqualTo(404);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?schema=adm&name=v_orders").statusCode()).isEqualTo(400);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=function&schema=adm&name=f&arguments=" + q("a\u0000")).statusCode()).isEqualTo(400);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=function&schema=adm&name=f&arguments=" + "x".repeat(4001)).statusCode()).isEqualTo(400);
        // An aggregate has no CREATE FUNCTION form: not found, as is anything that doesn't exist.
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=function&schema=adm&name=agg&arguments=integer").statusCode()).isEqualTo(404);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=view&schema=adm&name=nope").statusCode()).isEqualTo(404);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=index&schema=adm&name=x").statusCode()).isEqualTo(400);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=function&schema=adm&name=f").statusCode()).isEqualTo(400);
        assertThat(get("ada", "/api/metadata/" + id + "/definition?kind=view&schema=adm").statusCode()).isEqualTo(400);
        client("bob", "2");
        assertThat(get("bob", "/api/metadata/" + id + "/definition?kind=view&schema=adm&name=v_orders").statusCode()).isEqualTo(404);
        assertThat(apps.events("metadata_browse", "S-1-5-21-9-9-9-1")).anyMatch(e -> String.valueOf(e.get("details")).contains("\"definition\""));
    }

    @Test
    void aTablesDefinitionRecreatesTheSameTable() throws Exception {
        String id = open("ada", "1");
        String orders = definition("ada", id, "table", "adm", "orders");
        assertThat(orders).contains("CREATE TABLE adm.orders (\n    id integer DEFAULT nextval('adm.orders_id_seq'::regclass) NOT NULL,")
                .contains("customer text DEFAULT 'walk-in'::text NOT NULL")
                .contains("CONSTRAINT orders_pkey PRIMARY KEY (id)")
                .contains("CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES adm.customers(id)")
                .contains("CREATE INDEX orders_customer_idx ON adm.orders USING btree (customer);")
                .contains("CREATE TRIGGER orders_touch BEFORE UPDATE ON adm.orders")
                .contains("COMMENT ON TABLE adm.orders IS 'Customer''s orders';")
                .doesNotContain("orders_pkey ON"); // the primary key's index comes with its constraint
        assertThat(definition("ada", id, "partitioned_table", "adm", "events")).contains("PARTITION BY RANGE (at);");
        assertThat(definition("ada", id, "table", "adm", "events_2027"))
                .contains("CREATE TABLE adm.events_2027 PARTITION OF adm.events\n    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01')\n    PARTITION BY LIST (what);");
        assertThat(definition("ada", id, "table", "adm", "scratch"))
                .contains("CREATE UNLOGGED TABLE adm.scratch (\n    id integer GENERATED BY DEFAULT AS IDENTITY NOT NULL,\n    note text\n);");
        assertThat(definition("ada", id, "table", "adm", "events_2026"))
                .contains("CREATE TABLE adm.events_2026 PARTITION OF adm.events\n    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');");
        assertThat(definition("ada", id, "table", WEIRD, PAYLOAD))
                .contains("CREATE TABLE \"we\"\"ird / sch\".\"a\"\"; DROP TABLE adm.orders; --\" (\n    \"col \"\"q\"\"\" integer\n)");

        // Round trip: a table with identity, generated, collated and constrained columns is dropped and created again
        // from its definition; the definition read afterwards is identical.
        String t = "rt_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = apps.pg().superuser("adm_db"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE adm." + t + " (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name text COLLATE \"C\" NOT NULL DEFAULT 'x', "
                    + "twice bigint GENERATED ALWAYS AS (id * 2) STORED, half bigint GENERATED ALWAYS AS (id / 2) VIRTUAL, "
                    + "code varchar(10) UNIQUE, CONSTRAINT positive CHECK (id > 0))");
            st.execute("CREATE UNIQUE INDEX " + t + "_lower ON adm." + t + " (lower(name)) WHERE code IS NOT NULL");
            st.execute("COMMENT ON TABLE adm." + t + " IS 'round trip'");
            st.execute("GRANT SELECT ON adm." + t + " TO ada");
        }
        String before = definition("ada", id, "table", "adm", t);
        try (Connection c = apps.pg().superuser("adm_db"); Statement st = c.createStatement()) {
            st.execute("DROP TABLE adm." + t);
            st.execute(before);
            st.execute("GRANT SELECT ON adm." + t + " TO ada");
        }
        assertThat(definition("ada", id, "table", "adm", t)).isEqualTo(before);
    }

    @Test
    void aRelationShowsItsOwnerGrantsAndYourPrivileges() throws Exception {
        String id = open("ada", "1");
        JsonNode r = ok("ada", "/api/metadata/" + id + "/relation?schema=adm&name=orders");
        assertThat(r.path("owner").asText()).isEqualTo("adm_owner");
        List<String> mine = new ArrayList<>();
        r.path("myPrivileges").forEach(p -> mine.add(p.asText()));
        assertThat(mine).containsExactlyInAnyOrder("SELECT", "INSERT");
        List<String> grants = new ArrayList<>();
        r.path("grants").forEach(g -> grants.add(g.path("grantee").asText() + ":" + g.path("privilege").asText() + ":" + g.path("grantable").asBoolean()));
        assertThat(grants).contains("ada:SELECT:false", "ada:INSERT:true", "bob:SELECT:false", "adm_owner:DELETE:false");
        String bob = open("bob", "2");
        List<String> bobs = new ArrayList<>();
        ok("bob", "/api/metadata/" + bob + "/relation?schema=adm&name=orders").path("myPrivileges").forEach(p -> bobs.add(p.asText()));
        assertThat(bobs).containsExactly("SELECT");
    }

    @Test
    void explainOnlyPlansButExplainAnalyzeAsksFirst() throws Exception {
        String id = open("ada", "1");
        MockClient ada = CLIENTS.get("ada");
        String path = "/api/db-sessions/" + id + "/execute";
        HttpResponse<String> plan = ada.post(path, Json.write(Map.of("sql", "EXPLAIN (FORMAT JSON) DELETE FROM adm.customers")));
        assertThat(plan.statusCode()).as(plan.body()).isEqualTo(200);
        JsonNode doc = Json.mapper().readTree(plan.body());
        assertThat(doc.path("statementClass").asText()).isEqualTo("explain");
        assertThat(doc.path("results").get(0).path("rows").get(0).get(0).asText()).contains("\"Operation\": \"Delete\"");
        HttpResponse<String> analyze = ada.post(path, Json.write(Map.of("sql", "EXPLAIN (ANALYZE, FORMAT JSON) SELECT count(*) FROM adm.orders")));
        assertThat(analyze.statusCode()).isEqualTo(409);
        assertThat(analyze.body()).contains("CONFIRMATION_REQUIRED").contains("explain_analyze");
        HttpResponse<String> confirmed = ada.post(path, Json.write(Map.of("sql", "EXPLAIN (ANALYZE, FORMAT JSON) SELECT count(*) FROM adm.orders", "confirmed", true)));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(confirmed.body()).contains("Execution Time");
        try (Connection c = apps.pg().superuser("adm_db"); PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM adm.customers");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(3); // EXPLAIN without ANALYZE deleted nothing
        }
    }

    @Test
    void everyAdminViewBelongsToTheSessionsOwner() throws Exception {
        String id = open("ada", "1");
        client("bob", "2");
        for (String view : List.of("database", "activity", "sizes", "roles")) {
            assertThat(get("bob", "/api/metadata/" + id + "/" + view).statusCode()).as(view).isEqualTo(404);
            assertThat(get("ada", "/api/metadata/" + id + "/" + view).statusCode()).as(view).isEqualTo(200);
        }
        try (Connection c = apps.pg().superuser("adm_db"); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT to_regclass('adm.orders') IS NOT NULL")) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
        }
    }

    // ------------------------------------------------------------------ admin Phase 4: signals, SQL text of others

    static String signal(int pid, String action, boolean confirmed) {
        return Json.write(Map.of("pid", pid, "action", action, "confirmed", confirmed));
    }

    /** The backend pid of a session's main connection, as its own activity view reports it. */
    static int mainPid(String role, String id) throws Exception {
        for (JsonNode s : ok(role, "/api/metadata/" + id + "/activity").path("activity").path("sessions")) {
            if ("session".equals(s.path("mine").asText())) {
                return s.path("pid").asInt();
            }
        }
        throw new AssertionError("no main session in the activity of " + id);
    }

    static JsonNode row(String role, String id, int pid) throws Exception {
        for (JsonNode s : ok(role, "/api/metadata/" + id + "/activity").path("activity").path("sessions")) {
            if (s.path("pid").asInt() == pid) {
                return s;
            }
        }
        return null;
    }

    @Test
    void anotherRoleSeesYourSessionButNotYourSql() throws Exception {
        String adaId = open("ada", "1");
        String bobId = open("bob", "2");
        HttpResponse<String> r = CLIENTS.get("ada").post("/api/db-sessions/" + adaId + "/execute",
                Json.write(Map.of("sql", "SELECT 'ada-secret-value' AS v")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        int adaPid = mainPid("ada", adaId);
        assertThat(row("ada", adaId, adaPid).path("query").asText()).contains("ada-secret-value");
        HttpResponse<String> seen = get("bob", "/api/metadata/" + bobId + "/activity");
        assertThat(seen.statusCode()).isEqualTo(200);
        assertThat(seen.body()).doesNotContain("ada-secret-value");
        JsonNode adas = row("bob", bobId, adaPid);
        assertThat(adas).isNotNull();
        assertThat(adas.path("visible").asBoolean()).isTrue(); // pg_read_all_stats: the state is shown
        assertThat(adas.path("state").asText()).isEqualTo("idle");
        assertThat(adas.has("query")).isFalse();
        assertThat(adas.path("queryHidden").asBoolean()).isTrue();
        assertThat(row("bob", bobId, mainPid("bob", bobId)).has("query")).isTrue(); // your own SQL stays visible
    }

    @Test
    void cancellingAndEndingAnotherSessionAskFirstAndAreAudited() throws Exception {
        String one = open("ada", "1");
        String two = open("ada", "1");
        MockClient ada = CLIENTS.get("ada");
        int onePid = mainPid("ada", one);
        int twoPid = mainPid("ada", two);
        String path = "/api/metadata/" + one + "/signal";
        HttpResponse<String> own = ada.post(path, signal(onePid, "cancel", true));
        assertThat(own.statusCode()).isEqualTo(400);
        assertThat(own.body()).contains("OWN_SESSION");
        assertThat(ada.post(path, "{\"pid\":" + twoPid + ",\"action\":\"kill\"}").body()).contains("INVALID_ACTION");
        assertThat(ada.post(path, "{\"pid\":0,\"action\":\"cancel\"}").body()).contains("INVALID_PID");
        assertThat(ada.post(path, "{\"action\":\"cancel\"}").body()).contains("INVALID_PID");
        assertThat(ada.post(path, signal(2_000_000_000, "cancel", true)).statusCode()).isEqualTo(404);
        assertThat(ada.post("/api/metadata/" + one + "/nothing", signal(twoPid, "cancel", true)).statusCode()).isEqualTo(404);

        CompletableFuture<HttpResponse<String>> running = CompletableFuture.supplyAsync(() -> {
            try {
                return ada.post("/api/db-sessions/" + two + "/execute", Json.write(Map.of("sql", "SELECT pg_sleep(30)")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            JsonNode r = row("ada", one, twoPid);
            return r != null && "active".equals(r.path("state").asText());
        });
        HttpResponse<String> ask = ada.post(path, signal(twoPid, "cancel", false));
        assertThat(ask.statusCode()).isEqualTo(409);
        assertThat(ask.body()).contains("CONFIRMATION_REQUIRED").contains("cancel_query");
        assertThat(running.isDone()).isFalse();
        HttpResponse<String> cancel = ada.post(path, signal(twoPid, "cancel", true));
        assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);
        assertThat(Json.mapper().readTree(cancel.body()).path("sent").asBoolean()).isTrue();
        assertThat(running.get(20, TimeUnit.SECONDS).body()).contains("57014"); // query_canceled

        HttpResponse<String> askEnd = ada.post(path, signal(twoPid, "terminate", false));
        assertThat(askEnd.statusCode()).isEqualTo(409);
        assertThat(askEnd.body()).contains("terminate_session");
        HttpResponse<String> end = ada.post(path, signal(twoPid, "terminate", true));
        assertThat(end.statusCode()).as(end.body()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> row("ada", one, twoPid) == null);

        List<Map<String, Object>> intents = apps.events("session_signal", "S-1-5-21-9-9-9-1");
        assertThat(intents).hasSizeGreaterThanOrEqualTo(2);
        assertThat(intents).allMatch(e -> String.valueOf(e.get("details")).contains("\"targetPid\": " + twoPid));
        List<Map<String, Object>> outcomes = apps.events("session_signal_outcome", "S-1-5-21-9-9-9-1");
        assertThat(outcomes).anyMatch(e -> "success".equals(e.get("outcome")) && String.valueOf(e.get("details")).contains("terminate"));
        assertThat(outcomes).anyMatch(e -> "success".equals(e.get("outcome")) && String.valueOf(e.get("details")).contains("cancel"));
    }

    @Test
    void signalsNeedThePrivilegesOfTheTargetAndAClientSession() throws Exception {
        String adaId = open("ada", "1");
        String bobId = open("bob", "2");
        MockClient bob = CLIENTS.get("bob");
        int adaPid = mainPid("ada", adaId);
        int bobPid = mainPid("bob", bobId);
        // bob sees ada's session (pg_read_all_stats) but has neither ada's privileges nor pg_signal_backend.
        HttpResponse<String> no = bob.post("/api/metadata/" + bobId + "/signal", signal(adaPid, "terminate", true));
        assertThat(no.statusCode()).isEqualTo(403);
        assertThat(no.body()).contains("NOT_PERMITTED");
        assertThat(row("ada", adaId, adaPid)).isNotNull();
        assertThat(apps.events("session_signal", "S-1-5-21-9-9-9-2")).anyMatch(e -> "refused".equals(e.get("outcome")));
        // ada can't even see bob's session.
        HttpResponse<String> hidden = CLIENTS.get("ada").post("/api/metadata/" + adaId + "/signal", signal(bobPid, "cancel", true));
        assertThat(hidden.statusCode()).isEqualTo(403);
        // Background processes are never signalled from here.
        int checkpointer;
        try (Connection c = apps.pg().superuser("postgres"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT pid FROM pg_stat_activity WHERE backend_type = 'checkpointer'")) {
            rs.next();
            checkpointer = rs.getInt(1);
        }
        HttpResponse<String> bg = bob.post("/api/metadata/" + bobId + "/signal", signal(checkpointer, "cancel", true));
        assertThat(bg.statusCode()).isEqualTo(400);
        assertThat(bg.body()).contains("NOT_CLIENT_SESSION");
    }

    @Test
    void signalsAreOffWhileExecutionIsSwitchedOff() throws Exception {
        MockClient c = MockClient.user(off.port(), "TEST\\ada", "S-1-5-21-9-9-9-1");
        c.me();
        HttpResponse<String> r = c.post("/api/metadata/whatever/signal", signal(123, "cancel", true));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("EXECUTION_DISABLED");
    }

    @Test
    void aRelationNamesItsPrimaryKeyInKeyOrder() throws Exception {
        String id = open("ada", "1");
        JsonNode pairs = ok("ada", "/api/metadata/" + id + "/relation?schema=adm&name=pairs");
        assertThat(pairs.path("primaryKey").toString()).isEqualTo("[\"a\",\"b\"]"); // key order, not column order
        assertThat(ok("ada", "/api/metadata/" + id + "/relation?schema=adm&name=orders").path("primaryKey").toString()).isEqualTo("[\"id\"]");
        assertThat(ok("ada", "/api/metadata/" + id + "/relation?schema=adm&name=v_orders").path("primaryKey").size()).isZero();
    }

    // ------------------------------------------------------------------ admin Phase 8: schema comparison snapshot

    static JsonNode named(JsonNode list, String name) {
        for (JsonNode n : list) {
            if (name.equals(n.path("name").asText())) {
                return n;
            }
        }
        return null;
    }

    static JsonNode named(JsonNode list, String table, String name) {
        for (JsonNode n : list) {
            if (table.equals(n.path("table").asText()) && name.equals(n.path("name").asText())) {
                return n;
            }
        }
        return null;
    }

    @Test
    void aSnapshotDescribesTheSchemaWithoutItsNameAndChangesNothing() throws Exception {
        String id = open("ada", "1");
        JsonNode body = ok("ada", "/api/metadata/" + id + "/snapshot?schema=adm");
        assertThat(body.path("database").asText()).isEqualTo("adm_db");
        assertThat(body.path("schema").asText()).isEqualTo("adm");
        JsonNode snap = body.path("snapshot");
        JsonNode rel = snap.path("relations");
        assertThat(texts(rel, "name")).contains("orders", "events", "events_2026", "scratch", "orders_id_seq", "v_orders", "mv", "seq");
        assertThat(named(rel, "orders").path("kind").asText()).isEqualTo("table");
        assertThat(named(rel, "events").path("kind").asText()).isEqualTo("partitioned_table");
        assertThat(named(rel, "events_2026").path("partition").asBoolean()).isTrue();
        assertThat(named(rel, "scratch").path("unlogged").asBoolean()).isTrue();
        assertThat(named(rel, "orders_id_seq").path("kind").asText()).isEqualTo("sequence"); // serial: a sequence of its own
        assertThat(named(rel, "scratch_id_seq")).as("identity sequences come with their column").isNull();
        // Names in the compared schema are written without it; the definitions mean the same in another schema.
        assertThat(named(rel, "v_orders").path("definition").asText()).contains("FROM orders").doesNotContain("adm.").doesNotEndWith(";");
        assertThat(named(rel, "mv").path("kind").asText()).isEqualTo("materialized_view");
        JsonNode cols = snap.path("columns");
        assertThat(named(cols, "orders", "id").path("default").asText()).isEqualTo("nextval('orders_id_seq'::regclass)");
        assertThat(named(cols, "orders", "customer").path("notNull").asBoolean()).isTrue();
        assertThat(named(cols, "orders", "total").path("type").asText()).isEqualTo("numeric(10,2)");
        assertThat(named(cols, "scratch", "id").path("identity").asText()).isEqualTo("d");
        assertThat(named(snap.path("constraints"), "orders", "orders_customer_id_fkey").path("definition").asText())
                .isEqualTo("FOREIGN KEY (customer_id) REFERENCES customers(id)");
        assertThat(named(snap.path("constraints"), "orders", "orders_pkey").path("type").asText()).isEqualTo("primary_key");
        assertThat(snap.path("constraints").toString()).doesNotContain("not_null");
        assertThat(named(snap.path("indexes"), "orders", "orders_customer_idx").path("definition").asText())
                .isEqualTo("CREATE INDEX orders_customer_idx ON orders USING btree (customer)");
        assertThat(snap.path("indexes").toString()).doesNotContain("orders_pkey"); // a constraint's index comes with it
        assertThat(named(snap.path("triggers"), "orders", "orders_touch").path("definition").asText())
                .contains(" ON orders ").contains("EXECUTE FUNCTION touch()");
        JsonNode seq = named(snap.path("sequences"), "seq");
        assertThat(seq.path("type").asText()).isEqualTo("integer");
        assertThat(seq.path("increment").asText()).isEqualTo("5");
        assertThat(seq.path("cycle").asBoolean()).isTrue();
        List<String> routines = new ArrayList<>();
        snap.path("routines").forEach(r -> routines.add(r.path("name").asText() + "(" + r.path("arguments").asText() + ")"));
        assertThat(routines).contains("f(a integer, b text)", "f(a text)", "p()", "touch()").doesNotContain("agg(integer)");
        assertThat(named(snap.path("routines"), "p").path("definition").asText()).startsWith("CREATE OR REPLACE PROCEDURE p()");
        assertThat(snap.path("routines").get(0).path("definition").asText()).startsWith("CREATE OR REPLACE FUNCTION f(a integer");
        // The search path was local to the snapshot's transaction: later reads see the session's own again.
        assertThat(definition("ada", id, "view", "adm", "v_orders")).contains("FROM adm.orders");
        // A quoted schema name is a value, never SQL.
        JsonNode weird = ok("ada", "/api/metadata/" + id + "/snapshot?schema=" + q(WEIRD)).path("snapshot");
        assertThat(named(weird.path("relations"), PAYLOAD)).isNotNull();
        assertThat(named(weird.path("columns"), PAYLOAD, "col \"q\"").path("type").asText()).isEqualTo("integer");
        assertThat(get("ada", "/api/metadata/" + id + "/snapshot?schema=nope").statusCode()).isEqualTo(404);
        assertThat(get("ada", "/api/metadata/" + id + "/snapshot").statusCode()).isEqualTo(400);
        assertThat(get("ada", "/api/metadata/" + id + "/snapshot?schema=" + "x".repeat(64)).statusCode()).isEqualTo(400);
        client("bob", "2");
        assertThat(get("bob", "/api/metadata/" + id + "/snapshot?schema=adm").statusCode()).isEqualTo(404);
        assertThat(apps.events("metadata_browse", "S-1-5-21-9-9-9-1")).anyMatch(e -> String.valueOf(e.get("details")).contains("\"snapshot\""));
        try (Connection c = apps.pg().superuser("adm_db"); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM adm.orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(500);
        }
    }

    @Test
    void functionHeadersLoseOnlyTheirSchema() throws Exception {
        assertThat(SchemaSnapshot.unqualifiedHeader("CREATE OR REPLACE FUNCTION \"we\"\"ird / sch\".\"F\"(a int)\n RETURNS int", false,
                "\"we\"\"ird / sch\".\"F\"", "\"F\"")).isEqualTo("CREATE OR REPLACE FUNCTION \"F\"(a int)\n RETURNS int");
        assertThat(SchemaSnapshot.unqualifiedHeader("CREATE OR REPLACE PROCEDURE s.p()\n", true, "s.p", "p")).isEqualTo("CREATE OR REPLACE PROCEDURE p()");
        assertThat(SchemaSnapshot.unqualifiedHeader("something else", true, "s.p", "p")).isEqualTo("something else");
        assertThat(SchemaSnapshot.unqualifiedTable("CREATE INDEX i ON ONLY \"S\".t USING btree (a)", "i", "\"S\".t", "t"))
                .isEqualTo("CREATE INDEX i ON ONLY t USING btree (a)");
        // An index whose name looks like the table clause: only the clause after the name changes.
        assertThat(SchemaSnapshot.unqualifiedTable("CREATE UNIQUE INDEX \" ON s.t USING \" ON s.t USING gin (a)", "\" ON s.t USING \"", "s.t", "t"))
                .isEqualTo("CREATE UNIQUE INDEX \" ON s.t USING \" ON t USING gin (a)");
        assertThat(SchemaSnapshot.unqualifiedTable("something else", "i", "s.t", "t")).isEqualTo("something else");
    }
}
