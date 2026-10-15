package spike.s2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import spike.s2.support.Findings;
import spike.s2.support.PgQuery;
import spike.s2.support.SpikeExtension;

import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Re-verifies, on every run, why the audit text does not use libpg_query's normaliser (S2 findings N1, N2). If an
 * upgrade changes this behaviour the test fails and the decision is revisited.
 */
@ExtendWith(SpikeExtension.class)
class NormalizerFindingsTest {

    /** Templates where pg_query_normalize (18.1.0) leaves the literal in place. */
    static final Set<String> NORMALIZE_LEAKS = Set.of(
            "CREATE SERVER s FOREIGN DATA WRAPPER w OPTIONS (host '{s}')",
            "PREPARE p AS SELECT '{s}'",
            "EXECUTE p('{s}')",
            "COPY t FROM PROGRAM 'echo {s}'",
            "CREATE FUNCTION f2() RETURNS text LANGUAGE sql BEGIN ATOMIC SELECT '{s}'; END",
            "COMMENT ON TABLE t IS '{s}'",
            "CREATE TABLE t2 (a text DEFAULT '{s}')");

    private static String fill(String template, String s, String n) {
        return template.replace("{s}", s).replace("{n}", n).replace("{d}", "55");
    }

    @Test
    void pgQueryNormalizeLeaksInSomeUtilityStatements(PgQuery parser, Random rnd, Findings findings) {
        String s = "Zq" + Long.toString(Math.abs(rnd.nextLong()), 36);
        String n = String.valueOf(700_000 + rnd.nextInt(200_000));
        Set<String> leaking = new TreeSet<>();
        for (String t : NormalizationTest.TEMPLATES) {
            String out = parser.normalize(parser.stripComments(fill(t, s, n)), false);
            if (out.contains(s) || out.contains(n)) {
                leaking.add(t);
            }
        }
        findings.record("normalize", "N2_pg_query_normalize_leaks", leaking);
        assertEquals(new TreeSet<>(NORMALIZE_LEAKS), leaking);
    }

    @Test
    void pgQueryNormalizeUtilitySkipsDml(PgQuery parser, Random rnd, Findings findings) {
        String s = "Zq" + Long.toString(Math.abs(rnd.nextLong()), 36);
        String out = parser.normalize("SELECT * FROM t WHERE a = '" + s + "'", true);
        assertEquals(true, out.contains(s), out);
        findings.record("normalize", "N1_normalize_utility_skips_dml", out.replace(s, "<secret>"));
    }
}
