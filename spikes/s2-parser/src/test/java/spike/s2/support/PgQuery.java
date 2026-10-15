package spike.s2.support;

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

/**
 * JNA binding to libpg_query (pg_query.dll built by Build-PgQuery.ps1) plus the small amount of Java the application
 * would need: statement boundaries, statement kinds, literal normalisation and comment removal.
 *
 * <p>All offsets reported by libpg_query are <b>UTF-8 byte</b> offsets; they are converted here, never treated as
 * Java char indexes.
 */
public final class PgQuery {

    /** Parser option: standard_conforming_strings = off (pg_query.h). */
    public static final int DISABLE_STANDARD_CONFORMING_STRINGS = 32;
    private static final int TOKEN_SQL_COMMENT = 275;
    private static final int TOKEN_C_COMMENT = 276;
    /** FCONST, SCONST, USCONST, BCONST, XCONST, ICONST (pg_query_scan_tokens.h). */
    private static final java.util.Set<Integer> CONSTANT_TOKENS = java.util.Set.of(260, 261, 262, 263, 264, 266);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A statement found by the parser. {@code text} is the exact source slice. */
    public record Stmt(String text, String kind, int byteStart, int byteLen) {
    }

    /** Parser outcome: statements, or the error the PostgreSQL grammar raised. */
    public record Parsed(List<Stmt> stmts, String error, int cursorPos) {
        public boolean ok() {
            return error == null;
        }
    }

    // ---- native layer -------------------------------------------------

    public static class PgQueryError extends Structure {
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

    public static class ParseResult extends Structure implements Structure.ByValue {
        public Pointer parse_tree;
        public Pointer stderr_buffer;
        public Pointer error;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("parse_tree", "stderr_buffer", "error");
        }
    }

    public static class NormalizeResult extends Structure implements Structure.ByValue {
        public Pointer normalized_query;
        public Pointer error;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("normalized_query", "error");
        }
    }

    public static class ScanTokensResult extends Structure implements Structure.ByValue {
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

        NormalizeResult pg_query_normalize(Pointer input);

        NormalizeResult pg_query_normalize_utility(Pointer input);

        void pg_query_free_normalize_result(NormalizeResult result);

        ScanTokensResult pg_query_scan_tokens(Pointer input);

        void pg_query_free_scan_tokens_result(ScanTokensResult result);
    }

    private final Lib lib;

    private PgQuery(Lib lib) {
        this.lib = lib;
    }

    public static PgQuery load(Path dll) {
        if (!Files.isRegularFile(dll)) {
            throw new IllegalStateException("pg_query.dll not found at " + dll + ". Run Build-PgQuery.ps1 first.");
        }
        return new PgQuery(Native.load(dll.toAbsolutePath().toString(), Lib.class));
    }

    private static Memory cString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        Memory m = new Memory(b.length + 1L);
        m.write(0, b, 0, b.length);
        m.setByte(b.length, (byte) 0);
        return m;
    }

    private static String utf8(Pointer p) {
        return p == null ? null : p.getString(0, StandardCharsets.UTF_8.name());
    }

    // ---- API ------------------------------------------------------------

    public Parsed parse(String sql, boolean standardConformingStringsOff) {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        ParseResult r = lib.pg_query_parse_opts(cString(sql), standardConformingStringsOff ? DISABLE_STANDARD_CONFORMING_STRINGS : 0);
        try {
            if (r.error != null) {
                PgQueryError e = new PgQueryError(r.error);
                return new Parsed(List.of(), utf8(e.message), e.cursorpos);
            }
            JsonNode tree = JSON.readTree(utf8(r.parse_tree));
            List<Stmt> out = new ArrayList<>();
            for (JsonNode raw : tree.path("stmts")) {
                int loc = raw.path("stmt_location").asInt(0);
                // stmt_len 0 means "rest of the string" (last statement without a trailing semicolon).
                int len = raw.path("stmt_len").asInt(0);
                if (len == 0) {
                    len = bytes.length - loc;
                }
                String text = new String(bytes, loc, len, StandardCharsets.UTF_8);
                Iterator<String> names = raw.path("stmt").fieldNames();
                out.add(new Stmt(text, names.hasNext() ? names.next() : "?", loc, len));
            }
            return new Parsed(out, null, -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lib.pg_query_free_parse_result(r);
        }
    }

    /** Literals replaced by $n. {@code utility=true} calls pg_query_normalize_utility, which skips non-utility statements. */
    public String normalize(String sql, boolean utility) {
        NormalizeResult r = utility ? lib.pg_query_normalize_utility(cString(sql)) : lib.pg_query_normalize(cString(sql));
        try {
            if (r.error != null) {
                throw new IllegalArgumentException("normalize failed: " + utf8(new PgQueryError(r.error).message));
            }
            return utf8(r.normalized_query);
        } finally {
            lib.pg_query_free_normalize_result(r);
        }
    }

    /** Removes SQL (--) and C-style comments using the PostgreSQL scanner's token positions. */
    public String stripComments(String sql) {
        return rewriteTokens(sql, false);
    }

    /**
     * Removes comments and replaces every constant token (string, dollar-quoted, Unicode, bit, hex, integer and
     * numeric constants) with $1..$n, using the PostgreSQL scanner. Works the same for every statement type, unlike
     * {@code pg_query_normalize}, which leaves literals in place inside some utility statements (S2 finding N2).
     */
    public String maskConstants(String sql) {
        return rewriteTokens(sql, true);
    }

    private String rewriteTokens(String sql, boolean maskConstants) {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        ScanTokensResult r = lib.pg_query_scan_tokens(cString(sql));
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
                } else if (maskConstants && CONSTANT_TOKENS.contains(token)) {
                    replacement = "$" + (++param);
                }
                if (replacement != null) {
                    sb.append(new String(bytes, pos, start - pos, StandardCharsets.UTF_8)).append(replacement);
                    pos = end;
                }
            }
            sb.append(new String(bytes, pos, bytes.length - pos, StandardCharsets.UTF_8));
            return sb.toString();
        } finally {
            lib.pg_query_free_scan_tokens_result(r);
        }
    }

    /**
     * Audit text (plan §6): comments removed and every constant replaced, by the PostgreSQL scanner. Not
     * {@code pg_query_normalize}: it leaves literals inside some utility statements (COMMENT ON, PREPARE, EXECUTE,
     * COPY ... PROGRAM, server options, column DEFAULT, BEGIN ATOMIC bodies), and {@code pg_query_normalize_utility}
     * skips DML entirely (S2 findings N1, N2).
     */
    public String auditText(String sql) {
        return maskConstants(sql);
    }
}
