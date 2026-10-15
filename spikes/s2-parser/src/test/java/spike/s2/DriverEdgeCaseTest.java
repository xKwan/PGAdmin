package spike.s2;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import spike.s2.support.EphemeralPostgres;
import spike.s2.support.Findings;
import spike.s2.support.PgQuery;
import spike.s2.support.SpikeExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases found while building the S2 reference harness, observed on both drivers:
 * <ul>
 *   <li>text with no statements (empty, only semicolons, only comments);</li>
 *   <li>SQL-standard bodies ({@code BEGIN ATOMIC ... END}) through pgJDBC's client-side splitter.</li>
 * </ul>
 */
@ExtendWith(SpikeExtension.class)
class DriverEdgeCaseTest {

    private static Vertx vertx;

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static <T> String outcome(Future<T> f, long timeoutMs) {
        try {
            f.toCompletionStage().toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            return "completed";
        } catch (TimeoutException e) {
            return "NO RESPONSE after " + timeoutMs + " ms";
        } catch (Exception e) {
            return "error: " + e.getCause();
        }
    }

    private static PgConnection vx(EphemeralPostgres pg) throws Exception {
        PgConnectOptions o = new PgConnectOptions().setHost("127.0.0.1").setPort(pg.port())
                .setDatabase(EphemeralPostgres.DATABASE).setUser(EphemeralPostgres.APP_USER).setPassword(pg.appPassword());
        return PgConnection.connect(vertx, o).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ";", " ; ; ", "-- only a comment", "/* only */", "/* a */ ; -- b"})
    void textWithoutStatements(String text, EphemeralPostgres pg, PgQuery parser, Findings findings) throws Exception {
        Map<String, Object> seen = new LinkedHashMap<>();
        PgQuery.Parsed parsed = parser.parse(text, false);
        seen.put("parserStatements", parsed.ok() ? parsed.stmts().size() : parsed.error());

        PgConnection c1 = vx(pg);
        seen.put("vertxQuery", outcome(c1.query(text).execute(), 3000));
        seen.put("vertxQueryConnectionAfter", outcome(c1.query("SELECT 1").execute(), 3000));
        c1.close();
        PgConnection c2 = vx(pg);
        seen.put("vertxPreparedQuery", outcome(c2.preparedQuery(text).execute(), 3000));
        seen.put("vertxPreparedConnectionAfter", outcome(c2.preparedQuery("SELECT 1").execute(), 3000));
        c2.close();

        try (Connection j = pg.app("edge_" + UUID.randomUUID().toString().substring(0, 6)); Statement s = j.createStatement()) {
            s.setEscapeProcessing(false);
            try {
                boolean rs = s.execute(text);
                seen.put("pgjdbc", "resultSet=" + rs + " updateCount=" + s.getUpdateCount());
            } catch (SQLException e) {
                seen.put("pgjdbc", "error " + e.getSQLState());
            }
        }
        findings.record("edge-empty", text.isEmpty() ? "<empty>" : text, seen);
        // Observed on Vert.x 5.2.1: the future never completes, but the connection stays usable.
        assertEquals(0, seen.get("parserStatements"), "parser sees no statement");
        assertTrue(String.valueOf(seen.get("vertxQuery")).startsWith("NO RESPONSE"), seen.toString());
        assertTrue(String.valueOf(seen.get("vertxPreparedQuery")).startsWith("NO RESPONSE"), seen.toString());
        assertEquals("completed", seen.get("vertxQueryConnectionAfter"));
        assertEquals("completed", seen.get("vertxPreparedConnectionAfter"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE FUNCTION fa_{u}() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT 2; END",
            "CREATE PROCEDURE pa_{u}() LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT 2; END",
            "CREATE OR REPLACE PROCEDURE pb_{u}(a int) LANGUAGE sql BEGIN ATOMIC INSERT INTO x_{u} VALUES (a); INSERT INTO x_{u} VALUES (a + 1); END"})
    void beginAtomicThroughPgJdbc(String template, EphemeralPostgres pg, PgQuery parser, Random rnd, Findings findings) throws Exception {
        String u = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String sql = template.replace("{u}", u);
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("parserStatements", parser.parse(sql, false).stmts().size());
        String app = "atomic_" + u;
        try (Connection j = pg.app(app); Statement s = j.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute("CREATE TABLE x_" + u + " (a int)");
            try {
                s.execute(sql);
                seen.put("pgjdbcExtended", "ok");
            } catch (SQLException e) {
                seen.put("pgjdbcExtended", "error " + e.getSQLState() + ": " + e.getMessage());
            }
            seen.put("serverReceived", pg.loggedStatements(app, j).stream().map(l -> l.kind() + ": " + l.text()).toList());
        }
        findings.record("edge-begin-atomic", template, seen);
        assertEquals(1, seen.get("parserStatements"));
        assertEquals("ok", seen.get("pgjdbcExtended"));
        assertEquals(java.util.List.of("EXECUTE: CREATE TABLE x_" + u + " (a int)", "EXECUTE: " + sql), seen.get("serverReceived"),
                "pgJDBC sends the whole BEGIN ATOMIC body as one statement");
    }
}
