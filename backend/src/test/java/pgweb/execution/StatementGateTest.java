package pgweb.execution;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pgweb.sqlparse.SqlParser;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gate and the parser binding without a server (the corpus against a real server is in ExecutionIT). */
class StatementGateTest {

    static SqlParser parser;
    static StatementGate gate;

    @BeforeAll
    static void load() {
        parser = SqlParser.load(TestConfig.PARSER);
        gate = new StatementGate(parser, 1024);
    }

    @Test
    void theCorpusCountsMatchTheTable() {
        for (ParserCorpus.Item item : ParserCorpus.ITEMS) {
            SqlParser.Parsed p = parser.parse(item.instantiate(), !item.scsOff());
            if (item.isError()) {
                assertThat(p.ok()).as(item.sql()).isFalse();
            } else {
                assertThat(p.statements()).as(item.sql()).hasSize(item.expected());
            }
        }
    }

    @Test
    void verdicts() {
        assertThat(gate.check("SELECT 1;", true).approved()).isTrue();
        assertThat(gate.check("SELECT 1;", true).statementClass()).isEqualTo("select");
        assertThat(gate.check(null, true).rejection()).isEqualTo(StatementGate.Rejection.EMPTY_STATEMENT);
        assertThat(gate.check(" -- x\n", true).rejection()).isEqualTo(StatementGate.Rejection.EMPTY_STATEMENT);
        assertThat(gate.check("SELECT 1; SELECT 2", true).rejection()).isEqualTo(StatementGate.Rejection.MULTIPLE_STATEMENTS);
        assertThat(gate.check("SELECT '" + "x".repeat(1100) + "'", true).rejection()).isEqualTo(StatementGate.Rejection.TOO_LARGE);
        // 1024 bytes are counted in UTF-8: 400 three-byte characters are too many even though they are 400 chars.
        assertThat(gate.check("SELECT '" + "漢".repeat(400) + "'", true).rejection()).isEqualTo(StatementGate.Rejection.TOO_LARGE);
        assertThat(gate.check("COPY t FROM STDIN", true).rejection()).isEqualTo(StatementGate.Rejection.UNSUPPORTED);
        assertThat(gate.check("COPY t TO STDOUT", true).rejection()).isEqualTo(StatementGate.Rejection.UNSUPPORTED);
        assertThat(gate.check("COPY t TO '/tmp/x'", true).approved()).isTrue(); // server-side file: PostgreSQL decides
        // A NUL would end the parser's C string early while pgJDBC sends the rest: refused outright.
        StatementGate.Verdict nul = gate.check("SELECT 1\u0000; DROP TABLE t", true);
        assertThat(nul.rejection()).isEqualTo(StatementGate.Rejection.SYNTAX_ERROR);
        assertThat(nul.position()).isEqualTo(9);
        assertThat(gate.check("LISTEN c", true).rejection()).isEqualTo(StatementGate.Rejection.UNSUPPORTED);
        assertThat(gate.check("UNLISTEN *", true).rejection()).isEqualTo(StatementGate.Rejection.UNSUPPORTED);
    }

    @Test
    void syntaxErrorPositionsAreInCharactersNotBytes() {
        StatementGate.Verdict v = gate.check("SELECT 'é漢🙂' FRM t", true);
        assertThat(v.rejection()).isEqualTo(StatementGate.Rejection.SYNTAX_ERROR);
        // 'FRM' is read as a column alias, so the error is at "t": UTF-16 position 19 (the emoji is two chars),
        // while libpg_query reports code point 18 (finding P7-1) and the byte offset would be 24.
        assertThat(v.position()).isEqualTo(19);
        assertThat("SELECT 'é漢🙂' FRM t".charAt(v.position() - 1)).isEqualTo('t');
        assertThat(gate.check("SELEC 1", true).position()).isEqualTo(1);
        assertThat(SqlParser.utf16Position("a🙂b", 3)).isEqualTo(4);
        assertThat(SqlParser.utf16Position("ab", 0)).isEqualTo(-1);
        assertThat(SqlParser.utf16Position("ab", 99)).isEqualTo(3);
    }

    @Test
    void pgJdbcSplitsTheWayTheGateAssumes() {
        assertThat(StatementGate.jdbcStatementCount("SELECT 1", true)).isEqualTo(1);
        assertThat(StatementGate.jdbcStatementCount("SELECT 1; SELECT 2", true)).isEqualTo(2);
        assertThat(StatementGate.jdbcStatementCount("SELECT ';'", true)).isEqualTo(1);
        assertThat(StatementGate.jdbcStatementCount("SELECT 1;", true)).isEqualTo(1);
    }

