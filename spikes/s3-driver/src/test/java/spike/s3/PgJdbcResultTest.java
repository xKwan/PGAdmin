package spike.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import spike.s3.support.EphemeralPostgres;
import spike.s3.support.Findings;
import spike.s3.support.SpikeExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan §3.3 / §3.4 / S3: bounded retrieval and statement outcome reporting with pgJDBC. */
@ExtendWith(SpikeExtension.class)
class PgJdbcResultTest {

    private static String uniq(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static long lastValue(Connection c, String seq) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_value, is_called FROM " + seq)) {
            rs.next();
            return rs.getBoolean(2) ? rs.getLong(1) : 0;
        }
    }

    /**
     * setMaxRows must stop the server producing rows, not just make the driver discard them. Proof: each produced row
     * calls nextval(), so the sequence shows how many rows the server actually generated.
     */
    @ParameterizedTest(name = "autoCommit={0}")
    @ValueSource(booleans = {true, false})
    void maxRowsStopsTheServer(boolean autoCommit, EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int maxRows = 1 + rnd.nextInt(200);
        int total = 50_000 + rnd.nextInt(150_000);
        String seq = uniq("seq");
        try (Connection c = pg.app(uniq("maxrows"))) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SEQUENCE " + seq);
            }
            c.setAutoCommit(autoCommit);
            int got = 0;
            try (Statement s = c.createStatement()) {
                s.setMaxRows(maxRows);
                try (ResultSet rs = s.executeQuery("SELECT nextval('" + seq + "') FROM generate_series(1, " + total + ")")) {
                    while (rs.next()) {
                        got++;
                    }
                }
            }
            if (!autoCommit) {
                c.commit();
                c.setAutoCommit(true);
            }
            long produced = lastValue(c, seq);
            assertEquals(maxRows, got, "driver returned maxRows rows");
            assertTrue(produced <= maxRows + 1L,
                    "server produced " + produced + " rows for maxRows=" + maxRows + " of " + total);
            findings.record("pgjdbc", "max_rows",
                    Map.of("autoCommit", autoCommit, "maxRows", maxRows, "total", total, "serverProduced", produced));
        }
    }

    /**
     * fetchSize (with autocommit off) streams in batches through a portal; abandoning the result after the first
     * batch also stops the server.
     */
    @Test
    void fetchSizeStreamsAndStopsWhenAbandoned(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int fetch = 10 + rnd.nextInt(500);
        int read = 1 + rnd.nextInt(fetch);
        String seq = uniq("seq");
        try (Connection c = pg.app(uniq("fetch"))) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SEQUENCE " + seq);
            }
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.setFetchSize(fetch);
                try (ResultSet rs = s.executeQuery("SELECT nextval('" + seq + "') FROM generate_series(1, 1000000)")) {
                    for (int i = 0; i < read; i++) {
                        assertTrue(rs.next());
                    }
                }
            }
            c.commit();
            c.setAutoCommit(true);
            long produced = lastValue(c, seq);
            assertTrue(produced <= fetch + 1L, "server produced " + produced + " rows for fetchSize=" + fetch);
            findings.record("pgjdbc", "fetch_size", Map.of("fetchSize", fetch, "rowsRead", read, "serverProduced", produced));
        }
    }

    /** DML with RETURNING under maxRows: the statement still modifies every matching row; only the output is cut. */
    @Test
    void dmlReturningWithMaxRowsAffectsAllRows(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int rows = 20 + rnd.nextInt(500);
        int maxRows = 1 + rnd.nextInt(rows - 1);
        int delta = 1 + rnd.nextInt(9);
        String t = uniq("t");
        try (Connection c = pg.app(uniq("returning")); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE " + t + " (id int PRIMARY KEY, v int NOT NULL)");
            s.execute("INSERT INTO " + t + " SELECT g, 0 FROM generate_series(1, " + rows + ") g");
            try (Statement u = c.createStatement()) {
                u.setMaxRows(maxRows);
                int returned = 0;
                try (ResultSet rs = u.executeQuery("UPDATE " + t + " SET v = v + " + delta + " RETURNING id")) {
                    while (rs.next()) {
                        returned++;
                    }
                }
                assertEquals(maxRows, returned);
            }
            try (ResultSet rs = s.executeQuery("SELECT count(*) FILTER (WHERE v = " + delta + "), count(*) FROM " + t)) {
                rs.next();
                assertEquals(rows, rs.getInt(1), "every row was updated even though only maxRows were returned");
                assertEquals(rows, rs.getInt(2));
            }
            findings.record("pgjdbc", "dml_returning_max_rows",
                    "UPDATE ... RETURNING with maxRows=" + maxRows + " updated all " + rows
                            + " rows; the UI must say the result was truncated, not that fewer rows changed");
        }
    }

    /**
     * What the public JDBC API reports for each kind of statement. Expected: DDL and "UPDATE 0" both give update
     * count 0, so the API alone cannot tell them apart; the command tag is not public.
     */
    @Test
    void commandOutcomesAsReportedByTheDriver(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int ins = 1 + rnd.nextInt(20);
        String t = uniq("t");
        Map<String, String> seen = new LinkedHashMap<>();
        try (Connection c = pg.app(uniq("cmd")); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            Map<String, String> statements = new LinkedHashMap<>();
            statements.put("CREATE TABLE", "CREATE TABLE " + t + " (id int)");
            statements.put("INSERT n", "INSERT INTO " + t + " SELECT generate_series(1, " + ins + ")");
            statements.put("UPDATE 0", "UPDATE " + t + " SET id = id WHERE false");
            statements.put("SELECT", "SELECT 1");
            statements.put("SET", "SET search_path = public");
            statements.put("DO", "DO $$ BEGIN PERFORM 1; END $$");
            statements.put("CREATE INDEX", "CREATE INDEX ON " + t + " (id)");
            statements.put("DELETE n", "DELETE FROM " + t);
            statements.put("DROP TABLE", "DROP TABLE " + t);
            for (Map.Entry<String, String> e : statements.entrySet()) {
                boolean isRs = s.execute(e.getValue());
                if (isRs) {
                    s.getResultSet().close();
                }
                seen.put(e.getKey(), "resultSet=" + isRs + " updateCount=" + (isRs ? "n/a" : s.getUpdateCount()));
            }
        }
        assertEquals("resultSet=false updateCount=" + ins, seen.get("INSERT n"));
        assertEquals("resultSet=false updateCount=" + ins, seen.get("DELETE n"));
        assertEquals("resultSet=false updateCount=0", seen.get("UPDATE 0"));
        assertEquals(seen.get("UPDATE 0"), seen.get("CREATE TABLE"),
                "DDL is indistinguishable from UPDATE 0 through the public API");
        assertFalse(seen.get("SELECT").contains("false"));
        findings.record("pgjdbc", "command_outcomes", seen);
    }
}
