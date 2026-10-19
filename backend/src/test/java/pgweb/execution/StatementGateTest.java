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
    void scriptsAreSplitByThePostgresParserAndEveryStatementPassesTheGate() {
        String sql = "SELECT '漢;字'; -- a comment; with semicolons\nUPDATE t SET a = 1 WHERE b; DO $$BEGIN PERFORM 1; END$$;\nSELECT 3";
        StatementGate.ScriptVerdict v = gate.checkScript(sql, true, 10);
        assertThat(v.approved()).isTrue();
        assertThat(v.statements()).hasSize(4);
        assertThat(v.statements()).extracting(s -> s.verdict().statementClass()).containsExactly("select", "dml", "procedural", "select");
        for (StatementGate.ScriptStatement s : v.statements()) {
            // The offset is a Java string index: the statement's own text starts there, even after multi-byte characters.
            assertThat(sql.substring(s.offset())).startsWith(s.verdict().statement().text());
        }
        assertThat(v.statements().get(2).verdict().statement().text()).contains("PERFORM 1; END");

        // Where statements end depends on standard_conforming_strings, exactly as on the server.
        String backslash = "SELECT 'a\\'; SELECT 2'; SELECT 3";
        assertThat(gate.checkScript(backslash, false, 10).statements()).hasSize(2);
        assertThat(gate.checkScript(backslash, true, 10).rejection()).isEqualTo(StatementGate.Rejection.SYNTAX_ERROR);
    }

    @Test
    void aScriptIsRefusedWholeWhenAnyPartIsRefused() {
        StatementGate.ScriptVerdict tooMany = gate.checkScript("SELECT 1; SELECT 2; SELECT 3", true, 2);
        assertThat(tooMany.rejection()).isEqualTo(StatementGate.Rejection.TOO_MANY_STATEMENTS);
        assertThat(tooMany.message()).contains("3 statements").contains("at most 2");
        assertThat(gate.checkScript(";; -- nothing", true, 10).rejection()).isEqualTo(StatementGate.Rejection.EMPTY_STATEMENT);
        assertThat(gate.checkScript(null, true, 10).rejection()).isEqualTo(StatementGate.Rejection.EMPTY_STATEMENT);
        assertThat(gate.checkScript("SELECT '" + "x".repeat(1100) + "'", true, 10).rejection()).isEqualTo(StatementGate.Rejection.TOO_LARGE);
        assertThat(gate.checkScript("SELECT 1; SELECT 2\u0000", true, 10).rejection()).isEqualTo(StatementGate.Rejection.SYNTAX_ERROR);

        StatementGate.ScriptVerdict syntax = gate.checkScript("SELECT 1; SELEC 2", true, 10);
        assertThat(syntax.rejection()).isEqualTo(StatementGate.Rejection.SYNTAX_ERROR);
        assertThat(syntax.position()).isEqualTo(11); // in the whole script
        assertThat(syntax.statements()).isEmpty();

        StatementGate.ScriptVerdict copy = gate.checkScript("SELECT 1;\nCOPY t FROM STDIN", true, 10);
        assertThat(copy.rejection()).isEqualTo(StatementGate.Rejection.UNSUPPORTED);
        assertThat(copy.statementIndex()).isEqualTo(1);
        assertThat(copy.position()).isEqualTo(11); // PostgreSQL 18 starts a statement at its first token, after the newline
        assertThat(copy.message()).startsWith("Statement 2: ");
        assertThat(gate.checkScript("SELECT 1; LISTEN c", true, 10).statementIndex()).isEqualTo(1);
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

    static List<String> reasons(String sql) {
        return StatementGate.destructiveReasons(gate.check(sql, true).statement());
    }

    @Test
    void destructiveStatementsAreRecognisedFromTheParseTree() {
        assertThat(reasons("SELECT 1")).isEmpty();
        assertThat(reasons("INSERT INTO t VALUES (1)")).isEmpty();
        assertThat(reasons("UPDATE t SET a = 1 WHERE b = 2")).isEmpty();
        assertThat(reasons("DELETE FROM t WHERE id = 1")).isEmpty();
        assertThat(reasons("UPDATE t SET a = 1")).containsExactly("no_where");
        assertThat(reasons("DELETE FROM t")).containsExactly("no_where");
        assertThat(reasons("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d")).containsExactly("no_where");
        assertThat(reasons("WITH d AS (UPDATE t SET a = 1 WHERE a = 2 RETURNING *) SELECT * FROM d")).isEmpty();
        assertThat(reasons("DROP TABLE t")).containsExactly("drop");
        assertThat(reasons("DROP SCHEMA s CASCADE")).containsExactly("drop");
        assertThat(reasons("TRUNCATE t")).containsExactly("truncate");
        assertThat(reasons("GRANT SELECT ON t TO r")).containsExactly("privileges");
        assertThat(reasons("REVOKE r FROM u")).containsExactly("privileges");
        assertThat(reasons("CREATE TABLE t2 (a int)")).containsExactly("ddl");
        assertThat(reasons("ALTER TABLE t ADD COLUMN b int")).containsExactly("ddl");
        assertThat(reasons("DO $$ BEGIN DELETE FROM t; END $$")).containsExactly("procedural");
        // EXPLAIN only plans; EXPLAIN ANALYZE runs the statement (admin Phase 3).
        assertThat(reasons("EXPLAIN DELETE FROM t")).isEmpty();
        assertThat(reasons("EXPLAIN (FORMAT JSON) UPDATE t SET a = 1")).isEmpty();
        assertThat(reasons("EXPLAIN ANALYZE SELECT 1")).containsExactly("explain_analyze");
        assertThat(reasons("EXPLAIN (ANALYZE, FORMAT JSON) DELETE FROM t")).containsExactly("explain_analyze", "no_where");
        assertThat(reasons("EXPLAIN (ANALYZE true) DELETE FROM t WHERE a = 1")).containsExactly("explain_analyze");
        assertThat(reasons("EXPLAIN (ANALYZE on) SELECT 1")).containsExactly("explain_analyze");
        assertThat(reasons("EXPLAIN (ANALYZE 1) SELECT 1")).containsExactly("explain_analyze");
        assertThat(reasons("EXPLAIN (ANALYZE false) DELETE FROM t")).isEmpty();
        assertThat(reasons("EXPLAIN (ANALYZE off) SELECT 1")).isEmpty();
        assertThat(reasons("EXPLAIN (ANALYZE 0) SELECT 1")).isEmpty();
        assertThat(reasons("EXPLAIN (ANALYZE false, ANALYZE) SELECT 1")).containsExactly("explain_analyze");
        assertThat(reasons("EXPLAIN ANALYZE CREATE TABLE t3 AS SELECT 1")).containsExactly("explain_analyze", "ddl");
        assertThat(StatementGate.isCommit(gate.check("COMMIT", true).statement())).isTrue();
        assertThat(StatementGate.isCommit(gate.check("END", true).statement())).isTrue();
        assertThat(StatementGate.isCommit(gate.check("ROLLBACK", true).statement())).isFalse();
        assertThat(StatementGate.isCommit(gate.check("SELECT 1", true).statement())).isFalse();
    }

    @Test
    void isolationLevelsComeFromAnAllowList() {
        assertThat(ExecutionEngine.isolationClause(null)).isEmpty();
        assertThat(ExecutionEngine.isolationClause(" ")).isEmpty();
        assertThat(ExecutionEngine.isolationClause("Serializable")).isEqualTo(" ISOLATION LEVEL SERIALIZABLE");
        assertThat(ExecutionEngine.isolationClause("repeatable read")).isEqualTo(" ISOLATION LEVEL REPEATABLE READ");
        assertThat(ExecutionEngine.isolationClause("read committed")).isEqualTo(" ISOLATION LEVEL READ COMMITTED");
        assertThatThrownBy(() -> ExecutionEngine.isolationClause("read uncommitted")).hasMessageContaining("isolation must be");
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
