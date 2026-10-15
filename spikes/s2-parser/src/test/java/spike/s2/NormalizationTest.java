package spike.s2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import spike.s2.support.Findings;
import spike.s2.support.PgQuery;
import spike.s2.support.SpikeExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §6 / S2: the audit text must contain no literal values and no comments. Every template embeds a fresh random
 * secret; the secret must not survive {@link PgQuery#auditText(String)}.
 */
@ExtendWith(SpikeExtension.class)
class NormalizationTest {

    public static final List<String> TEMPLATES = List.of(
            "SELECT * FROM t WHERE a = '{s}' AND b = {n}",
            "INSERT INTO t VALUES ('{s}', {n}, E'{s}\\n')",
            "UPDATE t SET a = $${s}$$ WHERE id = {n}",
            "UPDATE t SET a = $tag${s}$tag$ WHERE id = {n}",
            "DELETE FROM t WHERE a IN ('{s}', 'x') RETURNING *",
            "SELECT U&'{s}' AS u",
            "SELECT 1 /* {s} */",
            "SELECT 1 -- {s}\n",
            "/* {s} */ SELECT 1",
            "CREATE ROLE r1 LOGIN PASSWORD '{s}'",
            "ALTER ROLE r1 PASSWORD '{s}' VALID UNTIL '2030-01-01'",
            "ALTER USER r1 WITH ENCRYPTED PASSWORD '{s}'",
            "CREATE USER MAPPING FOR r1 SERVER s OPTIONS (user 'u', password '{s}')",
            "CREATE SERVER s FOREIGN DATA WRAPPER w OPTIONS (host '{s}')",
            "SET my.secret = '{s}'",
            "SELECT set_config('my.secret', '{s}', false)",
            "PREPARE p AS SELECT '{s}'",
            "EXECUTE p('{s}')",
            "COPY t FROM PROGRAM 'echo {s}'",
            "DO $$ BEGIN PERFORM '{s}'; END $$",
            "CREATE FUNCTION f() RETURNS text LANGUAGE sql AS $$ SELECT '{s}' $$",
            "CREATE FUNCTION f2() RETURNS text LANGUAGE sql BEGIN ATOMIC SELECT '{s}'; END",
            "COMMENT ON TABLE t IS '{s}'",
            "CREATE TABLE t2 (a text DEFAULT '{s}')",
            "SELECT '{s}'::text, {n}::int, {n}.5, 1e{d}",
            "SELECT * FROM t LIMIT {n} OFFSET {n}",
            "SELECT ARRAY['{s}', 'b']",
            "SELECT '{\"k\": \"{s}\"}'::jsonb");

    private static String secret(Random rnd) {
        String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("Zq");
        for (int i = 0; i < 14; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    @Test
    void noLiteralOrCommentSurvives(PgQuery parser, Random rnd, Findings findings) {
        Map<String, String> leaks = new LinkedHashMap<>();
        Map<String, String> samples = new LinkedHashMap<>();
        for (String template : TEMPLATES) {
            String s = secret(rnd);
            // A number unlikely to occur in normalised text by accident ($1..$n placeholders are small).
            String n = String.valueOf(700_000 + rnd.nextInt(200_000));
            String sql = template.replace("{s}", s).replace("{n}", n).replace("{d}", String.valueOf(10 + rnd.nextInt(80)));
            String audit = parser.auditText(sql);
            samples.put(template, audit);
            if (audit.contains(s) || audit.contains(n)) {
                leaks.put(template, audit);
            }
        }
        findings.record("normalize", "samples", samples);
        findings.record("normalize", "leaks", leaks);
        assertTrue(leaks.isEmpty(), "values survived normalisation: " + leaks);
    }

    /** Normalisation keeps statement structure (identifiers stay, placeholders appear) and is deterministic. */
    @Test
    void structureIsKeptAndStable(PgQuery parser, Random rnd) {
        String s = secret(rnd);
        String sql = "SELECT name, email FROM customers WHERE email = '" + s + "' AND id > " + rnd.nextInt(1000);
        String a = parser.auditText(sql);
        String b = parser.auditText(sql.replace(s, secret(rnd)));
        assertEquals(a, b, "different literals give the same normalised text");
        assertTrue(a.contains("customers") && a.contains("email") && a.contains("$1") && a.contains("$2"), a);
        assertFalse(a.contains(s));
    }

    /** Comment removal uses the PostgreSQL scanner, so comment markers inside strings are not touched. */
    @Test
    void commentMarkersInsideStringsAreNotComments(PgQuery parser, Random rnd) {
        String s = secret(rnd);
        String sql = "SELECT '-- not a comment " + s + "', '/* nor this */' /* real " + s + " */ -- real " + s + "\n";
        String stripped = parser.stripComments(sql);
        assertTrue(stripped.contains("'-- not a comment " + s + "'"), stripped);
        assertTrue(stripped.contains("'/* nor this */'"), stripped);
        assertEquals(1, countOccurrences(stripped, s), "only the literal copy remains: " + stripped);
        List<String> none = new ArrayList<>();
        String audit = parser.auditText(sql);
        if (audit.contains(s)) {
            none.add(audit);
        }
        assertTrue(none.isEmpty(), "audit text still holds the value: " + none);
    }

    private static int countOccurrences(String hay, String needle) {
        int c = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) {
            c++;
        }
        return c;
    }
}
