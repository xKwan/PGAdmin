package pgweb.savedqueries;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.TestApps;
import pgweb.web.Json;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saved queries on the server (admin Phase 11): each account sees only its own; names unique per account; bounded;
 * audited without the text; off unless configured.
 */
class SavedQueriesIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static Application app;
    static Application off;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        app = apps.app(p -> p.setProperty("savedQueries.enabled", "true"), new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC()));
        off = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC()));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static MockClient user(Application a, String name, String rid) throws Exception {
        MockClient c = MockClient.user(a.port(), "TEST\\" + name, "S-1-5-21-9-9-8-" + rid);
        c.me();
        return c;
    }

    static Map<String, String> headers(MockClient c) {
        return Map.of("Origin", c.origin(), "X-CSRF-Token", c.csrf(), "Content-Type", "application/json");
    }

    static JsonNode json(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return Json.mapper().readTree(r.body());
    }

    @Test
    void anAccountSavesListsOpensReplacesAndDeletesItsOwnQueries() throws Exception {
        MockClient ada = user(app, "ada", "1");
        String text = "SELECT * FROM orders WHERE customer = 'Smith' -- secret literal";
        JsonNode created = json(ada.post("/api/saved-queries", Json.write(Map.of("name", "Big orders", "text", text))), 201);
        String id = created.path("id").asText();
        assertThat(created.has("text")).isFalse();
        JsonNode list = json(ada.get("/api/saved-queries"), 200);
        assertThat(list.path("queries").get(0).path("name").asText()).isEqualTo("Big orders");
        assertThat(list.path("queries").get(0).path("length").asInt()).isEqualTo(text.length());
        assertThat(list.toString()).doesNotContain("Smith");
        assertThat(json(ada.get("/api/saved-queries/" + id), 200).path("text").asText()).isEqualTo(text);

        // Replace: same ID, new text; a second query can't take the same name (any case).
        json(ada.send("PUT", "/api/saved-queries/" + id, Json.write(Map.of("name", "Big orders", "text", "SELECT 2")), headers(ada)), 200);
        assertThat(json(ada.get("/api/saved-queries/" + id), 200).path("text").asText()).isEqualTo("SELECT 2");
        HttpResponse<String> dup = ada.post("/api/saved-queries", Json.write(Map.of("name", "BIG ORDERS", "text", "SELECT 3")));
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(dup.body()).contains("NAME_TAKEN");

        // Another account sees nothing of it, and can't open, change or delete it.
        MockClient bob = user(app, "bob", "2");
        assertThat(json(bob.get("/api/saved-queries"), 200).path("queries").size()).isZero();
        assertThat(bob.get("/api/saved-queries/" + id).statusCode()).isEqualTo(404);
        assertThat(bob.send("PUT", "/api/saved-queries/" + id, Json.write(Map.of("name", "x", "text", "SELECT 1")), headers(bob)).statusCode()).isEqualTo(404);
        assertThat(bob.send("DELETE", "/api/saved-queries/" + id, null, headers(bob)).statusCode()).isEqualTo(404);

        // On disk: one file per account, named by a hash, never by the account.
        Path dir = app.config().server().workDir().resolve("saved-queries");
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(f -> f.getFileName().toString()).toList();
            String mine = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("S-1-5-21-9-9-8-1".getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ".json";
            assertThat(names).contains(mine).allMatch(n -> n.matches("[0-9a-f]{64}\\.json"));
        }
        // Audited without the text.
        List<Map<String, Object>> saved = apps.events("saved_query_saved", "S-1-5-21-9-9-8-1");
        assertThat(saved).hasSize(2);
        assertThat(saved.toString()).doesNotContain("Smith").doesNotContain("SELECT").contains("sqlHmac");

        json(ada.send("DELETE", "/api/saved-queries/" + id, null, headers(ada)), 200);
        assertThat(ada.get("/api/saved-queries/" + id).statusCode()).isEqualTo(404);
        assertThat(apps.events("saved_query_deleted", "S-1-5-21-9-9-8-1")).hasSize(1);
    }

    @Test
    void namesTextsAndIdsAreChecked() throws Exception {
        MockClient eve = user(app, "eve", "3");
        assertThat(eve.post("/api/saved-queries", Json.write(Map.of("name", "", "text", "SELECT 1"))).statusCode()).isEqualTo(400);
        assertThat(eve.post("/api/saved-queries", Json.write(Map.of("name", "x".repeat(101), "text", "SELECT 1"))).statusCode()).isEqualTo(400);
        assertThat(eve.post("/api/saved-queries", Json.write(Map.of("name", "two\nlines", "text", "SELECT 1"))).statusCode()).isEqualTo(400);
        assertThat(eve.post("/api/saved-queries", Json.write(Map.of("name", "ok", "text", "  "))).statusCode()).isEqualTo(400);
        assertThat(eve.post("/api/saved-queries", Json.write(Map.of("name", "ok", "text", "x".repeat(100_001)))).statusCode()).isEqualTo(400);
        assertThat(eve.get("/api/saved-queries/..%2F..%2Fsecret").statusCode()).as("refused by the server or not found").isIn(400, 404);
        assertThat(eve.get("/api/saved-queries/not-a-uuid").statusCode()).isEqualTo(404);
        assertThat(eve.send("PUT", "/api/saved-queries", Json.write(Map.of("name", "a", "text", "b")), headers(eve)).statusCode()).isEqualTo(404);
        for (int i = 0; i < SavedQueries.MAX_QUERIES; i++) {
            json(eve.post("/api/saved-queries", Json.write(Map.of("name", "q" + i, "text", "SELECT " + i))), 201);
        }
        HttpResponse<String> full = eve.post("/api/saved-queries", Json.write(Map.of("name", "one too many", "text", "SELECT 1")));
        assertThat(full.statusCode()).isEqualTo(409);
        assertThat(full.body()).contains("TOO_MANY");
        Path dir = app.config().server().workDir().resolve("saved-queries");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.filter(f -> f.toString().endsWith(".tmp")).count()).as("no temporary files left").isZero();
        }
    }

    @Test
    void itIsOffUnlessConfigured() throws Exception {
        MockClient ada = user(off, "ada", "1");
        assertThat(ada.me().path("capabilities").path("savedQueries").asBoolean()).isFalse();
        assertThat(ada.get("/api/saved-queries").statusCode()).isEqualTo(404);
        assertThat(ada.post("/api/saved-queries", Json.write(Map.of("name", "a", "text", "SELECT 1"))).statusCode()).isEqualTo(404);
    }
}
