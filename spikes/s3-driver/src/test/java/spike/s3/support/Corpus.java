package spike.s3.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Single statements whose text contains semicolons or other characters a naive splitter gets wrong. Every entry is
 * exactly one SQL statement and returns one row with one column. Each run samples and shuffles a subset, and varies
 * leading whitespace and keyword case.
 */
public final class Corpus {

    private static final List<String> SINGLE = List.of(
            "SELECT 'a;b' AS x",
            "SELECT $$a;b$$ AS x",
            "SELECT $tag$ ; $$ ; $tag$ AS x",
            "SELECT 1 /* ; */ AS x",
            "SELECT 1 /* nested /* ; */ ; */ AS x",
            "SELECT 1 -- ; trailing comment\n AS x",
            "SELECT E'it\\'s; here' AS x",
            "SELECT \"a;b\" AS x FROM (SELECT 1 AS \"a;b\") s",
            "SELECT 1 AS \"x\"\"y;\"",
            "SELECT U&'\\0041;' AS x",
            "SELECT '{\"a\":1}'::jsonb ? 'a' AS x",
            "SELECT '{\"a\":1}'::jsonb ?| array['a', ';'] AS x",
            "WITH c AS (SELECT ';' AS s) SELECT s AS x FROM c",
            "SELECT 'back\\slash;' AS x",
            "SELECT B'101' AS x",
            "SELECT 'two'';quotes' AS x",
            "SELECT $_$ -- not a comment ; $_$ AS x",
            "SELECT length($q$/* ; */$q$) AS x",
            "SELECT ';' || chr(59) AS x",
            "SELECT 1 AS x;");

    private Corpus() {
    }

    public static List<String> all() {
        return SINGLE;
    }

    /** A random, shuffled sample of at least {@code min} entries with randomised surrounding whitespace. */
    public static List<String> sample(Random rnd, int min) {
        List<String> copy = new ArrayList<>(SINGLE);
        Collections.shuffle(copy, rnd);
        int n = min + rnd.nextInt(copy.size() - min + 1);
        List<String> out = new ArrayList<>();
        for (String s : copy.subList(0, n)) {
            out.add(vary(rnd, s));
        }
        return out;
    }

    /** Leading whitespace and the case of the first keyword vary; the statement's meaning does not. */
    static String vary(Random rnd, String sql) {
        String[] lead = {"", " ", "\n", "\t", "  \n "};
        String s = sql;
        if (rnd.nextBoolean() && s.startsWith("SELECT")) {
            s = "select" + s.substring("SELECT".length());
        }
        return lead[rnd.nextInt(lead.length)] + s;
    }

    /** Trivial multi-statement scripts: {@code count} SELECTs with distinct results. */
    public static String multi(Random rnd, int count, List<Integer> expected) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int v = rnd.nextInt(1_000_000);
            expected.add(v);
            if (i > 0) {
                sb.append(rnd.nextBoolean() ? "; " : ";\n");
            }
            sb.append("SELECT ").append(v).append(" AS v");
        }
        if (rnd.nextBoolean()) {
            sb.append(';');
        }
        return sb.toString();
    }

    /** What PostgreSQL logs for a statement: surrounding whitespace and one trailing semicolon removed. */
    public static String canonical(String sql) {
        String s = sql.strip();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }
}
