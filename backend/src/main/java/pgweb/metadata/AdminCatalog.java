package pgweb.metadata;

import pgweb.dbsession.DbSession;
import pgweb.dbsession.DbSessionManager;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only administration views (admin Phase 3): the connected database's properties and grants, server activity and
 * locks, sizes, roles and memberships, and object definitions. Everything is read from PostgreSQL's catalog and
 * statistics views on the session's companion connection, <b>as the user's own role</b>, with the same rules as the
 * object browser ({@link MetadataService}): constant queries, names only ever as bound parameters, a 10-second timeout,
 * one read at a time per session, and a {@code metadata_browse} audit record.
 * <ul>
 *   <li>Nothing here changes anything: no cancel or terminate of other sessions, no DDL. Definitions are text for the
 *       editor; running them goes through the execution gate like any other SQL.</li>
 *   <li>What other sessions' rows show (query text, state) is PostgreSQL's decision: a role sees the details of its
 *       own sessions, and of everyone's only with {@code pg_read_all_stats}. Nothing is shown beyond that.</li>
 *   <li>Every list is bounded (a row cap, with {@code truncated} when it was reached) and loaded only on request, so a
 *       production server isn't polled.</li>
 * </ul>
 */
public final class AdminCatalog {

    /** The connected database: owner, encoding, locale, limits, size, and what the current role may do in it. */
    static final String DATABASE = """
            SELECT d.datname, pg_catalog.pg_get_userbyid(d.datdba), pg_catalog.pg_encoding_to_char(d.encoding),
                   d.datcollate, d.datctype, d.datconnlimit, d.datistemplate, d.datallowconn, t.spcname,
                   pg_catalog.shobj_description(d.oid, 'pg_database'), pg_catalog.pg_database_size(d.oid),
                   pg_catalog.has_database_privilege(d.oid, 'CREATE'), pg_catalog.has_database_privilege(d.oid, 'TEMP'),
                   current_user, pg_catalog.current_setting('server_version'), pg_catalog.pg_postmaster_start_time()
            FROM pg_catalog.pg_database d JOIN pg_catalog.pg_tablespace t ON t.oid = d.dattablespace
            WHERE d.datname = pg_catalog.current_database()""";

    /** Grants on the connected database (the defaults when none were ever changed). */
    static final String DATABASE_GRANTS = """
            SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_catalog.pg_get_userbyid(a.grantee) END,
                   pg_catalog.pg_get_userbyid(a.grantor), a.privilege_type, a.is_grantable
            FROM pg_catalog.pg_database d,
                 pg_catalog.aclexplode(COALESCE(d.datacl, pg_catalog.acldefault('d', d.datdba))) a
            WHERE d.datname = pg_catalog.current_database()
            ORDER BY 1, 3""";

    /**
     * Client sessions on the server. PostgreSQL hides the backend type (with the query and state) of sessions the
     * role may not see; those are kept when they belong to a database. Blockers are looked up only for sessions
     * waiting on a lock (pg_blocking_pids is not free). Parameter: row cap + 1.
     */
    static final String ACTIVITY = """
            SELECT a.pid, a.usename, a.datname, a.application_name, a.state, a.wait_event_type, a.wait_event,
                   a.backend_start, a.xact_start, a.query_start, a.state_change, pg_catalog.left(a.query, 2000),
                   pg_catalog.length(a.query) > 2000,
                   CASE WHEN a.wait_event_type = 'Lock' THEN pg_catalog.pg_blocking_pids(a.pid) END
            FROM pg_catalog.pg_stat_activity a
            WHERE a.backend_type = 'client backend' OR (a.backend_type IS NULL AND a.datid IS NOT NULL)
            ORDER BY a.backend_start, a.pid
            LIMIT ?""";

