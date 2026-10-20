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
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Export of rows the browser holds (admin Phase 7, decision A4): the request is validated, recorded as
 * {@code data_export} before the browser saves anything (fail-closed), never with data, and only for the caller's own
 * session.
 */
class ExportIT {

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
            for (String role : List.of("ada", "bob")) {
                String pw = "Pw" + UUID.randomUUID();
                s.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pw + "'");
                connector.password(role, pw);
            }
            s.execute("CREATE DATABASE exp_db");
            s.execute("GRANT CONNECT ON DATABASE exp_db TO ada, bob");
        }
        app = apps.app(p -> p.setProperty("execution.enabled", "true"), new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static MockClient client(String role, String rid) throws Exception {
        MockClient c = MockClient.user(app.port(), "TEST\\" + role, "S-1-5-21-7-7-7-" + rid);
        c.me();
        return c;
    }

    static String open(MockClient c) throws Exception {
        HttpResponse<String> r = c.post("/api/db-sessions/external", "{\"database\":\"exp_db\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return Json.mapper().readTree(r.body()).path("id").asText();
    }

    @Test
    void anExportIsRecordedWithoutItsDataAndOnlyForYourOwnSession() throws Exception {
        MockClient ada = client("ada", "1");
        String id = open(ada);
        HttpResponse<String> run = ada.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", "SELECT 'secret-cell' AS v, 2 AS w")));
        JsonNode doc = Json.mapper().readTree(run.body());
        String execution = doc.path("executionId").asText();
        String path = "/api/db-sessions/" + id + "/export";

        HttpResponse<String> ok = ada.post(path, Json.write(Map.of("executionId", execution, "format", "csv", "rows", 1, "columns", 2)));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).contains("\"recorded\":true");
        List<Map<String, Object>> events = apps.events("data_export", "S-1-5-21-7-7-7-1");
        assertThat(events).hasSize(1);
        Map<String, Object> e = events.get(0);
        assertThat(String.valueOf(e.get("details"))).contains("\"format\": \"csv\"").contains("\"rows\": 1").contains("\"columns\": 2")
                .doesNotContain("secret-cell");
        assertThat(String.valueOf(e.get("execution_id"))).isEqualTo(execution);
        assertThat(ada.post(path, Json.write(Map.of("format", "json", "rows", 0, "columns", 1))).statusCode()).isEqualTo(200);

        assertThat(ada.post(path, Json.write(Map.of("format", "xlsx", "rows", 1, "columns", 1))).body()).contains("INVALID_FORMAT");
        assertThat(ada.post(path, Json.write(Map.of("format", "csv", "rows", -1, "columns", 1))).body()).contains("INVALID_ROWS");
        assertThat(ada.post(path, Json.write(Map.of("format", "csv", "rows", 10_001, "columns", 1))).body()).contains("INVALID_ROWS");
        assertThat(ada.post(path, Json.write(Map.of("format", "csv", "columns", 1))).body()).contains("INVALID_ROWS");
        assertThat(ada.post(path, Json.write(Map.of("format", "csv", "rows", 1, "columns", 0))).body()).contains("INVALID_COLUMNS");
        assertThat(ada.post(path, Json.write(Map.of("format", "csv", "rows", 1))).body()).contains("INVALID_COLUMNS");
        assertThat(ada.post(path, Json.write(Map.of("executionId", "nope", "format", "csv", "rows", 1, "columns", 1))).body()).contains("INVALID_EXECUTION");
        assertThat(ada.post(path, "{\"format\":\"csv\",\"rows\":1,\"columns\":1,\"data\":\"x\"}").statusCode()).isEqualTo(400); // strict body

        MockClient bob = client("bob", "2");
        assertThat(bob.post(path, Json.write(Map.of("format", "csv", "rows", 1, "columns", 1))).statusCode()).isEqualTo(404);
        assertThat(apps.events("data_export", "S-1-5-21-7-7-7-2")).isEmpty();
        assertThat(apps.events("data_export", "S-1-5-21-7-7-7-1")).hasSize(2);
    }
}
