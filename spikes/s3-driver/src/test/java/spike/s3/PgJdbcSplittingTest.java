package spike.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import spike.s3.support.Corpus;
import spike.s3.support.EphemeralPostgres;
import spike.s3.support.Findings;
import spike.s3.support.LoggedStatement;
import spike.s3.support.SpikeExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan §3.2 / S3: what pgJDBC actually sends to the server. */
@ExtendWith(SpikeExtension.class)
class PgJdbcSplittingTest {

    private static String app(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Expected: in the default (extended) query mode pgJDBC splits a multi-statement string on the client and sends
     * each part as its own Parse/Execute. So the driver, not the server, decides statement boundaries.
     */
    @Test
    void multiStatementStringIsSplitByTheClient(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int count = 2 + rnd.nextInt(4);
        List<Integer> expected = new ArrayList<>();
        String script = Corpus.multi(rnd, count, expected);
        String app = app("split");
        try (Connection c = pg.app(app); Statement s = c.createStatement()) {
            List<Integer> got = new ArrayList<>();
            boolean isRs = s.execute(script);
            while (true) {
                if (isRs) {
                    try (ResultSet rs = s.getResultSet()) {
                        rs.next();
                        got.add(rs.getInt(1));
                    }
                } else if (s.getUpdateCount() == -1) {
                    break;
                }
                isRs = s.getMoreResults();
            }
            assertEquals(expected, got, "every statement in the script ran and returned its own result");

            List<LoggedStatement> log = pg.loggedStatements(app, c);
            assertEquals(count, log.size(), "one server-side Execute per statement: " + log);
            for (int i = 0; i < count; i++) {
                assertEquals(LoggedStatement.Kind.EXECUTE, log.get(i).kind());
                assertEquals("SELECT " + expected.get(i) + " AS v", log.get(i).text().strip());
            }
        }
        findings.record("pgjdbc", "multi_statement_extended_mode",
                "client-side split into " + count + " separate Execute messages (server never saw the whole script)");
    }

    /** In simple query mode the whole script goes to the server in one Query message, which runs all of it. */
    @Test
    void simpleQueryModeSendsTheWholeScript(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int count = 2 + rnd.nextInt(4);
        List<Integer> expected = new ArrayList<>();
        String script = Corpus.multi(rnd, count, expected);
        String app = app("simple");
        Properties p = new Properties();
        p.setProperty("preferQueryMode", "simple");
        try (Connection c = pg.app(app, p); Statement s = c.createStatement()) {
            s.execute(script);
            List<LoggedStatement> log = pg.loggedStatements(app, c);
            assertEquals(1, log.size(), "one Query message holding the full script: " + log);
            assertEquals(LoggedStatement.Kind.SIMPLE, log.get(0).kind());
            assertTrue(log.get(0).text().contains(";"), "server received the semicolons");
        }
        findings.record("pgjdbc", "multi_statement_simple_mode",
                "whole script sent as one simple Query; the server executes every statement. Never use preferQueryMode=simple.");
    }

    /**
     * Gate for Option A: every parser-approved single statement must reach the server as exactly one Execute whose
     * text is the statement itself. Escape processing must be off, otherwise JDBC escapes are rewritten.
     */
    @Test
    void singleStatementsReachTheServerIntact(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        List<String> sample = Corpus.sample(rnd, Corpus.all().size());
        String app = app("corpus");
        List<String> mismatches = new ArrayList<>();
        try (Connection c = pg.app(app)) {
            for (String sql : sample) {
                try (Statement s = c.createStatement()) {
                    s.setEscapeProcessing(false);
                    assertTrue(s.execute(sql), "returns a result set: " + sql);
                    try (ResultSet rs = s.getResultSet()) {
                        assertTrue(rs.next());
                        assertFalse(rs.next(), "exactly one row: " + sql);
                    }
                    assertFalse(s.getMoreResults(), "exactly one result: " + sql);
                    assertEquals(-1, s.getUpdateCount());
                }
            }
            List<LoggedStatement> log = pg.loggedStatements(app, c);
            assertEquals(sample.size(), log.size(), "one Execute per statement: " + log);
            for (int i = 0; i < sample.size(); i++) {
                String want = Corpus.canonical(sample.get(i));
                String got = Corpus.canonical(log.get(i).text());
                if (!want.equals(got)) {
                    mismatches.add(want + "  =>  " + got);
                }
            }
        }
        findings.record("pgjdbc", "corpus_sent_intact", mismatches.isEmpty()
                ? sample.size() + " sampled statements each sent as one unmodified Execute"
                : "MISMATCHES: " + mismatches);
        assertTrue(mismatches.isEmpty(), "pgJDBC changed statement text: " + mismatches);
    }

    /** With escape processing on (the JDBC default), pgJDBC rewrites {fn ...} escapes before sending. */
    @Test
    void escapeProcessingRewritesTextWhenEnabled(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        String word = "w" + rnd.nextInt(10_000);
        String sql = "SELECT {fn ucase('" + word + "')} AS x";
        String app = app("escape");
        try (Connection c = pg.app(app); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                assertEquals(word.toUpperCase(), rs.getString(1));
            }
            List<LoggedStatement> log = pg.loggedStatements(app, c);
            assertEquals(1, log.size());
            assertNotEquals(sql, log.get(0).text().strip(), "the driver rewrote the statement");
            findings.record("pgjdbc", "escape_processing",
                    "default ON rewrites '" + sql + "' to '" + log.get(0).text().strip() + "'. The app must call setEscapeProcessing(false).");
        }
    }
}