    /**
     * Locks held or awaited by other backends: relation names only in the connected database (elsewhere the oid
     * means nothing here). Waiting locks first. Parameter: row cap + 1.
     */
    static final String LOCKS = """
            SELECT l.pid, l.locktype, l.mode, l.granted, l.waitstart,
                   CASE WHEN l.relation IS NOT NULL AND l.database = (SELECT oid FROM pg_catalog.pg_database
                            WHERE datname = pg_catalog.current_database()) THEN l.relation::pg_catalog.regclass::text END,
                   l.transactionid::text, d.datname
            FROM pg_catalog.pg_locks l LEFT JOIN pg_catalog.pg_database d ON d.oid = l.database
            WHERE l.pid IS NOT NULL AND l.pid <> pg_catalog.pg_backend_pid() AND l.locktype <> 'virtualxid'
            ORDER BY l.granted, l.pid, l.locktype
            LIMIT ?""";

    /** Database sizes, only where the role may connect (pg_database_size requires it). Parameter: row cap + 1. */
    static final String DATABASE_SIZES = """
            SELECT d.datname,
                   CASE WHEN pg_catalog.has_database_privilege(d.oid, 'CONNECT') THEN pg_catalog.pg_database_size(d.oid) END
            FROM pg_catalog.pg_database d
            WHERE d.datallowconn
            ORDER BY 2 DESC NULLS LAST, 1
            LIMIT ?""";

    /** The largest tables (and materialised views) the role can use, with table, index and estimated row counts. */
    static final String TABLE_SIZES = """
            SELECT n.nspname, c.relname, c.relkind, pg_catalog.pg_total_relation_size(c.oid),
                   pg_catalog.pg_table_size(c.oid), pg_catalog.pg_indexes_size(c.oid), c.reltuples::bigint
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p', 'm')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg\\_toast%' AND n.nspname NOT LIKE 'pg\\_temp\\_%'
              AND (pg_catalog.has_table_privilege(c.oid, 'SELECT,INSERT,UPDATE,DELETE')
                   OR pg_catalog.has_any_column_privilege(c.oid, 'SELECT,INSERT,UPDATE'))
            ORDER BY 4 DESC, 1, 2
            LIMIT ?""";

    /** Roles (never passwords: pg_roles masks them). Parameters: include predefined pg_* roles, row cap + 1. */
    static final String ROLES = """
            SELECT r.rolname, r.rolsuper, r.rolinherit, r.rolcreaterole, r.rolcreatedb, r.rolcanlogin,
                   r.rolreplication, r.rolbypassrls, r.rolconnlimit, pg_catalog.pg_has_role(r.oid, 'MEMBER'),
                   r.rolname = current_user, pg_catalog.shobj_description(r.oid, 'pg_authid')
            FROM pg_catalog.pg_roles r
            WHERE ? OR r.rolname !~ '^pg_'
            ORDER BY 1
            LIMIT ?""";

    /** Role memberships, with the options of PostgreSQL 16 and later. Parameter: row cap + 1. */
    static final String MEMBERSHIPS = """
            SELECT pg_catalog.pg_get_userbyid(m.roleid), pg_catalog.pg_get_userbyid(m.member),
                   pg_catalog.pg_get_userbyid(m.grantor), m.admin_option, m.inherit_option, m.set_option
            FROM pg_catalog.pg_auth_members m
            ORDER BY 1, 2
            LIMIT ?""";

    /** A view or materialised view: its quoted name, kind and query. Parameters: schema, name. */
    static final String VIEW_DEFINITION = """
            SELECT pg_catalog.quote_ident(n.nspname) || '.' || pg_catalog.quote_ident(c.relname), c.relkind,
                   pg_catalog.pg_get_viewdef(c.oid, true),
                   pg_catalog.quote_literal(pg_catalog.obj_description(c.oid, 'pg_class'))
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('v', 'm')""";

    /** A function or procedure, chosen by its identity arguments. Parameters: schema, name, arguments. */
    static final String ROUTINE_DEFINITION = """
            SELECT pg_catalog.pg_get_functiondef(p.oid)
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.proname = ? AND pg_catalog.pg_get_function_identity_arguments(p.oid) = ?
              AND p.prokind IN ('f', 'p')""";

    /** A sequence's options. Parameters: schema, name. */
    static final String SEQUENCE_DEFINITION = """
            SELECT pg_catalog.quote_ident(n.nspname) || '.' || pg_catalog.quote_ident(c.relname),
                   pg_catalog.format_type(s.seqtypid, NULL), s.seqincrement, s.seqmin, s.seqmax, s.seqstart, s.seqcache,
                   s.seqcycle
            FROM pg_catalog.pg_sequence s JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?""";

