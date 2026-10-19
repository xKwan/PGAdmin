package pgweb.execution;

import com.fasterxml.jackson.databind.JsonNode;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;
import pgweb.sqlparse.SqlParser;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single-statement gate (ADR-0009, EXE-02): nothing reaches PostgreSQL unless
 * <ol>
 *   <li>PostgreSQL's own parser (libpg_query, with the session's {@code standard_conforming_strings}) finds exactly one
 *       statement, and</li>
 *   <li>pgJDBC, which splits multi-statement text on the client, also sees exactly one statement.</li>
 * </ol>
 * If they disagree, nothing is sent. Statement-free text (empty, only {@code ;}, only comments) and forms the MVP
 * doesn't support ({@code COPY} to/from the client, {@code LISTEN}) are refused here too. No hand-written splitting.
 */
public final class StatementGate {

    /** Why text was refused before anything was sent. */
    public enum Rejection { TOO_LARGE, SYNTAX_ERROR, EMPTY_STATEMENT, MULTIPLE_STATEMENTS, GATE_DISAGREEMENT, UNSUPPORTED, TOO_MANY_STATEMENTS }

    /** The gate's verdict: either an approved statement with its class, or a rejection. */
    public record Verdict(Rejection rejection, String message, int position, SqlParser.Statement statement, String statementClass) {
        public boolean approved() {
            return rejection == null;
        }
    }

    private static final String SESSION = "session";
    private static final String PREPARED = "prepared";
    private static final Map<String, String> CLASSES = Map.ofEntries(
            Map.entry("SelectStmt", "select"), Map.entry("InsertStmt", "dml"), Map.entry("UpdateStmt", "dml"),
            Map.entry("DeleteStmt", "dml"), Map.entry("MergeStmt", "dml"), Map.entry("TransactionStmt", "transaction"),
            Map.entry("VariableSetStmt", SESSION), Map.entry("VariableShowStmt", SESSION), Map.entry("DiscardStmt", SESSION),
            Map.entry("ExplainStmt", "explain"), Map.entry("GrantStmt", "dcl"), Map.entry("GrantRoleStmt", "dcl"),
            Map.entry("AlterDefaultPrivilegesStmt", "dcl"), Map.entry("CallStmt", "call"), Map.entry("DoStmt", "procedural"),
            Map.entry("CopyStmt", "copy"), Map.entry("TruncateStmt", "ddl"), Map.entry("DropStmt", "ddl"),
            Map.entry("VacuumStmt", "maintenance"), Map.entry("ExecuteStmt", PREPARED), Map.entry("PrepareStmt", PREPARED),
            Map.entry("DeallocateStmt", PREPARED), Map.entry("NotifyStmt", "notify"), Map.entry("LockStmt", "lock"));

    private final SqlParser parser;
    private final long maxSqlBytes;

    public StatementGate(SqlParser parser, long maxSqlBytes) {
        this.parser = parser;
        this.maxSqlBytes = maxSqlBytes;
    }

    public Verdict check(String sql, boolean standardConformingStrings) {
        if (sql == null) {
            return reject(Rejection.EMPTY_STATEMENT, "Enter a SQL statement", -1);
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > maxSqlBytes) {
            return reject(Rejection.TOO_LARGE, "The statement is longer than " + maxSqlBytes + " bytes", -1);
        }
        int nul = sql.indexOf('\u0000');
        if (nul >= 0) {
            // The parser reads a C string and would stop at the NUL while the driver sent everything after it.
            return reject(Rejection.SYNTAX_ERROR, "The text contains a NUL character", nul + 1);
        }
        SqlParser.Parsed parsed = parser.parse(sql, standardConformingStrings);
        if (!parsed.ok()) {
            return reject(Rejection.SYNTAX_ERROR, "Syntax error: " + parsed.error(), parsed.position());
        }
        List<SqlParser.Statement> stmts = parsed.statements();
        if (stmts.isEmpty()) {
            return reject(Rejection.EMPTY_STATEMENT, "There is no statement to run", -1);
        }
        if (stmts.size() > 1) {
            return reject(Rejection.MULTIPLE_STATEMENTS, "Run one statement at a time (found " + stmts.size()
                    + "). Select the statement you want to run.", -1);
        }
        SqlParser.Statement s = stmts.get(0);
        int jdbc = jdbcStatementCount(sql, standardConformingStrings);
        if (jdbc != 1) {
            return reject(Rejection.GATE_DISAGREEMENT, "The statement could not be checked safely and was not run", -1);
        }
        if (s.kind().equals("CopyStmt") && !s.node().has("filename")) {
            return reject(Rejection.UNSUPPORTED, "COPY to or from the client (STDIN/STDOUT) is not supported; use SELECT or INSERT", -1);
        }
        if (s.kind().equals("ListenStmt") || s.kind().equals("UnlistenStmt")) {
            return reject(Rejection.UNSUPPORTED, "LISTEN/UNLISTEN is not supported in this application", -1);
        }
        return new Verdict(null, null, -1, s, classify(s.kind()));
    }

