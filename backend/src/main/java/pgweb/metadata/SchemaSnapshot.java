package pgweb.metadata;

import pgweb.dbsession.DbSession;
import pgweb.dbsession.DbSessionManager;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A schema's structure for schema comparison (admin Phase 8, read-only): its tables with their columns, constraints,
 * indexes and triggers, its views, materialised views, sequences, functions and procedures. The browser compares two
 * snapshots and writes the SQL that would make one like the other into an editor tab; nothing here, and nothing in the
 * comparison, runs anything.
 * <ul>
 *   <li>Read on the companion as the user's own role, with the object browser's rules: constant queries, the schema
 *       only ever as a bound parameter, a timeout per query, one read at a time, a {@code metadata_browse} record.</li>
 *   <li>Read in one read-only transaction with {@code search_path} set (locally) to the compared schema alone, so that
 *       PostgreSQL writes names in that schema without their schema and qualifies everything else. Definitions then
 *       mean the same in another schema once its name is the search path, which is how the generated SQL runs.
 *       Function headers, always qualified by PostgreSQL, are written without the schema here for the same reason.</li>
 *   <li>Objects that belong to an extension, and sequences of identity columns, are left out: they come with the
 *       extension or the column. Bounded: a schema with more objects or text than the limits is refused.</li>
 * </ul>
 */
public final class SchemaSnapshot {

    /** Only the compared schema on the search path, for this transaction. Parameter: schema. */
    static final String SEARCH_PATH = "SELECT pg_catalog.set_config('search_path', pg_catalog.quote_ident(?), true)";

    /** The schema itself (to tell "not found" from "empty"). Parameter: schema. */
    static final String SCHEMA_EXISTS = "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?";

    /**
     * Relations that aren't part of an extension or an identity column, with view queries (a partitioned table's
     * internal dependencies on its own key columns don't count). Parameters: schema, limit.
     */
    static final String RELATIONS = """
            SELECT c.relname, c.relkind, c.relispartition, c.relpersistence,
                   CASE WHEN c.relkind IN ('v', 'm') THEN pg_catalog.pg_get_viewdef(c.oid, true) END
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'f', 'v', 'm', 'S')
              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                              WHERE d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass AND d.objid = c.oid
                                AND d.deptype IN ('e', 'i') AND d.refobjid <> c.oid)
            ORDER BY c.relname
            LIMIT ?""";

    /** Columns of the schema's tables: type, NOT NULL, default, identity, generation, collation. Parameter: schema. */
    static final String COLUMNS = """
            SELECT c.relname, a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid), a.attidentity, a.attgenerated,
                   CASE WHEN a.attcollation <> t.typcollation THEN pg_catalog.quote_ident(co.collname) END
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            LEFT JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            LEFT JOIN pg_catalog.pg_collation co ON co.oid = a.attcollation
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'f') AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum""";

    /** Primary key, unique, check, foreign key and exclusion constraints (NOT NULL is on the columns). Parameter: schema. */
    static final String CONSTRAINTS = """
            SELECT c.relname, r.conname, r.contype, pg_catalog.pg_get_constraintdef(r.oid, true)
            FROM pg_catalog.pg_constraint r
            JOIN pg_catalog.pg_class c ON c.oid = r.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND r.contype IN ('p', 'u', 'c', 'f', 'x') AND r.conparentid = 0
            ORDER BY c.relname, r.conname""";

    /**
     * Indexes that don't belong to a constraint, on tables and materialised views, with the table's qualified and
     * bare name (pg_get_indexdef always qualifies the table). Parameter: schema.
     */
    static final String INDEXES = """
            SELECT t.relname, i.relname, pg_catalog.pg_get_indexdef(x.indexrelid),
                   pg_catalog.quote_ident(n.nspname) || '.' || pg_catalog.quote_ident(t.relname), pg_catalog.quote_ident(t.relname),
                   pg_catalog.quote_ident(i.relname)
            FROM pg_catalog.pg_index x
            JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid
            JOIN pg_catalog.pg_class t ON t.oid = x.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = ?
              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint r WHERE r.conindid = x.indexrelid AND r.conrelid = x.indrelid)
            ORDER BY t.relname, i.relname""";

    /** User triggers that weren't cloned from a parent table. Parameter: schema. */
    static final String TRIGGERS = """
            SELECT c.relname, t.tgname, pg_catalog.pg_get_triggerdef(t.oid, true)
            FROM pg_catalog.pg_trigger t
            JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND NOT t.tgisinternal AND t.tgparentid = 0
            ORDER BY c.relname, t.tgname""";

    /** Sequence options. Parameter: schema. */
    static final String SEQUENCES = """
            SELECT c.relname, pg_catalog.format_type(s.seqtypid, NULL), s.seqincrement, s.seqmin, s.seqmax, s.seqstart,
                   s.seqcache, s.seqcycle
            FROM pg_catalog.pg_sequence s
            JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
            ORDER BY c.relname""";