    /** A table: its oid, quoted name, kind, persistence, partition parent and bound, partition key and comment. */
    static final String TABLE_DEFINITION = """
            SELECT c.oid, pg_catalog.quote_ident(n.nspname) || '.' || pg_catalog.quote_ident(c.relname), c.relkind,
                   c.relpersistence,
                   (SELECT pg_catalog.quote_ident(pn.nspname) || '.' || pg_catalog.quote_ident(pc.relname)
                    FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class pc ON pc.oid = i.inhparent
                    JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
                    WHERE c.relispartition AND i.inhrelid = c.oid),
                   CASE WHEN c.relispartition THEN pg_catalog.pg_get_expr(c.relpartbound, c.oid) END,
                   CASE WHEN c.relkind = 'p' THEN pg_catalog.pg_get_partkeydef(c.oid) END,
                   pg_catalog.quote_literal(pg_catalog.obj_description(c.oid, 'pg_class'))
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')""";

    /** A table's own columns, quoted, with type, collation, NOT NULL, default, identity and generation. Parameter: oid. */
    static final String TABLE_COLUMNS = """
            SELECT pg_catalog.quote_ident(a.attname), pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid), a.attidentity, a.attgenerated,
                   CASE WHEN a.attcollation <> t.typcollation THEN pg_catalog.quote_ident(co.collname) END
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            LEFT JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            LEFT JOIN pg_catalog.pg_collation co ON co.oid = a.attcollation
            WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum""";

    /** Table constraints except NOT NULL (written on the columns). Parameter: oid. */
    static final String TABLE_CONSTRAINTS = """
            SELECT pg_catalog.quote_ident(r.conname), pg_catalog.pg_get_constraintdef(r.oid, true)
            FROM pg_catalog.pg_constraint r
            WHERE r.conrelid = ? AND r.contype <> 'n'
            ORDER BY r.contype = 'f', r.conname""";

    /** Indexes that don't belong to a constraint. Parameter: oid. */
    static final String TABLE_INDEXES = """
            SELECT pg_catalog.pg_get_indexdef(x.indexrelid)
            FROM pg_catalog.pg_index x JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid
            WHERE x.indrelid = ?
              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint r WHERE r.conindid = x.indexrelid AND r.conrelid = x.indrelid)
            ORDER BY i.relname""";

    /** User triggers that weren't cloned from a parent table. Parameter: oid. */
    static final String TABLE_TRIGGERS = """
            SELECT pg_catalog.pg_get_triggerdef(t.oid, true)
            FROM pg_catalog.pg_trigger t
            WHERE t.tgrelid = ? AND NOT t.tgisinternal AND t.tgparentid = 0
            ORDER BY t.tgname""";

    static final int MAX_ACTIVITY = 500;
    static final int MAX_LOCKS = 1000;
    static final int MAX_DATABASES = 500;
    static final int MAX_TABLES = 100;
    static final int MAX_ROLES = 2000;
    static final int MAX_MEMBERSHIPS = 5000;
    static final int MAX_ARGUMENTS = 4000;
    static final String HIDDEN_QUERY = "<insufficient privilege>";

    private static final Set<String> DEFINABLE = Set.of("table", "partitioned_table", "view", "materialized_view", "sequence",
            "function", "procedure");
    private static final String NAME = "name";
    private static final String SCHEMA = "schema";
    private static final String TRUNCATED = "truncated";

    private final MetadataService metadata;
    private final DbSessionManager sessions;

    public AdminCatalog(MetadataService metadata, DbSessionManager sessions) {
        this.metadata = metadata;
        this.sessions = sessions;
    }

