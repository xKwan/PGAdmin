package pgweb.metadata;

import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.dbsession.DbSession;
import pgweb.dbsession.DbSessionManager;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The object browser (plan Phase 10): schemas, the objects in a schema, and a relation's columns, indexes and
 * constraints, read from PostgreSQL's catalog on the session's <b>companion</b> connection, as the user's own role.
 * <ul>
 *   <li>Every query is a constant with parameters; names are only ever bound values (never part of SQL text). An
 *       ArchUnit rule forbids plain {@code Statement} here.</li>
 *   <li>By default only objects the role can use are listed ({@code has_*_privilege}). With {@code all}, everything
 *       the catalog shows the role is listed, marked {@code accessible: false} where it has no privilege. Catalog
 *       visibility itself is PostgreSQL's behaviour; nothing is revealed beyond what the role can query.</li>
 *   <li>The companion shows <b>committed</b> state: uncommitted DDL in the main session's transaction isn't visible.</li>
 * </ul>
 */
public final class MetadataService {

    /** Schemas: usable, non-system ones; with {@code all} every schema the catalog shows. Parameter: all. */
    static final String SCHEMAS = """
            SELECT n.nspname, pg_catalog.pg_get_userbyid(n.nspowner),
                   pg_catalog.has_schema_privilege(n.oid, 'USAGE')
            FROM pg_catalog.pg_namespace n
            WHERE n.nspname NOT LIKE 'pg\\_toast%' AND n.nspname NOT LIKE 'pg\\_temp\\_%'
              AND (? OR (n.nspname NOT IN ('pg_catalog', 'information_schema') AND pg_catalog.has_schema_privilege(n.oid, 'USAGE')))
            ORDER BY 1""";

    /** Tables, views, materialised views, sequences, partitioned and foreign tables of a schema. Parameters: schema, all. */
    static final String RELATIONS = """
            SELECT c.relname, c.relkind,
                   CASE WHEN c.relkind = 'S' THEN pg_catalog.has_sequence_privilege(c.oid, 'USAGE,SELECT')
                        ELSE pg_catalog.has_table_privilege(c.oid, 'SELECT,INSERT,UPDATE,DELETE')
                             OR pg_catalog.has_any_column_privilege(c.oid, 'SELECT,INSERT,UPDATE') END,
                   pg_catalog.obj_description(c.oid, 'pg_class')
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
            ORDER BY 1""";

    /** Functions and procedures of a schema, with their signatures. Parameters: schema. */
    static final String ROUTINES = """
            SELECT p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid),
                   pg_catalog.pg_get_function_result(p.oid), p.prokind,
                   pg_catalog.has_function_privilege(p.oid, 'EXECUTE')
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.prokind IN ('f', 'p', 'a', 'w')
            ORDER BY 1, 2""";

    /** The relation itself (to tell "not found" from "empty"). Parameters: schema, name. */
    static final String RELATION = """
            SELECT c.oid, c.relkind,
                   pg_catalog.has_table_privilege(c.oid, 'SELECT,INSERT,UPDATE,DELETE')
                   OR pg_catalog.has_any_column_privilege(c.oid, 'SELECT,INSERT,UPDATE')
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?""";

    /** Columns. Parameter: relation oid. */
    static final String COLUMNS = """
            SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid), a.attidentity, a.attgenerated
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum""";

    /** Indexes. Parameter: relation oid. */
    static final String INDEXES = """
            SELECT i.relname, pg_catalog.pg_get_indexdef(x.indexrelid), x.indisprimary, x.indisunique
            FROM pg_catalog.pg_index x JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid
            WHERE x.indrelid = ?
            ORDER BY 1""";

    /** Constraints. Parameter: relation oid. */
    static final String CONSTRAINTS = """
            SELECT r.conname, r.contype, pg_catalog.pg_get_constraintdef(r.oid, true)
            FROM pg_catalog.pg_constraint r
            WHERE r.conrelid = ?
            ORDER BY 1""";

    private static final Map<String, String> RELKINDS = Map.of("r", "table", "p", "table", "v", "view", "m", "materialized_view",
            "S", "sequence", "f", "foreign_table");
    private static final Map<String, String> PROKINDS = Map.of("f", "function", "p", "procedure", "a", "aggregate", "w", "window");
    private static final Map<String, String> CONTYPES = Map.of("p", "primary_key", "f", "foreign_key", "u", "unique", "c", "check",
            "x", "exclusion", "t", "trigger", "n", "not_null");
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final long BUSY_WAIT_MILLIS = 3000;
    private static final String NAME = "name";
    private static final String ACCESSIBLE = "accessible";
    private static final String SCHEMA = "schema";
    private static final String OTHER = "other";

    private final DbSessionManager sessions;
    private final AuditService audit;
    private final Clock clock;