    @Test
    void classificationIsMetadataOnly() {
        assertThat(StatementGate.classify("SelectStmt")).isEqualTo("select");
        assertThat(StatementGate.classify("UpdateStmt")).isEqualTo("dml");
        assertThat(StatementGate.classify("CreateStmt")).isEqualTo("ddl");
        assertThat(StatementGate.classify("AlterTableStmt")).isEqualTo("ddl");
        assertThat(StatementGate.classify("IndexStmt")).isEqualTo("ddl");
        assertThat(StatementGate.classify("ViewStmt")).isEqualTo("ddl");
        assertThat(StatementGate.classify("TransactionStmt")).isEqualTo("transaction");
        assertThat(StatementGate.classify("CheckPointStmt")).isEqualTo("other");
        // A data-modifying CTE is a SelectStmt (S2 P6): that's why the class never allows or denies anything.
        assertThat(gate.check("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", true).statementClass()).isEqualTo("select");
    }

    @Test
    void auditTextHasNoLiteralsOrComments() {
        String masked = gate.auditText("SELECT 'secret', 42, 4.2, E'\\x41', B'101', X'ff', $$dollar$$ /* c secret */ -- tail secret\n FROM t");
        assertThat(masked).doesNotContain("secret").doesNotContain("42").doesNotContain("dollar").doesNotContain("101").doesNotContain("ff'");
        assertThat(masked).startsWith("SELECT $1, $2, $3, $4, $5, $6, $7").endsWith("FROM t");
        assertThat(gate.auditText("SELECT \"漢\" FROM \"表\" WHERE a = 'ü'")).isEqualTo("SELECT \"漢\" FROM \"表\" WHERE a = $1");
        assertThatThrownBy(() -> parser.auditText("SELECT 'unterminated")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theParserIsSafeToShareBetweenThreads() {
        Random r = Seeds.random("parser-threads");
        List<String> texts = IntStream.range(0, 200).mapToObj(i -> r.nextBoolean() ? "SELECT " + i : "SELECT " + i + "; SELECT 2").toList();
        ConcurrentLinkedQueue<String> wrong = new ConcurrentLinkedQueue<>();
        texts.parallelStream().forEach(t -> {
            int n = parser.parse(t, true).statements().size();
            if (n != (t.contains(";") ? 2 : 1)) {
                wrong.add(t);
            }
        });
        assertThat(wrong).isEmpty();
    }

    /**
     * Threat T31 (accepted with a fuzz suite): random and mutated SQL never crashes the process, never throws
     * from the binding, and every result is either statements or an error with a sane position.
     */
    @Test
    void fuzzedTextNeverBreaksTheParser() {
        Random r = Seeds.random("parser-fuzz");
        String[] pieces = {"SELECT", "'", "\"", "$$", "$a$", "/*", "*/", "--", "\n", ";", "E'\\", "U&'", "\\", "(", ")",
            "BEGIN ATOMIC", "END", "DO", "漢", "🙂", "\u0000", " ", "1", "x", "::", "[", "]", "{", "}", "%", "?"};
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = r.nextInt(40);
            for (int k = 0; k < n; k++) {
                if (r.nextInt(5) == 0) {
                    sb.appendCodePoint(r.nextInt(0x2FFFF) + 1);
                } else {
                    sb.append(pieces[r.nextInt(pieces.length)]);
                }
            }
            String sql = sb.toString().replace("\u0000", ""); // a C string can't carry NUL; the gate never sees one
            String scrubbed = new String(sql.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            for (boolean scs : new boolean[] {true, false}) {
                SqlParser.Parsed p = parser.parse(scrubbed, scs);
                if (p.ok()) {
                    assertThat(p.statements()).allSatisfy(s -> assertThat(scrubbed).contains(s.text()));
                } else {
                    assertThat(p.position()).isLessThanOrEqualTo(scrubbed.length() + 1);
                }
            }
        }
    }

    @Test
    void cellsAreCutOnCharacterBoundaries() {
        assertThat(ExecutionEngine.ResultBuilder.cut("abc", 2)).isEqualTo("ab");
        assertThat(ExecutionEngine.ResultBuilder.cut("éé", 3)).isEqualTo("é");
        assertThat(ExecutionEngine.ResultBuilder.cut("🙂🙂", 5)).isEqualTo("🙂");
        assertThat(ExecutionEngine.ResultBuilder.cut("漢", 2)).isEmpty();
    }

    @Test
    void aMissingLibraryFailsClearly() {
        assertThatThrownBy(() -> SqlParser.load(Path.of("no-such-dir", "pg_query.dll")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SQL parser library not found");
    }
}
