package pgweb.sqlparse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * PostgreSQL's own parser (libpg_query 18.1.0, built by native/libpg_query/Build-PgQuery.ps1) through JNA
 * (ADR-0009, spike S2). Used for the single-statement gate, statement classification (UX and audit metadata only,
 * never a security decision: S2 P6) and audit text (constants masked by the scanner: S2 N3).
 *
 * <p>Statement offsets from libpg_query are UTF-8 <b>byte</b> offsets (S2 P5), but a syntax error's cursor position
 * is in <b>characters</b> (code points; PostgreSQL computes it with pg_mbstrlen: finding P7-1). Both are converted
 * here. The library is
 * called only from one dedicated thread (see {@link #worker}). Input size is limited by the caller before parsing
 * (threat T31).
 */
public final class SqlParser {

    private static final int DISABLE_STANDARD_CONFORMING_STRINGS = 32;
    private static final int TOKEN_SQL_COMMENT = 275;
    private static final int TOKEN_C_COMMENT = 276;
    /** FCONST, SCONST, USCONST, BCONST, XCONST, ICONST (pg_query_scan_tokens.h). */
    private static final Set<Integer> CONSTANT_TOKENS = Set.of(260, 261, 262, 263, 264, 266);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One statement: its exact source text, top-level node type (for example {@code SelectStmt}) and parse node.
     */
    public record Statement(String text, String kind, JsonNode node) {
    }

    /** Statements, or the grammar's error with its character position (1-based, -1 if unknown). */
    public record Parsed(List<Statement> statements, String error, int position) {
        public boolean ok() {
            return error == null;
        }
    }

    // ---- native layer -------------------------------------------------

    public static final class PgQueryError extends Structure {
        public Pointer message;
        public Pointer funcname;
        public Pointer filename;
        public int lineno;
        public int cursorpos;
        public Pointer context;

        public PgQueryError(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("message", "funcname", "filename", "lineno", "cursorpos", "context");
        }
    }

    public static final class ParseResult extends Structure implements Structure.ByValue {
        public Pointer parse_tree;
        public Pointer stderr_buffer;
        public Pointer error;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("parse_tree", "stderr_buffer", "error");
        }
    }

    public static final class ScanTokensResult extends Structure implements Structure.ByValue {
        public Pointer tokens;
        public int n_tokens;
        public Pointer error;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("tokens", "n_tokens", "error");
        }
    }

    interface Lib extends Library {
        ParseResult pg_query_parse_opts(Pointer input, int parserOptions);

        void pg_query_free_parse_result(ParseResult result);

        ScanTokensResult pg_query_scan_tokens(Pointer input);

        void pg_query_free_scan_tokens_result(ScanTokensResult result);
    }

    private final Lib lib;
    /**
     * Every call into libpg_query runs on this one long-lived thread (finding P7-5). The library keeps per-thread
     * state (emulated thread-local storage in this MinGW build) with clean-up code that runs when a thread exits, and
     * Tomcat retires idle request threads. An intermittent native memory fault was seen while the parser was called
     * from many threads; with one thread that lives as long as the process, the library never sees a thread exit
     * and never runs concurrently. Calls take microseconds to milliseconds, so serialising them costs little.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pgweb-sql-parser");
        t.setDaemon(true);
        return t;
    });

    private SqlParser(Lib lib) {
        this.lib = lib;
    }

    private <T> T onParserThread(Callable<T> call) {
        Future<T> f = worker.submit(call);
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("SQL parser failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the SQL parser", e);
        }
    }

    /** Loads the parser DLL; fails clearly if it is missing (execution can't be enabled without it). */
    public static SqlParser load(Path dll) {
        if (!Files.isRegularFile(dll)) {
            throw new IllegalStateException("SQL parser library not found: " + dll.toAbsolutePath());
        }
        return new SqlParser(Native.load(dll.toAbsolutePath().toString(), Lib.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8")));
    }

    private static Memory cString(byte[] b) {
        Memory m = new Memory(b.length + 1L);
        m.write(0, b, 0, b.length);
        m.setByte(b.length, (byte) 0);
        return m;
    }

    private static String utf8(Pointer p) {
        return p == null ? null : p.getString(0, StandardCharsets.UTF_8.name());
    }

    /** Java (UTF-16) position, 1-based, of a 1-based code-point position in {@code sql}; -1 if unknown. */
    public static int utf16Position(String sql, int codePointPos) {
        if (codePointPos <= 0) {
            return -1;
        }
        int cps = Math.min(codePointPos - 1, sql.codePointCount(0, sql.length()));
        return sql.offsetByCodePoints(0, cps) + 1;
    }

    /**
     * Parses {@code sql} as PostgreSQL 18 would.
     * @param standardConformingStrings the session's setting: it changes where statements end (S2 P4)
     */
    public Parsed parse(String sql, boolean standardConformingStrings) {
        return onParserThread(() -> parseHere(sql, standardConformingStrings));
    }

    private Parsed parseHere(String sql, boolean standardConformingStrings) {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        ParseResult r = lib.pg_query_parse_opts(cString(bytes), standardConformingStrings ? 0 : DISABLE_STANDARD_CONFORMING_STRINGS);
        try {
            if (r.error != null) {
                PgQueryError e = new PgQueryError(r.error);
                return new Parsed(List.of(), utf8(e.message), utf16Position(sql, e.cursorpos));
            }
            JsonNode tree = JSON.readTree(utf8(r.parse_tree));
            List<Statement> out = new ArrayList<>();
            for (JsonNode raw : tree.path("stmts")) {
                int loc = raw.path("stmt_location").asInt(0);
                int len = raw.path("stmt_len").asInt(0);
                if (len == 0) {
                    len = bytes.length - loc; // 0 = "to the end"
                }
                Iterator<String> names = raw.path("stmt").fieldNames();
                String kind = names.hasNext() ? names.next() : "Unknown";
                out.add(new Statement(new String(bytes, loc, len, StandardCharsets.UTF_8), kind, raw.path("stmt").path(kind)));
            }
            return new Parsed(List.copyOf(out), null, -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lib.pg_query_free_parse_result(r);
        }
    }

    /**
     * Audit text (docs/audit-policy.md §4): comments removed and every constant replaced by {@code $n}, using the
     * PostgreSQL scanner, for every statement type. Only valid for {@code standard_conforming_strings=on}; the caller
     * stores no text otherwise.
     */
    public String auditText(String sql) {
        return onParserThread(() -> auditTextHere(sql));
    }

    private String auditTextHere(String sql) {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        ScanTokensResult r = lib.pg_query_scan_tokens(cString(bytes));
        try {
            if (r.error != null) {
                throw new IllegalArgumentException("scan failed: " + utf8(new PgQueryError(r.error).message));
            }
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            int param = 0;
            int tokenSize = 16; // int start, int end, enum token, enum keyword_kind
            for (int i = 0; i < r.n_tokens; i++) {
                int start = r.tokens.getInt((long) i * tokenSize);
                int end = r.tokens.getInt((long) i * tokenSize + 4);
                int token = r.tokens.getInt((long) i * tokenSize + 8);
                String replacement = null;
                if (token == TOKEN_SQL_COMMENT || token == TOKEN_C_COMMENT) {
                    replacement = " ";
                } else if (CONSTANT_TOKENS.contains(token)) {
                    replacement = "$" + (++param);
                }
                if (replacement != null) {
                    sb.append(new String(bytes, pos, start - pos, StandardCharsets.UTF_8)).append(replacement);
                    pos = end;
                }
            }
            sb.append(new String(bytes, pos, bytes.length - pos, StandardCharsets.UTF_8));
            return sb.toString().strip();
        } finally {
            lib.pg_query_free_scan_tokens_result(r);
        }
    }
}