    /**
     * Functions and procedures outside extensions, with their definitions and the qualified name PostgreSQL puts in
     * the header. Parameters: schema, limit.
     */
    static final String ROUTINES = """
            SELECT p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid), p.prokind,
                   pg_catalog.pg_get_functiondef(p.oid),
                   pg_catalog.quote_ident(n.nspname) || '.' || pg_catalog.quote_ident(p.proname), pg_catalog.quote_ident(p.proname)
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.prokind IN ('f', 'p')
              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                              WHERE d.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass AND d.objid = p.oid AND d.deptype = 'e')
            ORDER BY 1, 2
            LIMIT ?""";

    static final int MAX_RELATIONS = 2000;
    static final int MAX_ROUTINES = 2000;
    static final long MAX_TEXT_CHARS = 8_000_000;

    private static final Map<String, String> RELKINDS = Map.of("r", "table", "p", "partitioned_table", "f", "foreign_table",
            "v", "view", "m", "materialized_view", "S", "sequence");
    private static final Map<String, String> CONTYPES = Map.of("p", "primary_key", "u", "unique", "c", "check",
            "f", "foreign_key", "x", "exclusion");
    private static final String NAME = "name";
    private static final String TABLE = "table";
    private static final String DEFINITION = "definition";

    private final MetadataService metadata;
    private final DbSessionManager sessions;

    public SchemaSnapshot(MetadataService metadata, DbSessionManager sessions) {
        this.metadata = metadata;
        this.sessions = sessions;
    }

    /** The structure of one schema of the session's database, as the session's role sees it. */
    public Map<String, Object> snapshot(UserContext user, String sessionId, String schema) {
        MetadataService.requireName(schema, "schema");
        DbSession s = sessions.get(user, sessionId);
        Map<String, Object> out = metadata.onCompanion(user, s, "snapshot", schema, c -> readOnly(c, () -> read(c, schema)));
        if (out == null) {
            throw ApiException.notFound();
        }
        Map<String, Object> m = metadata.envelope("snapshot", out);
        m.put("database", s.database());
        m.put("schema", schema);
        return m;
    }

    private interface Body<T> {
        T run() throws SQLException;
    }