    /**
     * A script's verdict (EXE-03, decided 2026-10-19): either every statement approved, in order, or one rejection.
     * {@code statementIndex} is the statement that was refused (-1 for the whole text); {@code position} is in the
     * whole text (characters, 1-based).
     */
    public record ScriptVerdict(Rejection rejection, String message, int position, int statementIndex, List<ScriptStatement> statements) {
        public boolean approved() {
            return rejection == null;
        }
    }

    /** One approved statement of a script: its verdict and where its text starts in the script (0-based). */
    public record ScriptStatement(Verdict verdict, int offset) {
    }

    /**
     * Checks a script: the same limits as a single statement for the whole text, then PostgreSQL's parser finds the
     * statements, and <b>each</b> statement's text must pass {@link #check} on its own: exactly one statement for both
     * the parser and pgJDBC, nothing unsupported. Only those texts are ever sent, one at a time, so pgJDBC never splits
     * anything itself. Statement boundaries depend on {@code standard_conforming_strings}: the caller must stop the
     * script if the setting changes while it runs.
     */
    public ScriptVerdict checkScript(String sql, boolean standardConformingStrings, int maxStatements) {
        if (sql == null) {
            return scriptReject(Rejection.EMPTY_STATEMENT, "Enter a SQL statement", -1, -1);
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > maxSqlBytes) {
            return scriptReject(Rejection.TOO_LARGE, "The script is longer than " + maxSqlBytes + " bytes", -1, -1);
        }
        int nul = sql.indexOf('\u0000');
        if (nul >= 0) {
            return scriptReject(Rejection.SYNTAX_ERROR, "The text contains a NUL character", nul + 1, -1);
        }
        SqlParser.Parsed parsed = parser.parse(sql, standardConformingStrings);
        if (!parsed.ok()) {
            return scriptReject(Rejection.SYNTAX_ERROR, "Syntax error: " + parsed.error(), parsed.position(), -1);
        }
        List<SqlParser.Statement> stmts = parsed.statements();
        if (stmts.isEmpty()) {
            return scriptReject(Rejection.EMPTY_STATEMENT, "There is no statement to run", -1, -1);
        }
        if (stmts.size() > maxStatements) {
            return scriptReject(Rejection.TOO_MANY_STATEMENTS, "The script has " + stmts.size() + " statements; at most "
                    + maxStatements + " can run as one script", -1, -1);
        }
        List<ScriptStatement> out = new ArrayList<>(stmts.size());
        for (int i = 0; i < stmts.size(); i++) {
            SqlParser.Statement s = stmts.get(i);
            Verdict v = check(s.text(), standardConformingStrings);
            if (!v.approved()) {
                // Anything but UNSUPPORTED means the statement's own text didn't parse as exactly that one statement.
                boolean unsupported = v.rejection() == Rejection.UNSUPPORTED;
                return scriptReject(unsupported ? Rejection.UNSUPPORTED : Rejection.GATE_DISAGREEMENT,
                        unsupported ? "Statement " + (i + 1) + ": " + v.message()
                                : "Statement " + (i + 1) + " could not be checked safely, so nothing was run",
                        s.offset() + 1, i);
            }
            out.add(new ScriptStatement(v, s.offset()));
        }
        return new ScriptVerdict(null, null, -1, -1, List.copyOf(out));
    }

    private static ScriptVerdict scriptReject(Rejection r, String message, int position, int index) {
        return new ScriptVerdict(r, message, position, index, List.of());
    }

    /** Audit text for an approved statement: comments removed, constants masked (only with standard_conforming_strings on). */
    public String auditText(String statement) {
        return parser.auditText(statement);
    }

