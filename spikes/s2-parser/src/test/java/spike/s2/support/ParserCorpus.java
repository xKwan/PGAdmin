package spike.s2.support;

import java.util.List;
import java.util.UUID;

/**
 * Plan §3.1 test corpus. Each item is a script with the number of statements PostgreSQL sees in it (the tests check
 * this number against the real server, not only against this table). {@code {u}} is replaced by a unique suffix so
 * that objects created by different items never collide.
 */
public final class ParserCorpus {

    /**
     * @param category plan §3.1 bullet the item covers
     * @param sql      script text
     * @param scsOff   parse and execute with standard_conforming_strings = off
     * @param expected number of statements, or -1 when PostgreSQL must reject the text as a syntax error
     */
    public record Item(String category, String sql, boolean scsOff, int expected) {
        public String instantiate() {
            return sql.replace("{u}", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }

        public boolean isError() {
            return expected < 0;
        }
    }

    public static final List<Item> ITEMS = List.of(
            // quotes
            new Item("quotes", "SELECT 'it''s; fine' AS x", false, 1),
            new Item("quotes", "SELECT \"we\"\"ird;col\" FROM (SELECT 1 AS \"we\"\"ird;col\") s; SELECT 2", false, 2),
            new Item("quotes", "SELECT '''' AS q; SELECT ''';''' AS q2", false, 2),
            // E'' and U&''
            new Item("E-strings", "SELECT E'\\';' AS x; SELECT 2", false, 2),
            new Item("E-strings", "SELECT E'a\\\\' AS x; SELECT E'\\x41;'", false, 2),
            new Item("U&-strings", "SELECT U&'\\0041;' AS x; SELECT U&'!0041;' UESCAPE '!'", false, 2),
            new Item("U&-strings", "SELECT U&\"\\0061\" FROM (SELECT 1 AS a) s", false, 1),
            // standard_conforming_strings on/off: the same text has a different number of statements
            new Item("scs", "SELECT 'a\\'; SELECT 1; --'", false, 2),
            new Item("scs", "SELECT 'a\\'; SELECT 1; --'", true, 1),
            new Item("scs", "SELECT 'x\\'' AS y; SELECT 2", true, 2),
            // comments
            new Item("comments", "SELECT 1 -- ; not a separator\n; SELECT 2", false, 2),
            new Item("comments", "/* a /* nested ; */ still comment ; */ SELECT 1; SELECT 2", false, 2),
            new Item("comments", "SELECT 1 /* ; */ ; /* ; */ SELECT 2 -- ;", false, 2),
            // dollar quoting
            new Item("dollar", "SELECT $$;$$; SELECT $a$ $$ ; $b$ $a$", false, 2),
            new Item("dollar", "SELECT $outer$ $inner$ ; $inner$ ; $outer$ AS x", false, 1),
            new Item("dollar", "SELECT $_$;$_$ AS x; SELECT $q1$'$q1$", false, 2),
            // DO and plpgsql
            new Item("do", "DO $$ BEGIN RAISE NOTICE 'x;'; PERFORM 1; END $$; SELECT 1", false, 2),
            new Item("do", "DO LANGUAGE plpgsql $body$ DECLARE v text := ';'; BEGIN v := v || $q$;$q$; END $body$", false, 1),
            new Item("plpgsql", "CREATE FUNCTION f_{u}() RETURNS int LANGUAGE plpgsql AS $f$ BEGIN PERFORM 1; RETURN 2; END $f$; SELECT f_{u}()", false, 2),
            new Item("plpgsql", "CREATE FUNCTION g_{u}(a int) RETURNS text LANGUAGE plpgsql AS ' BEGIN RETURN ''a;b''; END '; SELECT g_{u}(1)", false, 2),
            // SQL-standard bodies
            new Item("begin-atomic", "CREATE FUNCTION h_{u}() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT 2; END; SELECT h_{u}()", false, 2),
            new Item("begin-atomic", "CREATE PROCEDURE p_{u}() LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT 2; SELECT 3; END; CALL p_{u}()", false, 2),
            new Item("begin-atomic", "CREATE FUNCTION k_{u}() RETURNS int LANGUAGE sql RETURN 1; SELECT k_{u}()", false, 2),
            // rules with multiple actions
            new Item("rule", "CREATE TABLE rt_{u}(a int); CREATE TABLE rl_{u}(a int); "
                    + "CREATE RULE r_{u} AS ON INSERT TO rt_{u} DO ALSO (INSERT INTO rl_{u} VALUES (1); INSERT INTO rl_{u} VALUES (2)); "
                    + "INSERT INTO rt_{u} VALUES (0)", false, 4),
            // psql meta-commands are not SQL
            new Item("psql-meta", "\\dt", false, -1),
            new Item("psql-meta", "SELECT 1 \\gset", false, -1),
            new Item("psql-meta", "\\copy t FROM 'x.csv'", false, -1),
            new Item("psql-meta", "\\i other.sql", false, -1),
            // empty statements, trailing semicolons
            new Item("empty", ";;SELECT 1;;; SELECT 2;", false, 2),
            new Item("empty", "SELECT 1;", false, 1),
            new Item("empty", " ; ; ", false, 0),
            // Unicode identifiers and text (byte vs character offsets)
            new Item("unicode", "SELECT 1 AS \"数据;列\", 'ü;é' AS ünïcode; SELECT '😀;' AS e", false, 2),
            new Item("unicode", "CREATE TABLE \"表_{u};\" (\"列\" int); INSERT INTO \"表_{u};\" VALUES (1); SELECT \"列\" FROM \"表_{u};\"", false, 3),
            // operators and casts near separators
            new Item("operators", "SELECT '{\"a\":1}'::jsonb ? 'a'; SELECT 1::int8;SELECT-1", false, 3),
            new Item("operators", "SELECT (ARRAY[1,2])[1]; SELECT (1);SELECT 'x'||';'", false, 3),
            // CTE with data modification inside a statement that starts with WITH
            new Item("cte", "CREATE TABLE c_{u}(a int); WITH d AS (INSERT INTO c_{u} VALUES (1) RETURNING a) SELECT * FROM d", false, 2),
            // syntax errors anywhere make the whole text invalid
            new Item("syntax-error", "SELECT 1; SELEC 2", false, -1),
            new Item("syntax-error", "SELECT 'unterminated", false, -1),
            new Item("syntax-error", "SELECT $$unterminated", false, -1),
            new Item("syntax-error", "SELECT 1 /* unterminated", false, -1));

    private ParserCorpus() {
    }
}
