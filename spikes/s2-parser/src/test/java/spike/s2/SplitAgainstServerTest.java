package spike.s2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import spike.s2.support.EphemeralPostgres;
import spike.s2.support.Findings;
import spike.s2.support.ParserCorpus;
import spike.s2.support.ParserCorpus.Item;
import spike.s2.support.PgQuery;
import spike.s2.support.ServerTruth;
import spike.s2.support.SpikeExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §3.1 / S2: libpg_query's statement boundaries must agree with PostgreSQL's for 100% of the corpus. The server
 * is the reference: it runs the same text and its result count is compared.
 */
@ExtendWith(SpikeExtension.class)
class SplitAgainstServerTest {

    private static String app(String p) {
        return p + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    static IntStream corpusIndexes() {
        return IntStream.range(0, ParserCorpus.ITEMS.size());
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("corpusIndexes")
    void corpusItemAgreesWithServer(int index, EphemeralPostgres pg, PgQuery parser, Findings findings) throws Exception {
        Item item = ParserCorpus.ITEMS.get(index);
        String sql = item.instantiate();
        PgQuery.Parsed parsed = parser.parse(sql, item.scsOff());
        try (ServerTruth server = new ServerTruth(pg, app("corpus"), item.scsOff())) {
            ServerTruth.Outcome truth = server.run(sql);
            if (item.isError()) {
                assertFalse(parsed.ok(), item.category() + ": parser must reject: " + sql);
                assertFalse(truth.ok(), item.category() + ": server must reject: " + sql);
                assertEquals("42601", truth.sqlState(), "server rejects as a syntax error before running anything");
                findings.record("corpus", item.category(), Map.of("sql", sql, "parserError", parsed.error(), "serverSqlState", truth.sqlState()));
                return;
            }
            assertTrue(parsed.ok(), item.category() + ": parser error '" + parsed.error() + "' for: " + sql);
            assertTrue(truth.ok(), item.category() + ": server error " + truth.sqlState() + " " + truth.message() + " for: " + sql);
            assertEquals(item.expected(), truth.count(), "corpus table vs server for: " + sql);
            assertEquals(truth.count(), parsed.stmts().size(), "parser vs server for: " + sql);

            List<String> pieces = parsed.stmts().stream().map(PgQuery.Stmt::text).toList();
            List<ServerTruth.Outcome> perPiece = server.runPieces(pieces);
            assertEquals(pieces.size(), perPiece.size(), "every piece ran: " + perPiece);
            for (int i = 0; i < pieces.size(); i++) {
                assertTrue(perPiece.get(i).ok(), "piece failed on its own: " + pieces.get(i) + " -> " + perPiece.get(i));
                assertEquals(1, perPiece.get(i).count(), "piece is exactly one statement for the server: " + pieces.get(i));
            }
            findings.record("corpus", item.category(), Map.of("sql", sql, "statements", parsed.stmts().size(),
                    "kinds", parsed.stmts().stream().map(PgQuery.Stmt::kind).toList()));
        }
    }

    /** Random scripts built from corpus items with random separators; scs on or off. */
    @Test
    void randomScriptsAgreeWithServer(EphemeralPostgres pg, PgQuery parser, Random rnd, Findings findings) throws Exception {
        String[] separators = {"\n;\n", "\n; -- sep ;\n", "\n;/* ; */ ", "\n;;\n", "\n;\t"};
        int scripts = 30 + rnd.nextInt(21);
        int totalStatements = 0;
        try (ServerTruth on = new ServerTruth(pg, app("rnd_on"), false);
             ServerTruth off = new ServerTruth(pg, app("rnd_off"), true)) {
            for (int s = 0; s < scripts; s++) {
                boolean scsOff = rnd.nextInt(5) == 0;
                List<Item> pool = ParserCorpus.ITEMS.stream().filter(i -> !i.isError() && i.scsOff() == scsOff).toList();
                int parts = 1 + rnd.nextInt(6);
                StringBuilder sb = new StringBuilder();
                int expected = 0;
                for (int p = 0; p < parts; p++) {
                    Item item = pool.get(rnd.nextInt(pool.size()));
                    if (p > 0) {
                        sb.append(separators[rnd.nextInt(separators.length)]);
                    }
                    sb.append(item.instantiate());
                    expected += item.expected();
                }
                String script = sb.toString();
                PgQuery.Parsed parsed = parser.parse(script, scsOff);
                ServerTruth server = scsOff ? off : on;
                ServerTruth.Outcome truth = server.run(script);
                assertTrue(parsed.ok(), "parser error " + parsed.error() + " for: " + script);
                assertTrue(truth.ok(), "server error " + truth.sqlState() + " " + truth.message() + " for: " + script);
                assertEquals(expected, truth.count(), "sum of item counts vs server: " + script);
                assertEquals(truth.count(), parsed.stmts().size(), "parser vs server: " + script);

                int lastEnd = -1;
                for (PgQuery.Stmt st : parsed.stmts()) {
                    assertTrue(st.byteStart() >= lastEnd, "pieces are ordered and do not overlap");
                    lastEnd = st.byteStart() + st.byteLen();
                }
                List<ServerTruth.Outcome> perPiece = server.runPieces(parsed.stmts().stream().map(PgQuery.Stmt::text).toList());
                for (ServerTruth.Outcome o : perPiece) {
                    assertTrue(o.ok() && o.count() == 1, "each piece is one statement for the server: " + o + " in " + script);
                }
                totalStatements += parsed.stmts().size();
            }
        }
        findings.record("random-scripts", "summary", Map.of("scripts", scripts, "statements", totalStatements, "mismatches", 0));
    }

    /** The same text splits differently depending on standard_conforming_strings, exactly as the server does. */
    @Test
    void standardConformingStringsChangesTheSplit(EphemeralPostgres pg, PgQuery parser, Random rnd, Findings findings) throws Exception {
        String inner = "v" + rnd.nextInt(1000);
        String sql = "SELECT '" + inner + "\\'; SELECT " + rnd.nextInt(1000) + "; --'";
        PgQuery.Parsed on = parser.parse(sql, false);
        PgQuery.Parsed off = parser.parse(sql, true);
        try (ServerTruth sOn = new ServerTruth(pg, app("scs_on"), false); ServerTruth sOff = new ServerTruth(pg, app("scs_off"), true)) {
            assertEquals(sOn.run(sql).count(), on.stmts().size());
            assertEquals(sOff.run(sql).count(), off.stmts().size());
        }
        assertEquals(2, on.stmts().size());
        assertEquals(1, off.stmts().size());
        findings.record("scs", "split_depends_on_setting",
                "same text: 2 statements with standard_conforming_strings=on, 1 with off; parser matches the server only if told the session's setting");
    }

    /** Byte offsets from the parser must be converted correctly for multi-byte UTF-8 text. */
    @Test
    void unicodeOffsetsAreBytes(PgQuery parser, Random rnd) {
        String[] words = {"数据", "ünïcødé", "😀", "Ωμέγα", "ascii"};
        StringBuilder sb = new StringBuilder();
        List<String> expected = new ArrayList<>();
        int n = 2 + rnd.nextInt(5);
        for (int i = 0; i < n; i++) {
            String w = words[rnd.nextInt(words.length)];
            String st = "SELECT '" + w + ";" + i + "' AS \"" + w + "\"";
            expected.add(st);
            sb.append(st).append(i < n - 1 ? "; " : "");
        }
        PgQuery.Parsed parsed = parser.parse(sb.toString(), false);
        assertTrue(parsed.ok());
        assertEquals(expected, parsed.stmts().stream().map(s -> s.text().strip()).toList());
        assertNotNull(parsed.stmts().get(0).kind());
    }
}