    /**
     * Why a statement deserves a confirmation before it runs (plan Phase 9): {@code drop}, {@code truncate},
     * {@code privileges} (GRANT/REVOKEâ€¦), {@code ddl}, {@code no_where} (an UPDATE or DELETE without WHERE anywhere
     * in the statement, including in a CTE) and {@code procedural} (a DO block can do anything). EXPLAIN only plans
     * and needs none; EXPLAIN ANALYZE runs the statement, so it gets {@code explain_analyze} (admin Phase 3) plus what
     * the statement itself would get. Empty: none.
     * <p>A UX safeguard against mistakes, <b>not a security control</b>: the user's PostgreSQL privileges decide what
     * the statement may do, and the user can always confirm.
     */
    public static List<String> destructiveReasons(SqlParser.Statement s) {
        String kind = s.kind();
        if (kind.equals("ExplainStmt")) {
            return explainReasons(s.node());
        }
        List<String> reasons = new ArrayList<>();
        String cls = classify(kind);
        if (kind.startsWith("Drop")) {
            reasons.add("drop");
        } else if (kind.equals("TruncateStmt")) {
            reasons.add("truncate");
        } else if (cls.equals("dcl")) {
            reasons.add("privileges");
        } else if (cls.equals("ddl")) {
            reasons.add("ddl");
        } else if (kind.equals("DoStmt")) {
            reasons.add("procedural");
        }
        if (modifiesWithoutWhere(s.node()) || modifiesWithoutWhere(kind, s.node())) {
            reasons.add("no_where");
        }
        return List.copyOf(reasons);
    }

    private static List<String> explainReasons(JsonNode explain) {
        if (!analyzes(explain.path("options"))) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("explain_analyze");
        JsonNode query = explain.path("query");
        if (query.has("CreateTableAsStmt")) {
            reasons.add("ddl"); // EXPLAIN ANALYZE CREATE TABLE … AS creates the table
        }
        if (modifiesWithoutWhere(query)) {
            reasons.add("no_where");
        }
        return List.copyOf(reasons);
    }

    /**
     * Whether EXPLAIN's options turn ANALYZE on: the last {@code analyze} option wins, as in PostgreSQL. Only an
     * explicit false, off or 0 counts as off; anything unclear counts as on (the safe side for a confirmation).
     */
    static boolean analyzes(JsonNode options) {
        boolean on = false;
        for (JsonNode o : options) {
            JsonNode d = o.path("DefElem");
            if (!"analyze".equals(d.path("defname").asText())) {
                continue;
            }
            JsonNode arg = d.path("arg");
            if (arg.has("String")) {
                String v = arg.path("String").path("sval").asText().toLowerCase(java.util.Locale.ROOT);
                on = !(v.equals("false") || v.equals("off") || v.equals("0"));
            } else if (arg.has("Integer")) {
                on = arg.path("Integer").path("ival").asInt(0) != 0;
            } else if (arg.has("Boolean")) {
                on = arg.path("Boolean").path("boolval").asBoolean(false);
            } else {
                on = true;
            }
        }
        return on;
    }

    private static boolean modifiesWithoutWhere(String kind, JsonNode node) {
        return (kind.equals("UpdateStmt") || kind.equals("DeleteStmt")) && !node.has("whereClause");
    }

    /** Any UpdateStmt or DeleteStmt below {@code node} that has no WHERE clause. */
    private static boolean modifiesWithoutWhere(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (modifiesWithoutWhere(child)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, JsonNode> f : node.properties()) {
            if (modifiesWithoutWhere(f.getKey(), f.getValue()) || modifiesWithoutWhere(f.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** True for COMMIT (and END): in a failed transaction PostgreSQL turns it into a rollback without an error. */
    static boolean isCommit(SqlParser.Statement s) {
        return s.kind().equals("TransactionStmt") && "TRANS_STMT_COMMIT".equals(s.node().path("kind").asText());
    }

    /** UX and audit metadata only (S2 P6: a data-modifying CTE is a SelectStmt); never used to allow or deny. */
    static String classify(String kind) {
        String c = CLASSES.get(kind);
        if (c != null) {
            return c;
        }
        if (kind.startsWith("Create") || kind.startsWith("Alter") || kind.startsWith("Drop") || kind.startsWith("Rename")
                || kind.startsWith("Comment") || kind.startsWith("Define") || kind.startsWith("Index") || kind.startsWith("Rule")
                || kind.startsWith("View")) {
            return "ddl";
        }
        return "other";
    }

    /** How many statements pgJDBC itself would send for this text (its client-side split, J1). */
    static int jdbcStatementCount(String sql, boolean standardConformingStrings) {
        try {
            List<NativeQuery> q = Parser.parseJdbcSql(sql, standardConformingStrings, false, true, false, false);
            int n = 0;
            for (NativeQuery nq : q) {
                if (!nq.nativeSql.isBlank()) {
                    n++;
                }
            }
            return n;
        } catch (SQLException e) {
            return -1;
        }
    }

    private static Verdict reject(Rejection r, String message, int position) {
        return new Verdict(r, message, position, null, null);
    }
}