    /** The connected database's properties, your privileges in it, and its grants. */
    public Map<String, Object> database(UserContext user, String sessionId) {
        DbSession s = sessions.get(user, sessionId);
        return metadata.envelope("database", metadata.onCompanion(user, s, "database", null, c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            try (PreparedStatement ps = MetadataService.prepare(c, DATABASE); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound();
                }
                m.put(NAME, rs.getString(1));
                m.put("owner", rs.getString(2));
                m.put("encoding", rs.getString(3));
                m.put("collate", rs.getString(4));
                m.put("ctype", rs.getString(5));
                m.put("connectionLimit", rs.getInt(6));
                m.put("template", rs.getBoolean(7));
                m.put("allowConnections", rs.getBoolean(8));
                m.put("tablespace", rs.getString(9));
                putIfPresent(m, "comment", rs.getString(10));
                m.put("sizeBytes", rs.getLong(11));
                m.put("canCreateSchemas", rs.getBoolean(12));
                m.put("canCreateTemp", rs.getBoolean(13));
                m.put("currentRole", rs.getString(14));
                m.put("serverVersion", rs.getString(15));
                m.put("serverStarted", iso(rs, 16));
            }
            List<Map<String, Object>> grants = new ArrayList<>();
            try (PreparedStatement ps = MetadataService.prepare(c, DATABASE_GRANTS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    grants.add(grant(rs));
                }
            }
            m.put("grants", grants);
            return m;
        }));
    }

    /** Client sessions on the server (as far as the role may see them) and the locks of other sessions. */
    public Map<String, Object> activity(UserContext user, String sessionId) {
        DbSession s = sessions.get(user, sessionId);
        Integer companionPid = s.companionPid();
        return metadata.envelope("activity", metadata.onCompanion(user, s, "activity", null, c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean cut = capped(c, ACTIVITY, MAX_ACTIVITY, rs -> {
                Map<String, Object> a = new LinkedHashMap<>();
                int pid = rs.getInt(1);
                a.put("pid", pid);
                putIfPresent(a, "user", rs.getString(2));
                putIfPresent(a, "database", rs.getString(3));
                putIfPresent(a, "application", rs.getString(4));
                String query = rs.getString(12);
                boolean visible = query != null && !HIDDEN_QUERY.equals(query);
                a.put("visible", visible);
                if (visible) {
                    putIfPresent(a, "state", rs.getString(5));
                    putIfPresent(a, "waitEventType", rs.getString(6));
                    putIfPresent(a, "waitEvent", rs.getString(7));
                    putIfPresent(a, "backendStart", iso(rs, 8));
                    putIfPresent(a, "transactionStart", iso(rs, 9));
                    putIfPresent(a, "queryStart", iso(rs, 10));
                    putIfPresent(a, "stateChange", iso(rs, 11));
                    a.put("query", query);
                    if (rs.getBoolean(13)) {
                        a.put("queryTruncated", true);
                    }
                }
                List<Integer> blockers = ints(rs.getArray(14));
                if (!blockers.isEmpty()) {
                    a.put("blockedBy", blockers);
                }
                if (pid == s.mainPid()) {
                    a.put("mine", "session");
                } else if (companionPid != null && pid == companionPid) {
                    a.put("mine", "companion");
                }
                rows.add(a);
            });
            m.put("sessions", rows);
            if (cut) {
                m.put("sessionsTruncated", true);
            }
            List<Map<String, Object>> locks = new ArrayList<>();
            boolean lockCut = capped(c, LOCKS, MAX_LOCKS, rs -> {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("pid", rs.getInt(1));
                l.put("type", rs.getString(2));
                l.put("mode", rs.getString(3));
                l.put("granted", rs.getBoolean(4));
                putIfPresent(l, "waitStart", iso(rs, 5));
                putIfPresent(l, "relation", rs.getString(6));
                putIfPresent(l, "transactionId", rs.getString(7));
                putIfPresent(l, "database", rs.getString(8));
                locks.add(l);
            });
            m.put("locks", locks);
            if (lockCut) {
                m.put("locksTruncated", true);
            }
            return m;
        }));
    }

    /** Sizes of the databases you may connect to and of the largest tables you can use in this one. */
    public Map<String, Object> sizes(UserContext user, String sessionId) {
        DbSession s = sessions.get(user, sessionId);
        return metadata.envelope("sizes", metadata.onCompanion(user, s, "sizes", null, c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, Object>> dbs = new ArrayList<>();
            boolean dbCut = capped(c, DATABASE_SIZES, MAX_DATABASES, rs -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put(NAME, rs.getString(1));
                long size = rs.getLong(2);
                if (!rs.wasNull()) {
                    d.put("sizeBytes", size);
                }
                dbs.add(d);
            });
            m.put("databases", dbs);
            if (dbCut) {
                m.put("databasesTruncated", true);
            }
            List<Map<String, Object>> tables = new ArrayList<>();
            capped(c, TABLE_SIZES, MAX_TABLES, rs -> {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put(SCHEMA, rs.getString(1));
                t.put(NAME, rs.getString(2));
                t.put("kind", switch (rs.getString(3)) {
                    case "p" -> "partitioned_table";
                    case "m" -> "materialized_view";
                    default -> "table";
                });
                t.put("totalBytes", rs.getLong(4));
                t.put("tableBytes", rs.getLong(5));
                t.put("indexBytes", rs.getLong(6));
                long est = rs.getLong(7);
                if (est >= 0) {
                    t.put("estimatedRows", est);
                }
                tables.add(t);
            });
            m.put("tables", tables);
            m.put("tablesLimit", MAX_TABLES);
            return m;
        }));
    }

    /** Roles with their attributes and whether you are a member, and every membership. */
    public Map<String, Object> roles(UserContext user, String sessionId, boolean predefined) {
        DbSession s = sessions.get(user, sessionId);
        return metadata.envelope("roles", metadata.onCompanion(user, s, "roles", null, c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, Object>> roles = new ArrayList<>();
            boolean cut;
            try (PreparedStatement ps = MetadataService.prepare(c, ROLES)) {
                ps.setBoolean(1, predefined);
                ps.setInt(2, MAX_ROLES + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    cut = collect(rs, MAX_ROLES, r -> {
                        Map<String, Object> role = new LinkedHashMap<>();
                        role.put(NAME, r.getString(1));
                        List<String> attrs = new ArrayList<>();
                        flag(attrs, r.getBoolean(2), "superuser");
                        flag(attrs, !r.getBoolean(3), "noinherit");
                        flag(attrs, r.getBoolean(4), "createrole");
                        flag(attrs, r.getBoolean(5), "createdb");
                        flag(attrs, r.getBoolean(6), "login");
                        flag(attrs, r.getBoolean(7), "replication");
                        flag(attrs, r.getBoolean(8), "bypassrls");
                        role.put("attributes", attrs);
                        int limit = r.getInt(9);
                        if (limit >= 0) {
                            role.put("connectionLimit", limit);
                        }
                        role.put("member", r.getBoolean(10));
                        if (r.getBoolean(11)) {
                            role.put("current", true);
                        }
                        putIfPresent(role, "comment", r.getString(12));
                        roles.add(role);
                    });
                }
            }
            m.put("roles", roles);
            if (cut) {
                m.put(TRUNCATED, true);
            }
            List<Map<String, Object>> members = new ArrayList<>();
            boolean memberCut = capped(c, MEMBERSHIPS, MAX_MEMBERSHIPS, rs -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("role", rs.getString(1));
                mm.put("member", rs.getString(2));
                putIfPresent(mm, "grantor", rs.getString(3));
                mm.put("admin", rs.getBoolean(4));
                mm.put("inherit", rs.getBoolean(5));
                mm.put("set", rs.getBoolean(6));
                members.add(mm);
            });
            m.put("memberships", members);
            if (memberCut) {
                m.put("membershipsTruncated", true);
            }
            return m;
        }));
    }

    /**
     * An object's definition as SQL text, for reading or for the editor. Tables are reconstructed from the catalog
     * (columns, constraints, other indexes, triggers, comment): not a dump. Nothing is run.
     */
    public Map<String, Object> definition(UserContext user, String sessionId, String kind, String schema, String name, String arguments) {
        if (kind == null || !DEFINABLE.contains(kind)) {
            throw ApiException.badRequest("INVALID_KIND", "Definitions are available for tables, views, sequences, functions and procedures");
        }
        MetadataService.requireName(schema, SCHEMA);
        MetadataService.requireName(name, NAME);
        boolean routine = kind.equals("function") || kind.equals("procedure");
        if (routine && (arguments == null || arguments.indexOf('\0') >= 0 || arguments.length() > MAX_ARGUMENTS)) {
            throw ApiException.badRequest("INVALID_ARGUMENTS", "Not a valid argument list");
        }
        DbSession s = sessions.get(user, sessionId);
        String text = metadata.onCompanion(user, s, "definition", schema, c -> switch (kind) {
            case "view", "materialized_view" -> viewDefinition(c, schema, name);
            case "sequence" -> sequenceDefinition(c, schema, name);
            case "function", "procedure" -> routineDefinition(c, schema, name, arguments);
            default -> tableDefinition(c, schema, name);
        });
        if (text == null) {
            throw ApiException.notFound();
        }
        Map<String, Object> m = metadata.envelope("definition", text);
        m.put("kind", kind);
        m.put(SCHEMA, schema);
        m.put(NAME, name);
        return m;
    }

    private static String viewDefinition(Connection c, String schema, String name) throws SQLException {
        try (PreparedStatement ps = MetadataService.prepare(c, VIEW_DEFINITION)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                boolean mat = "m".equals(rs.getString(2));
                StringBuilder sb = new StringBuilder();
                sb.append(mat ? "CREATE MATERIALIZED VIEW " : "CREATE OR REPLACE VIEW ").append(rs.getString(1)).append(" AS\n")
                        .append(stripSemicolon(rs.getString(3))).append(";\n");
                String comment = rs.getString(4);
                if (comment != null) {
                    sb.append("\nCOMMENT ON ").append(mat ? "MATERIALIZED VIEW " : "VIEW ").append(rs.getString(1)).append(" IS ")
                            .append(comment).append(";\n");
                }
                return sb.toString();
            }
        }
    }

    private static String routineDefinition(Connection c, String schema, String name, String arguments) throws SQLException {
        try (PreparedStatement ps = MetadataService.prepare(c, ROUTINE_DEFINITION)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.setString(3, arguments);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1).stripTrailing() + ";\n" : null;
            }
        }
    }

    private static String sequenceDefinition(Connection c, String schema, String name) throws SQLException {
        try (PreparedStatement ps = MetadataService.prepare(c, SEQUENCE_DEFINITION)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return "CREATE SEQUENCE " + rs.getString(1) + "\n    AS " + rs.getString(2)
                        + "\n    INCREMENT BY " + rs.getLong(3)
                        + "\n    MINVALUE " + rs.getLong(4)
                        + "\n    MAXVALUE " + rs.getLong(5)
                        + "\n    START WITH " + rs.getLong(6)
                        + "\n    CACHE " + rs.getLong(7)
                        + (rs.getBoolean(8) ? "\n    CYCLE" : "\n    NO CYCLE") + ";\n";
            }
        }
    }

    private static String tableDefinition(Connection c, String schema, String name) throws SQLException {
        long oid;
        String qualified;
        String parent;
        String bound;
        String partitionKey;
        String comment;
        boolean unlogged;
        boolean partitioned;
        try (PreparedStatement ps = MetadataService.prepare(c, TABLE_DEFINITION)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                oid = rs.getLong(1);
                qualified = rs.getString(2);
                partitioned = "p".equals(rs.getString(3));
                unlogged = "u".equals(rs.getString(4));
                parent = rs.getString(5);
                bound = rs.getString(6);
                partitionKey = rs.getString(7);
                comment = rs.getString(8);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("-- Reconstructed from the catalog: columns, constraints, indexes, triggers and comment.\n")
                .append("-- Not included: ownership, privileges, storage parameters, statistics and policies. Review before running.\n");
        sb.append(unlogged ? "CREATE UNLOGGED TABLE " : "CREATE TABLE ").append(qualified);
        if (parent != null) {
            // A partition takes its columns, constraints and indexes from its parent.
            sb.append(" PARTITION OF ").append(parent).append("\n    ").append(bound);
            if (partitionKey != null) {
                sb.append("\n    PARTITION BY ").append(partitionKey);
            }
            sb.append(";\n");
        } else {
            List<String> lines = new ArrayList<>();
            try (PreparedStatement ps = MetadataService.prepare(c, TABLE_COLUMNS)) {
                ps.setLong(1, oid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(column(rs));
                    }
                }
            }
            for (String[] con : rows2(c, TABLE_CONSTRAINTS, oid)) {
                lines.add("CONSTRAINT " + con[0] + " " + con[1]);
            }
            sb.append(" (\n    ").append(String.join(",\n    ", lines)).append("\n)");
            if (partitioned) {
                sb.append("\nPARTITION BY ").append(partitionKey);
            }
            sb.append(";\n");
            for (String idx : rows1(c, TABLE_INDEXES, oid)) {
                sb.append('\n').append(idx).append(';');
            }
        }
        for (String trg : rows1(c, TABLE_TRIGGERS, oid)) {
            sb.append('\n').append(trg).append(';');
        }
        if (comment != null) {
            sb.append("\n\nCOMMENT ON TABLE ").append(qualified).append(" IS ").append(comment).append(';');
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private static String column(ResultSet rs) throws SQLException {
        StringBuilder col = new StringBuilder(rs.getString(1)).append(' ').append(rs.getString(2));
        String collation = rs.getString(7);
        if (collation != null) {
            col.append(" COLLATE ").append(collation);
        }
        String expr = rs.getString(4);
        String identity = rs.getString(5);
        String generated = rs.getString(6);
        if ("a".equals(identity)) {
            col.append(" GENERATED ALWAYS AS IDENTITY");
        } else if ("d".equals(identity)) {
            col.append(" GENERATED BY DEFAULT AS IDENTITY");
        }
        if ("s".equals(generated) || "v".equals(generated)) {
            col.append(" GENERATED ALWAYS AS (").append(expr).append(")").append("s".equals(generated) ? " STORED" : " VIRTUAL");
        } else if (expr != null) {
            col.append(" DEFAULT ").append(expr);
        }
        if (rs.getBoolean(3)) {
            col.append(" NOT NULL");
        }
        return col.toString();
    }

    // ---------------------------------------------------------------- helpers

    private interface RowSink {
        void accept(ResultSet rs) throws SQLException;
    }

    /** Runs a query whose only parameter is the row cap + 1; true if there were more rows than the cap. */
    private static boolean capped(Connection c, String sql, int cap, RowSink sink) throws SQLException {
        try (PreparedStatement ps = MetadataService.prepare(c, sql)) {
            ps.setInt(1, cap + 1);
            try (ResultSet rs = ps.executeQuery()) {
                return collect(rs, cap, sink);
            }
        }
    }

    private static boolean collect(ResultSet rs, int cap, RowSink sink) throws SQLException {
        int n = 0;
        while (rs.next()) {
            if (n == cap) {
                return true;
            }
            sink.accept(rs);
            n++;
        }
        return false;
    }

    private static List<String> rows1(Connection c, String sql, long oid) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = MetadataService.prepare(c, sql)) {
            ps.setLong(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static List<String[]> rows2(Connection c, String sql, long oid) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = MetadataService.prepare(c, sql)) {
            ps.setLong(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        return out;
    }

    /** One row of aclexplode: grantee, grantor, privilege, grantable. */
    static Map<String, Object> grant(ResultSet rs) throws SQLException {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("grantee", rs.getString(1));
        g.put("grantor", rs.getString(2));
        g.put("privilege", rs.getString(3));
        g.put("grantable", rs.getBoolean(4));
        return g;
    }

    private static List<Integer> ints(Array a) throws SQLException {
        List<Integer> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        try {
            for (Object o : (Object[]) a.getArray()) {
                out.add(((Number) o).intValue());
            }
        } finally {
            a.free();
        }
        return out;
    }

    private static String iso(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant().toString();
    }

    private static String stripSemicolon(String s) {
        String t = s.stripTrailing();
        return t.endsWith(";") ? t.substring(0, t.length() - 1) : t;
    }

    private static void flag(List<String> attrs, boolean on, String name) {
        if (on) {
            attrs.add(name);
        }
    }

    private static void putIfPresent(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
