package spike.s3;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Cursor;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import spike.s3.support.Corpus;
import spike.s3.support.EphemeralPostgres;
import spike.s3.support.Findings;
import spike.s3.support.LoggedStatement;
import spike.s3.support.SpikeExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §3.2 Option B: Vert.x Reactive PostgreSQL Client prepared (extended-protocol) queries. The client does not
 * split; the server itself refuses more than one command per Parse.
 */
@ExtendWith(SpikeExtension.class)
class VertxOptionBTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        await(vertx.close());
    }

    private static <T> T await(Future<T> f) throws Exception {
        try {
            return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static String uniq(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static PgConnection connect(EphemeralPostgres pg, String app) throws Exception {
        PgConnectOptions o = new PgConnectOptions()
                .setHost("127.0.0.1")
                .setPort(pg.port())
                .setDatabase(EphemeralPostgres.DATABASE)
                .setUser(EphemeralPostgres.APP_USER)
                .setPassword(pg.appPassword())
                .setCachePreparedStatements(false)
                .setPipeliningLimit(1)
                .setProperties(Map.of("application_name", app));
        return await(PgConnection.connect(vertx, o));
    }

    private static List<LoggedStatement> logged(EphemeralPostgres pg, PgConnection c, String app) throws Exception {
        String sentinel = "sentinel_" + UUID.randomUUID().toString().replace("-", "");
        await(c.preparedQuery("SELECT '" + sentinel + "'").execute());
        return pg.loggedStatementsUntil(app, sentinel);
    }

    @Test
    void serverRejectsMultipleCommandsInAPreparedQuery(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        String t = uniq("t");
        int count = 2 + rnd.nextInt(4);
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < count; i++) {
            script.append(i == 0 ? "" : (rnd.nextBoolean() ? "; " : ";\n")).append("INSERT INTO ").append(t).append(" VALUES (").append(i).append(')');
        }
        if (rnd.nextBoolean()) {
            script.append(';');
        }
        String app = uniq("vx_multi");
        PgConnection c = connect(pg, app);
        try {
            await(c.preparedQuery("CREATE TABLE " + t + " (id int)").execute());
            Exception e = assertThrows(Exception.class, () -> await(c.preparedQuery(script.toString()).execute()));
            PgException pe = assertInstanceOf(PgException.class, e);
            assertEquals("42601", pe.getSqlState());
            assertTrue(pe.getMessage().contains("cannot insert multiple commands into a prepared statement"), pe.getMessage());
            RowSet<Row> rs = await(c.preparedQuery("SELECT count(*) FROM " + t).execute());
            assertEquals(0L, rs.iterator().next().getLong(0), "nothing from the script was executed");
            findings.record("vertx", "multi_statement_prepared",
                    "server rejected a " + count + "-statement script with 42601 'cannot insert multiple commands into a prepared statement'; nothing ran");
        } finally {
            await(c.close());
        }
    }

    @Test
    void singleStatementsReachTheServerIntact(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        List<String> sample = Corpus.sample(rnd, Corpus.all().size());
        String app = uniq("vx_corpus");
        PgConnection c = connect(pg, app);
        List<String> mismatches = new ArrayList<>();
        try {
            for (String sql : sample) {
                RowSet<Row> rs = await(c.preparedQuery(sql).execute());
                assertEquals(1, rs.size(), "one row: " + sql);
                assertTrue(rs.next() == null, "one result: " + sql);
            }
            List<LoggedStatement> log = logged(pg, c, app);
            assertEquals(sample.size(), log.size(), "one Execute per statement: " + log);
            for (int i = 0; i < sample.size(); i++) {
                String want = Corpus.canonical(sample.get(i));
                String got = Corpus.canonical(log.get(i).text());
                assertEquals(LoggedStatement.Kind.EXECUTE, log.get(i).kind());
                if (!want.equals(got)) {
                    mismatches.add(want + "  =>  " + got);
                }
            }
        } finally {
            await(c.close());
        }
        findings.record("vertx", "corpus_sent_intact", mismatches.isEmpty()
                ? sample.size() + " sampled statements each sent as one unmodified Execute"
                : "MISMATCHES: " + mismatches);
        assertTrue(mismatches.isEmpty(), "Vert.x changed statement text: " + mismatches);
    }

    /** query() uses the simple protocol, which runs whole scripts. The app must never call it with user SQL. */
    @Test
    void simpleQueryApiRunsWholeScripts(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int count = 2 + rnd.nextInt(4);
        List<Integer> expected = new ArrayList<>();
        String script = Corpus.multi(rnd, count, expected);
        PgConnection c = connect(pg, uniq("vx_simple"));
        try {
            RowSet<Row> rs = await(c.query(script).execute());
            int results = 0;
            for (RowSet<Row> r = rs; r != null; r = r.next()) {
                results++;
            }
            assertEquals(count, results);
            findings.record("vertx", "simple_query_api",
                    "query() ran all " + count + " statements of a script. Only preparedQuery() may carry user SQL.");
        } finally {
            await(c.close());
        }
    }

    /** A cursor read inside a transaction stops the server after the rows requested. */
    @Test
    void cursorReadStopsTheServer(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int batch = 1 + rnd.nextInt(300);
        String seq = uniq("seq");
        PgConnection c = connect(pg, uniq("vx_cursor"));
        try {
            await(c.preparedQuery("CREATE SEQUENCE " + seq).execute());
            Transaction tx = await(c.begin());
            PreparedStatement ps = await(c.prepare("SELECT nextval('" + seq + "') FROM generate_series(1, 1000000)"));
            Cursor cursor = ps.cursor();
            RowSet<Row> rows = await(cursor.read(batch));
            assertEquals(batch, rows.size());
            assertTrue(cursor.hasMore());
            await(cursor.close());
            await(ps.close());
            await(tx.commit());
            RowSet<Row> r = await(c.preparedQuery("SELECT last_value FROM " + seq).execute());
            long produced = r.iterator().next().getLong(0);
            assertTrue(produced <= batch + 1L, "server produced " + produced + " rows for a read of " + batch);
            findings.record("vertx", "cursor_read", Map.of("batch", batch, "serverProduced", produced));
        } finally {
            await(c.close());
        }
    }

    @Test
    void cancelRequestEndsARunningStatement(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        long delayMs = 200 + rnd.nextInt(1300);
        PgConnection c = connect(pg, uniq("vx_cancel"));
        try {
            long t0 = System.nanoTime();
            Future<RowSet<Row>> running = c.preparedQuery("SELECT pg_sleep(30)").execute();
            Thread.sleep(delayMs);
            await(c.cancelRequest());
            Exception e = assertThrows(Exception.class, () -> await(running));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertEquals("57014", assertInstanceOf(PgException.class, e).getSqlState());
            assertTrue(elapsedMs < delayMs + 5_000, "cancel took effect promptly: " + elapsedMs + " ms");
            RowSet<Row> ok = await(c.preparedQuery("SELECT 42").execute());
            assertEquals(42, ok.iterator().next().getInteger(0));
            findings.record("vertx", "cancel", Map.of("delayMs", delayMs, "elapsedMs", elapsedMs));
        } finally {
            await(c.close());
        }
    }

    /** User transaction control through preparedQuery works; the client exposes no transaction-status API. */
    @ParameterizedTest(name = "commit={0}")
    @ValueSource(booleans = {true, false})
    void userTransactionControlAndOutcomes(boolean commit, EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        String t = uniq("t");
        int ins = 1 + rnd.nextInt(20);
        String app = uniq("vx_txn");
        PgConnection c = connect(pg, app);
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        try {
            rowCounts.put("CREATE TABLE", await(c.preparedQuery("CREATE TABLE " + t + " (id int)").execute()).rowCount());
            rowCounts.put("BEGIN", await(c.preparedQuery("BEGIN").execute()).rowCount());
            rowCounts.put("INSERT n", await(c.preparedQuery("INSERT INTO " + t + " SELECT generate_series(1, " + ins + ")").execute()).rowCount());
            rowCounts.put("UPDATE 0", await(c.preparedQuery("UPDATE " + t + " SET id = id WHERE false").execute()).rowCount());
            rowCounts.put("CREATE INDEX", await(c.preparedQuery("CREATE INDEX ON " + t + " (id)").execute()).rowCount());
            rowCounts.put("SET", await(c.preparedQuery("SET search_path = public").execute()).rowCount());
            rowCounts.put("END", await(c.preparedQuery(commit ? "COMMIT" : "ROLLBACK").execute()).rowCount());
        } finally {
            await(c.close());
        }
        try (Connection j = pg.app(uniq("vx_check")); Statement s = j.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + t)) {
            rs.next();
            assertEquals(commit ? ins : 0, rs.getInt(1));
        }
        assertEquals(ins, rowCounts.get("INSERT n"));
        assertEquals(0, rowCounts.get("UPDATE 0"));
        // Finding: the client decodes the last word of a non-numeric command tag as if it were digits
        // ("CREATE TABLE" -> "TABLE" -> 379101). rowCount() is meaningless for DDL/utility statements.
        assertEquals(tagTailAsDigits("TABLE"), rowCounts.get("CREATE TABLE"));
        assertEquals(tagTailAsDigits("INDEX"), rowCounts.get("CREATE INDEX"));
        findings.record("vertx", "transaction_and_outcomes",
                Map.of("commit", commit, "rowCounts", rowCounts,
                        "note", "BEGIN/COMMIT/ROLLBACK work via preparedQuery; no public API exposes the ReadyForQuery transaction status. "
                                + "rowCount() for DDL is garbage (command-tag word decoded as digits)."));
    }

    /**
     * Vert.x exposes no transaction status, so the app would ask the server after each user statement. Probe:
     * {@code statement_timestamp() <> transaction_timestamp()} is false when the probe starts its own implicit
     * transaction, true inside an open block, and fails with 25P02 inside an aborted block.
     *
     * <p>The probe is fixed app SQL and must use the simple protocol ({@code query()}): with the extended protocol the
     * implicit transaction starts at Parse but the statement timestamp is taken at Execute, so the two always differ
     * (first attempt of this spike failed exactly that way for the idle cases).
     */
    @ParameterizedTest(name = "scenario={0}")
    @ValueSource(strings = {"idle", "open", "failed", "after-commit", "after-rollback", "savepoint"})
    void transactionStatusProbe(String scenario, EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        PgConnection c = connect(pg, uniq("vx_probe"));
        String probe = "SELECT statement_timestamp() <> transaction_timestamp()";
        try {
            List<String> begins = List.of("BEGIN", "START TRANSACTION", "BEGIN ISOLATION LEVEL SERIALIZABLE", "begin read only");
            String begin = begins.get(rnd.nextInt(begins.size()));
            int work = rnd.nextInt(3);
            String observed;
            switch (scenario) {
                case "idle" -> observed = String.valueOf(probeValue(c, probe));
                case "open", "savepoint", "after-commit", "after-rollback" -> {
                    await(c.preparedQuery(begin).execute());
                    for (int i = 0; i < work; i++) {
                        await(c.preparedQuery("SELECT " + i).execute());
                    }
                    if (scenario.equals("savepoint")) {
                        await(c.preparedQuery("SAVEPOINT s1").execute());
                        await(c.preparedQuery("RELEASE SAVEPOINT s1").execute());
                    }
                    if (scenario.equals("after-commit")) {
                        await(c.preparedQuery("COMMIT").execute());
                    } else if (scenario.equals("after-rollback")) {
                        await(c.preparedQuery("ROLLBACK").execute());
                    }
                    observed = String.valueOf(probeValue(c, probe));
                    if (scenario.equals("open") || scenario.equals("savepoint")) {
                        await(c.preparedQuery("ROLLBACK").execute());
                    }
                }
                case "failed" -> {
                    await(c.preparedQuery(begin).execute());
                    assertThrows(PgException.class, () -> await(c.preparedQuery("SELECT 1/0").execute()));
                    Exception e = assertThrows(Exception.class, () -> probeValue(c, probe));
                    observed = assertInstanceOf(PgException.class, e).getSqlState();
                    await(c.preparedQuery("ROLLBACK").execute());
                }
                default -> throw new IllegalArgumentException(scenario);
            }
            String expected = switch (scenario) {
                case "open", "savepoint" -> "true";
                case "failed" -> "25P02";
                default -> "false";
            };
            assertEquals(expected, observed, scenario + " with '" + begin + "'");
            findings.record("vertx", "txn_probe_" + scenario, Map.of("begin", begin, "observed", observed));
        } finally {
            await(c.close());
        }
    }

    private static boolean probeValue(PgConnection c, String probe) throws Exception {
        return await(c.query(probe).execute()).iterator().next().getBoolean(0);
    }

    /** Reproduces the client's decoding of a command-tag word: each char minus '0', base 10. */
    private static int tagTailAsDigits(String word) {
        int n = 0;
        for (char ch : word.toCharArray()) {
            n = n * 10 + (ch - '0');
        }
        return n;
    }
}