    public MetadataService(DbSessionManager sessions, AuditService audit, Clock clock) {
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    /** One catalog read on the companion, serialised per session; a busy or missing companion is reported, not waited on. */
    private interface Read<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T onCompanion(UserContext user, DbSession s, String object, String schema, Read<T> read) {
        Connection companion = s.companion();
        if (companion == null) {
            throw ApiException.conflict("METADATA_UNAVAILABLE", "Object browsing isn't available for this session (Limited): connect again later.");
        }
        boolean locked;
        try {
            locked = s.companionLock().tryLock(BUSY_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            throw ApiException.conflict("COMPANION_BUSY", "The session is busy loading other information. Try again in a moment.");
        }
        try {
            T out = read.run(companion);
            audit.record(AuditEvent.of("metadata_browse").user(user).target(s.profile(), s.database(), s.role(), s.companionPid(), s.id())
                    .detail("object", object).detail(SCHEMA, schema));
            return out;
        } catch (SQLException e) {
            throw new ApiException(422, "METADATA_FAILED", "The catalog could not be read (" + (e.getSQLState() == null ? "error" : e.getSQLState()) + ").");
        } finally {
            s.companionLock().unlock();
        }
    }

    private Map<String, Object> envelope(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        m.put("loadedAt", clock.instant().toString());
        return m;
    }

    /** A prepared catalog query with the timeout set; closed again if setting it fails. */
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            return ps;
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
    }

    public Map<String, Object> schemas(UserContext user, String sessionId, boolean all) {
        DbSession s = sessions.get(user, sessionId);
        return envelope("schemas", onCompanion(user, s, "schemas", null, c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = prepare(c, SCHEMAS)) {
                ps.setBoolean(1, all);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(Map.of(NAME, rs.getString(1), "owner", rs.getString(2), ACCESSIBLE, rs.getBoolean(3)));
                    }
                }
            }
            return out;
        }));
    }

    public Map<String, Object> objects(UserContext user, String sessionId, String schema, boolean all) {
        requireName(schema, SCHEMA);
        DbSession s = sessions.get(user, sessionId);
        return envelope("objects", onCompanion(user, s, "objects", schema, c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = prepare(c, RELATIONS)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean accessible = rs.getBoolean(3);
                        if (all || accessible) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("kind", RELKINDS.getOrDefault(rs.getString(2), OTHER));
                            m.put(NAME, rs.getString(1));
                            m.put(ACCESSIBLE, accessible);
                            putIfPresent(m, "comment", rs.getString(4));
                            out.add(m);
                        }
                    }
                }
            }
            try (PreparedStatement ps = prepare(c, ROUTINES)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean accessible = rs.getBoolean(5);
                        if (all || accessible) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("kind", PROKINDS.getOrDefault(rs.getString(4), "function"));
                            m.put(NAME, rs.getString(1));
                            m.put("arguments", rs.getString(2));
                            putIfPresent(m, "returns", rs.getString(3));
                            m.put(ACCESSIBLE, accessible);
                            out.add(m);
                        }
                    }
                }
            }
            return out;
        }));
    }

    public Map<String, Object> relation(UserContext user, String sessionId, String schema, String name) {
        requireName(schema, SCHEMA);
        requireName(name, NAME);
        DbSession s = sessions.get(user, sessionId);
        Map<String, Object> rel = onCompanion(user, s, "relation", schema, c -> {
            long oid;
            Map<String, Object> m = new LinkedHashMap<>();
            try (PreparedStatement ps = prepare(c, RELATION)) {
                ps.setString(1, schema);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    oid = rs.getLong(1);
                    m.put(SCHEMA, schema);
                    m.put(NAME, name);
                    m.put("kind", RELKINDS.getOrDefault(rs.getString(2), OTHER));
                    m.put(ACCESSIBLE, rs.getBoolean(3));
                }
            }
            m.put("columns", list(c, COLUMNS, oid, rs -> {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put(NAME, rs.getString(1));
                col.put("type", rs.getString(2));
                col.put("nullable", rs.getBoolean(3));
                putIfPresent(col, "default", rs.getString(4));
                putIfPresent(col, "identity", blankToNull(rs.getString(5)));
                putIfPresent(col, "generated", blankToNull(rs.getString(6)));
                return col;
            }));
            m.put("indexes", list(c, INDEXES, oid, rs -> Map.of(NAME, rs.getString(1), "definition", rs.getString(2),
                    "primary", rs.getBoolean(3), "unique", rs.getBoolean(4))));
            m.put("constraints", list(c, CONSTRAINTS, oid, rs -> Map.of(NAME, rs.getString(1),
                    "type", CONTYPES.getOrDefault(rs.getString(2), OTHER), "definition", rs.getString(3))));
            return m;
        });
        if (rel == null) {
            throw ApiException.notFound();
        }
        rel.put("loadedAt", clock.instant().toString());
        return rel;
    }

    private interface Row {
        Map<String, Object> map(ResultSet rs) throws SQLException;
    }

    private static List<Map<String, Object>> list(Connection c, String sql, long oid, Row row) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, sql)) {
            ps.setLong(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(row.map(rs));
                }
            }
        }
        return out;
    }

    /** PostgreSQL names are at most 63 bytes and never contain NUL; anything else can't exist, so it isn't sent. */
    static void requireName(String name, String what) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            throw ApiException.badRequest("INVALID_NAME", "Not a valid " + what + " name");
        }
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
