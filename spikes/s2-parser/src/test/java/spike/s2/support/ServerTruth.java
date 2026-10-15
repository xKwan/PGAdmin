package spike.s2.support;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * PostgreSQL itself as the reference. The whole script is sent unchanged in one simple-protocol Query through the
 * Vert.x client, which (unlike pgJDBC) has no client-side SQL lexer: the server parses all of the text before running
 * any of it, and returns one result per statement. Everything runs inside a transaction that is rolled back, so
 * corpus items can be executed repeatedly.
 *
 * <p>pgJDBC was tried first and is <b>not</b> a usable reference: through the JDBC API a statement that returns neither
 * rows nor an update count ({@code CALL}, or an empty query) looks the same as "no more results", so counting stopped
 * early (S2 report, loop history).
 */
public final class ServerTruth implements AutoCloseable {

    /** Server outcome: statement count, or the SQLSTATE of the error. */
    public record Outcome(int count, String sqlState, String message) {
        public boolean ok() {
            return sqlState == null;
        }
    }

    private static final Vertx VERTX = Vertx.vertx();
    private final PgConnection conn;

    public ServerTruth(EphemeralPostgres pg, String app, boolean scsOff) throws Exception {
        PgConnectOptions o = new PgConnectOptions()
                .setHost("127.0.0.1")
                .setPort(pg.port())
                .setDatabase(EphemeralPostgres.DATABASE)
                .setUser(EphemeralPostgres.APP_USER)
                .setPassword(pg.appPassword())
                .setPipeliningLimit(1)
                .setProperties(Map.of("application_name", app));
        conn = await(PgConnection.connect(VERTX, o));
        await(conn.query("SET statement_timeout = 5000").execute());
        if (scsOff) {
            await(conn.query("SET standard_conforming_strings = off").execute());
            await(conn.query("SET escape_string_warning = off").execute());
        }
    }

    private static <T> T await(Future<T> f) throws Exception {
        try {
            return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private Outcome exec(String sql) throws Exception {
        try {
            RowSet<Row> rs = await(conn.query(sql).execute());
            int n = 0;
            for (RowSet<Row> r = rs; r != null; r = r.next()) {
                n++;
            }
            return new Outcome(n, null, null);
        } catch (PgException e) {
            return new Outcome(-1, e.getSqlState(), e.getMessage());
        }
    }

    /**
     * Runs the script with a sentinel statement appended on a new line. The sentinel means the server never receives
     * text with no statements: Vert.x 5.2.1 never completes such a query (S2 finding V8). Appending cannot make invalid
     * text valid: an unterminated string, identifier, dollar quote or comment stays unterminated, because the sentinel
     * contains an even number of single quotes (so an open string is still open at the end) and no {@code "}, {@code $}
     * or {@code *}{@code /}.
     */
    public Outcome run(String script) throws Exception {
        String sentinel = "sentinel_" + Long.toHexString(System.nanoTime());
        await(conn.query("BEGIN").execute());
        try {
            RowSet<Row> rs;
            try {
                rs = await(conn.query(script + "\n;SELECT '" + sentinel + "' AS s").execute());
            } catch (PgException e) {
                return new Outcome(-1, e.getSqlState(), e.getMessage());
            }
            int n = 0;
            RowSet<Row> last = null;
            for (RowSet<Row> r = rs; r != null; r = r.next()) {
                n++;
                last = r;
            }
            Row lastRow = last == null || last.size() != 1 ? null : last.iterator().next();
            boolean sentinelLast = lastRow != null && lastRow.size() == 1 && sentinel.equals(String.valueOf(lastRow.getValue(0)));
            if (!sentinelLast) {
                return new Outcome(-1, "SENTINEL", "sentinel was not the last result");
            }
            return new Outcome(n - 1, null, null);
        } finally {
            await(conn.query("ROLLBACK").execute());
        }
    }

    /** Runs each piece on its own, in order, in one transaction; returns each piece's outcome. */
    public List<Outcome> runPieces(List<String> pieces) throws Exception {
        List<Outcome> out = new ArrayList<>();
        await(conn.query("BEGIN").execute());
        try {
            for (String piece : pieces) {
                Outcome o = exec(piece);
                out.add(o);
                if (!o.ok()) {
                    break;
                }
            }
            return out;
        } finally {
            await(conn.query("ROLLBACK").execute());
        }
    }

    @Override
    public void close() throws Exception {
        await(conn.close());
    }
}