    /** Runs in a read-only transaction that is always rolled back, so the local search_path ends with it. */
    private static <T> T readOnly(Connection c, Body<T> body) throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        boolean readOnly = c.isReadOnly();
        c.setAutoCommit(false);
        try {
            c.setReadOnly(true);
            return body.run();
        } finally {
            try {
                c.rollback();
            } finally {
                c.setAutoCommit(autoCommit);
                c.setReadOnly(readOnly);
            }
        }
    }

    private static Map<String, Object> read(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = MetadataService.prepare(c, SCHEMA_EXISTS)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
            }
        }
        try (PreparedStatement ps = MetadataService.prepare(c, SEARCH_PATH)) {
            ps.setString(1, schema);
            ps.executeQuery().close();
        }
        long[] chars = {0};
        Set<String> tables = new HashSet<>();
        Set<String> sequenceNames = new HashSet<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        try (PreparedStatement ps = MetadataService.prepare(c, RELATIONS)) {
            ps.setString(1, schema);
            ps.setInt(2, MAX_RELATIONS + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = RELKINDS.get(rs.getString(2));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put(NAME, rs.getString(1));
                    r.put("kind", kind);
                    if (rs.getBoolean(3)) {
                        r.put("partition", true);
                    }
                    if ("u".equals(rs.getString(4))) {
                        r.put("unlogged", true);
                    }
                    putText(r, DEFINITION, stripSemicolon(rs.getString(5)), chars);
                    if (kind.endsWith(TABLE)) {
                        tables.add(rs.getString(1));
                    } else if (kind.equals("sequence")) {
                        sequenceNames.add(rs.getString(1));
                    }
                    relations.add(r);
                }
            }
        }
        if (relations.size() > MAX_RELATIONS) {
            throw tooLarge();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relations", relations);
        m.put("columns", rows(c, COLUMNS, schema, tables, rs -> {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put(TABLE, rs.getString(1));
            col.put(NAME, rs.getString(2));
            col.put("type", rs.getString(3));
            col.put("notNull", rs.getBoolean(4));
            putText(col, "default", rs.getString(5), chars);
            putIfPresent(col, "identity", blankToNull(rs.getString(6)));
            putIfPresent(col, "generated", blankToNull(rs.getString(7)));
            putIfPresent(col, "collation", rs.getString(8));
            return col;
        }));
        m.put("constraints", rows(c, CONSTRAINTS, schema, tables, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(TABLE, rs.getString(1));
            r.put(NAME, rs.getString(2));
            r.put("type", CONTYPES.get(rs.getString(3)));
            putText(r, DEFINITION, rs.getString(4), chars);
            return r;
        }));
        Set<String> indexed = new HashSet<>(tables);
        relations.stream().filter(r -> "materialized_view".equals(r.get("kind"))).forEach(r -> indexed.add((String) r.get(NAME)));
        m.put("indexes", rows(c, INDEXES, schema, indexed, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(TABLE, rs.getString(1));
            r.put(NAME, rs.getString(2));
            putText(r, DEFINITION, unqualifiedTable(rs.getString(3), rs.getString(6), rs.getString(4), rs.getString(5)), chars);
            return r;
        }));
        m.put("triggers", rows(c, TRIGGERS, schema, tables, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(TABLE, rs.getString(1));
            r.put(NAME, rs.getString(2));
            putText(r, DEFINITION, rs.getString(3), chars);
            return r;
        }));
        m.put("sequences", rows(c, SEQUENCES, schema, sequenceNames, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(TABLE, rs.getString(1));
            r.put("type", rs.getString(2));
            r.put("increment", rs.getString(3));
            r.put("min", rs.getString(4));
            r.put("max", rs.getString(5));
            r.put("start", rs.getString(6));
            r.put("cache", rs.getString(7));
            r.put("cycle", rs.getBoolean(8));
            return r;
        }).stream().map(SchemaSnapshot::renameTableToName).toList());
        m.put("routines", routines(c, schema, chars));
        if (chars[0] > MAX_TEXT_CHARS) {
            throw tooLarge();
        }
        return m;
    }

    private static List<Map<String, Object>> routines(Connection c, String schema, long[] chars) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = MetadataService.prepare(c, ROUTINES)) {
            ps.setString(1, schema);
            ps.setInt(2, MAX_ROUTINES + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (out.size() == MAX_ROUTINES) {
                        throw tooLarge();
                    }
                    boolean procedure = "p".equals(rs.getString(3));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put(NAME, rs.getString(1));
                    r.put("arguments", rs.getString(2));
                    r.put("kind", procedure ? "procedure" : "function");
                    putText(r, DEFINITION, unqualifiedHeader(rs.getString(4), procedure, rs.getString(5), rs.getString(6)), chars);
                    out.add(r);
                }
            }
        }
        return out;
    }

    /**
     * pg_get_functiondef always qualifies the routine's own name in its header ({@code CREATE OR REPLACE FUNCTION
     * s.f(}); the rest of the definition follows the search path. The header's schema is removed so the definition
     * creates the routine in whatever schema the search path names.
     */
    static String unqualifiedHeader(String definition, boolean procedure, String qualifiedName, String name) {
        String head = "CREATE OR REPLACE " + (procedure ? "PROCEDURE " : "FUNCTION ");
        String qualified = head + qualifiedName + "(";
        String trimmed = definition.stripTrailing();
        return trimmed.startsWith(qualified) ? head + name + "(" + trimmed.substring(qualified.length()) : trimmed;
    }

    /**
     * An index definition with its table's schema removed: {@code INDEX i ON [ONLY] s.t USING} becomes {@code INDEX i
     * ON [ONLY] t USING}. Only the text right after the index's own (quoted) name is looked at.
     */
    static String unqualifiedTable(String definition, String index, String qualifiedTable, String table) {
        for (String create : new String[] {"CREATE INDEX ", "CREATE UNIQUE INDEX "}) {
            int i = create.length() + index.length();
            if (definition.startsWith(create + index)) {
                for (String on : new String[] {" ON ONLY ", " ON "}) {
                    String qualified = on + qualifiedTable + " USING ";
                    if (definition.startsWith(qualified, i)) {
                        return definition.substring(0, i) + on + table + " USING " + definition.substring(i + qualified.length());
                    }
                }
            }
        }
        return definition;
    }

    private interface Row {
        Map<String, Object> map(ResultSet rs) throws SQLException;
    }

    /** Rows of a per-schema query whose first column names a relation in {@code keep}. */
    private static List<Map<String, Object>> rows(Connection c, String sql, String schema, Set<String> keep, Row row) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = MetadataService.prepare(c, sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (keep.contains(rs.getString(1))) {
                        out.add(row.map(rs));
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Object> renameTableToName(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        r.forEach((k, v) -> m.put(TABLE.equals(k) ? NAME : k, v));
        return m;
    }

    private static ApiException tooLarge() {
        return new ApiException(422, "SNAPSHOT_TOO_LARGE", "This schema has more than " + MAX_RELATIONS
                + " tables, views and sequences, more than " + MAX_ROUTINES + " routines, or more than "
                + MAX_TEXT_CHARS / 1_000_000 + " million characters of definitions: too large to compare here.");
    }

    private static void putText(Map<String, Object> m, String key, String value, long[] chars) {
        if (value != null) {
            chars[0] += value.length();
            m.put(key, value);
        }
    }

    private static String stripSemicolon(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.endsWith(";") ? t.substring(0, t.length() - 1).stripTrailing() : t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void putIfPresent(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
