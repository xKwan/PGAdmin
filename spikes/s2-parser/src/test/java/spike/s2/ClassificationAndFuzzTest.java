package spike.s2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import spike.s2.support.EphemeralPostgres;
import spike.s2.support.Findings;
import spike.s2.support.PgQuery;
import spike.s2.support.ServerTruth;
import spike.s2.support.SpikeExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S2: statement classification, performance, and a differential fuzz test against the server. */
@ExtendWith(SpikeExtension.class)
class ClassificationAndFuzzTest {

    private static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("SELECT 1", "SelectStmt");
        KINDS.put("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", "SelectStmt");
        KINDS.put("INSERT INTO t VALUES (1)", "InsertStmt");
        KINDS.put("UPDATE t SET a = 1", "UpdateStmt");
        KINDS.put("DELETE FROM t", "DeleteStmt");
        KINDS.put("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE", "MergeStmt");
        KINDS.put("CREATE TABLE t (a int)", "CreateStmt");
        KINDS.put("DROP TABLE t", "DropStmt");
        KINDS.put("TRUNCATE t", "TruncateStmt");
        KINDS.put("ALTER TABLE t ADD COLUMN b int", "AlterTableStmt");
        KINDS.put("BEGIN", "TransactionStmt");
        KINDS.put("COMMIT", "TransactionStmt");
        KINDS.put("SET search_path = x", "VariableSetStmt");
        KINDS.put("SHOW search_path", "VariableShowStmt");
        KINDS.put("DO $$ BEGIN END $$", "DoStmt");
        KINDS.put("EXPLAIN ANALYZE DELETE FROM t", "ExplainStmt");
        KINDS.put("COPY t TO STDOUT", "CopyStmt");
        KINDS.put("GRANT SELECT ON t TO r", "GrantStmt");
        KINDS.put("CREATE ROLE r", "CreateRoleStmt");
        KINDS.put("VACUUM t", "VacuumStmt");
        KINDS.put("LISTEN c", "ListenStmt");
        KINDS.put("CALL p()", "CallStmt");
        KINDS.put("LOCK TABLE t", "LockStmt");
    }

    /**
     * Top-level node kinds are reported correctly. Note the second entry: a data-modifying CTE is still a SelectStmt
     * at the top level, which is why classification is used for UX and audit only, never as a security boundary.
     */
    @Test
    void statementKinds(PgQuery parser, Random rnd, Findings findings) {
        List<String> keys = new ArrayList<>(KINDS.keySet());
        java.util.Collections.shuffle(keys, rnd);
        for (String sql : keys) {
            PgQuery.Parsed p = parser.parse(sql, false);
            assertTrue(p.ok(), sql + ": " + p.error());
            assertEquals(1, p.stmts().size());
            assertEquals(KINDS.get(sql), p.stmts().get(0).kind(), sql);
        }
        findings.record("classify", "kinds", KINDS.size() + " statement kinds correct; data-modifying CTE reports SelectStmt at top level");
    }

    /** Plan S2 criterion: under 5 ms per typical statement (parse + audit normalisation). */
    @Test
    void performance(PgQuery parser, Random rnd, Findings findings) {
        List<String> typical = new ArrayList<>(List.of(
                "SELECT id, name FROM customers WHERE email = 'a@b.c'",
                "SELECT c.id, sum(o.total) FROM customers c JOIN orders o ON o.customer_id = c.id WHERE o.created_at > now() - interval '30 days' GROUP BY c.id HAVING sum(o.total) > 100 ORDER BY 2 DESC LIMIT 50",
                "UPDATE accounts SET balance = balance - 10 WHERE id = 42 RETURNING balance",
                "INSERT INTO audit_events (kind, payload) VALUES ('x', '{\"a\":1}'::jsonb)",
                "CREATE TABLE t_perf (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name text NOT NULL, created timestamptz DEFAULT now())",
                "DO $$ DECLARE r record; BEGIN FOR r IN SELECT * FROM t LOOP RAISE NOTICE '%', r; END LOOP; END $$"));
        StringBuilder big = new StringBuilder("SELECT ");
        for (int i = 0; i < 400; i++) {
            big.append(i == 0 ? "" : ", ").append("col").append(i).append(" + ").append(i);
        }
        typical.add(big.append(" FROM wide_table WHERE a = 'x'").toString());
        for (int i = 0; i < 2000; i++) { // warm-up
            parser.parse(typical.get(i % typical.size()), false);
        }
        int iterations = 2000 + rnd.nextInt(2000);
        long[] nanos = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            String sql = typical.get(rnd.nextInt(typical.size()));
            long t0 = System.nanoTime();
            parser.parse(sql, false);
            parser.auditText(sql);
            nanos[i] = System.nanoTime() - t0;
        }
        Arrays.sort(nanos);
        double p50 = nanos[iterations / 2] / 1e6;
        double p99 = nanos[(int) (iterations * 0.99)] / 1e6;
        double max = nanos[iterations - 1] / 1e6;
        findings.record("performance", "parse_plus_audit_ms", Map.of("iterations", iterations, "p50", p50, "p99", p99, "max", max,
                "largestStatementChars", big.length()));
        assertTrue(p99 < 5.0, "p99 " + p99 + " ms");
    }

    /**
     * Differential fuzzing: random token soup is given to the parser and to the server. Whenever the parser rejects
     * text, the server must reject it too before running anything; whenever the parser accepts text, the server must
     * not report a syntax error, and a fully successful server run must have the same statement count.
     */
    @Test
    void fuzzAgainstServer(EphemeralPostgres pg, PgQuery parser, Random rnd, Findings findings) throws Exception {
        String[] alphabet = {"SELECT ", "SELECT 1", "1", "'", "''", "\"", ";", "; ", "$$", "$a$", "--", "/*", "*/", "\n", " ",
                "E'", "U&'", "\\", "\\'", "a", "(", ")", ",", "x", " AS ", "::int", "'z'", "ü", "数"};
        int cases = 250 + rnd.nextInt(150);
        Map<String, Integer> tally = new TreeMap<>();
        List<String> disagreements = new ArrayList<>();
        try (ServerTruth on = new ServerTruth(pg, "fuzz_on_" + UUID.randomUUID().toString().substring(0, 6), false);
             ServerTruth off = new ServerTruth(pg, "fuzz_off_" + UUID.randomUUID().toString().substring(0, 6), true)) {
            for (int c = 0; c < cases; c++) {
                StringBuilder sb = new StringBuilder();
                int len = 1 + rnd.nextInt(14);
                for (int i = 0; i < len; i++) {
                    sb.append(alphabet[rnd.nextInt(alphabet.length)]);
                }
                String sql = sb.toString();
                boolean scsOff = rnd.nextInt(4) == 0;
                PgQuery.Parsed parsed = parser.parse(sql, scsOff);
                ServerTruth.Outcome truth = (scsOff ? off : on).run(sql);
                String key;
                if (!parsed.ok()) {
                    key = "parser-reject/server-" + (truth.ok() ? "ACCEPT" : truth.sqlState());
                    if (truth.ok()) {
                        disagreements.add("parser rejected, server ran " + truth.count() + ": " + sql);
                    }
                } else if (!truth.ok()) {
                    key = "parser-accept/server-" + truth.sqlState();
                    if ("42601".equals(truth.sqlState())) {
                        disagreements.add("parser accepted, server syntax error: " + sql);
                    }
                } else {
                    key = "both-accept";
                    if (truth.count() != parsed.stmts().size()) {
                        disagreements.add("count parser=" + parsed.stmts().size() + " server=" + truth.count() + ": " + sql);
                    }
                }
                tally.merge(key + (scsOff ? " (scs off)" : ""), 1, Integer::sum);
            }
        }
        findings.record("fuzz", "tally", Map.of("cases", cases, "outcomes", tally, "disagreements", disagreements));
        assertTrue(disagreements.isEmpty(), "parser and server disagree: " + disagreements);
    }

    /**
     * Structured fuzzing: mostly valid scripts (literals full of separators and quote characters, comments between
     * tokens, varied separators), a share of them mutated by one random edit. Gives far more accepted inputs than
     * token soup, so statement counts are compared on many valid scripts.
     */
    @Test
    void structuredFuzzAgainstServer(EphemeralPostgres pg, PgQuery parser, Random rnd, Findings findings) throws Exception {
        String[] atomsOn = {"'a;b'", "'it''s;'", "E'x\\';y'", "$$;$$", "$t$ $$ ; $t$", "U&'\\0041;'", "1", "-2.5e3", "B'101'",
                "X'1F'", "(1)", "ARRAY[1,2]", "'{\"a\":\";\"}'::jsonb", "NULL", "'--;'", "'/*;*/'", "$_$'$_$", "'数;'"};
        String[] atomsOff = {"'a;b'", "'it''s;'", "'x\\';y'", "$$;$$", "1", "'--;'", "'/*;*/'", "E'\\\\;'", "'q\\\\'"};
        String[] gaps = {" ", "  ", "\n", " /* ; */ ", " -- ;\n", "\t"};
        String[] seps = {";", "; ", ";\n", ";;", " ; -- c\n", ";/* x */"};
        String[] mutations = {"'", "$$", "/*", "\"", ";", "\\", "E'", "--", "*/", "$a$"};
        int cases = 150 + rnd.nextInt(100);
        Map<String, Integer> tally = new TreeMap<>();
        List<String> disagreements = new ArrayList<>();
        try (ServerTruth on = new ServerTruth(pg, "sfuzz_on_" + UUID.randomUUID().toString().substring(0, 6), false);
             ServerTruth off = new ServerTruth(pg, "sfuzz_off_" + UUID.randomUUID().toString().substring(0, 6), true)) {
            for (int c = 0; c < cases; c++) {
                boolean scsOff = rnd.nextInt(4) == 0;
                String[] atoms = scsOff ? atomsOff : atomsOn;
                StringBuilder sb = new StringBuilder();
                if (rnd.nextInt(4) == 0) {
                    sb.append(gaps[rnd.nextInt(gaps.length)]);
                }
                int stmts = 1 + rnd.nextInt(5);
                for (int s = 0; s < stmts; s++) {
                    if (s > 0) {
                        sb.append(seps[rnd.nextInt(seps.length)]);
                    }
                    sb.append("SELECT");
                    int cols = 1 + rnd.nextInt(4);
                    for (int k = 0; k < cols; k++) {
                        sb.append(k == 0 ? gaps[rnd.nextInt(gaps.length)] : "," + gaps[rnd.nextInt(gaps.length)]);
                        sb.append(atoms[rnd.nextInt(atoms.length)]);
                    }
                }
                if (rnd.nextBoolean()) {
                    sb.append(seps[rnd.nextInt(seps.length)]);
                }
                boolean mutated = rnd.nextInt(10) < 4;
                if (mutated) {
                    int pos = rnd.nextInt(sb.length() + 1);
                    if (rnd.nextBoolean() && pos < sb.length()) {
                        sb.deleteCharAt(pos);
                    } else {
                        sb.insert(pos, mutations[rnd.nextInt(mutations.length)]);
                    }
                }
                String sql = sb.toString();
                PgQuery.Parsed parsed = parser.parse(sql, scsOff);
                ServerTruth.Outcome truth = (scsOff ? off : on).run(sql);
                String key;
                if (!parsed.ok()) {
                    key = "parser-reject/server-" + (truth.ok() ? "ACCEPT" : truth.sqlState());
                    if (truth.ok()) {
                        disagreements.add("parser rejected, server ran " + truth.count() + ": " + sql);
                    }
                } else if (!truth.ok()) {
                    key = "parser-accept/server-" + truth.sqlState();
                    if ("42601".equals(truth.sqlState()) || "SENTINEL".equals(truth.sqlState())) {
                        disagreements.add("parser accepted, server " + truth.sqlState() + ": " + sql);
                    }
                } else {
                    key = "both-accept";
                    if (truth.count() != parsed.stmts().size()) {
                        disagreements.add("count parser=" + parsed.stmts().size() + " server=" + truth.count() + ": " + sql);
                    }
                }
                tally.merge(key + (scsOff ? " (scs off)" : "") + (mutated ? " [mutated]" : ""), 1, Integer::sum);
            }
        }
        findings.record("fuzz", "structured_tally", Map.of("cases", cases, "outcomes", tally, "disagreements", disagreements));
        assertTrue(disagreements.isEmpty(), "parser and server disagree: " + disagreements);
    }
}
