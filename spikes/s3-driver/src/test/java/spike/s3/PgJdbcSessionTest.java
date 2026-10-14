package spike.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import spike.s3.support.EphemeralPostgres;
import spike.s3.support.Findings;
import spike.s3.support.SpikeExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan §3.5 / §3.6 / S3: transaction-state tracking, cancel and companion terminate with pgJDBC. */
@ExtendWith(SpikeExtension.class)
class PgJdbcSessionTest {

    private static String uniq(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static TransactionState state(Connection c) throws SQLException {
        return c.unwrap(BaseConnection.class).getTransactionState();
    }

    private static int backendPid(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static <T> T pick(Random rnd, List<T> options) {
        return options.get(rnd.nextInt(options.size()));
    }

    /**
     * A user-typed BEGIN while the JDBC connection is in autocommit mode: getAutoCommit() stays true, but the
     * driver's transaction state (from ReadyForQuery) shows the open or failed transaction.
     */
    @ParameterizedTest(name = "failInside={0}")
    @ValueSource(booleans = {false, true})
    void userTransactionInAutocommitIsTracked(boolean fail, EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        String begin = pick(rnd, List.of("BEGIN", "begin", "START TRANSACTION", "BEGIN ISOLATION LEVEL REPEATABLE READ",
                "START TRANSACTION READ ONLY", "BEGIN WORK"));
        String end = pick(rnd, List.of("COMMIT", "END", "ROLLBACK", "ABORT", "COMMIT WORK"));
        try (Connection c = pg.app(uniq("txn")); Statement s = c.createStatement()) {
            assertTrue(c.getAutoCommit());
            assertEquals(TransactionState.IDLE, state(c));
            s.execute(begin);
            assertTrue(c.getAutoCommit(), "pgJDBC does not change its autocommit flag for a user BEGIN");
            assertEquals(TransactionState.OPEN, state(c), "the driver sees the open transaction");
            s.execute("SELECT 1");
            assertEquals(TransactionState.OPEN, state(c));
            if (fail) {
                SQLException e = assertThrows(SQLException.class, () -> s.execute("SELECT 1/0"));
                assertEquals("22012", e.getSQLState());
                assertEquals(TransactionState.FAILED, state(c), "failed transaction is visible");
                SQLException blocked = assertThrows(SQLException.class, () -> s.execute("SELECT 1"));
                assertEquals("25P02", blocked.getSQLState(), "commands are refused until the transaction ends");
            }
            s.execute(end);
            assertEquals(TransactionState.IDLE, state(c), "transaction ended with '" + end + "'");
            findings.record("pgjdbc", "transaction_state",
                    Map.of("begin", begin, "end", end, "failedInside", fail, "result", "OPEN/FAILED/IDLE tracked; getAutoCommit() stayed true"));
        }
    }

    /** Statement.cancel() from another thread ends a running statement with 57014; the connection stays usable. */
    @ParameterizedTest(name = "inTransaction={0}")
    @ValueSource(booleans = {false, true})
    void cancelEndsARunningStatement(boolean inTransaction, EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        long delayMs = 200 + rnd.nextInt(1300);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = pg.app(uniq("cancel")); Statement s = c.createStatement()) {
            if (inTransaction) {
                s.execute("BEGIN");
            }
            long t0 = System.nanoTime();
            Future<SQLException> running = ex.submit(() -> {
                try {
                    s.execute("SELECT pg_sleep(30)");
                    return null;
                } catch (SQLException e) {
                    return e;
                }
            });
            Thread.sleep(delayMs);
            s.cancel();
            SQLException e = running.get(10, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(e != null && "57014".equals(e.getSQLState()), "expected 57014, got " + e);
            assertTrue(elapsedMs < delayMs + 5_000, "cancel took effect promptly: " + elapsedMs + " ms");
            if (inTransaction) {
                assertEquals(TransactionState.FAILED, state(c), "cancel inside a transaction aborts it");
                s.execute("ROLLBACK");
            }
            assertEquals(TransactionState.IDLE, state(c));
            try (ResultSet rs = s.executeQuery("SELECT 42")) {
                rs.next();
                assertEquals(42, rs.getInt(1), "connection still usable after cancel");
            }
            findings.record("pgjdbc", "cancel",
                    Map.of("delayMs", delayMs, "elapsedMs", elapsedMs, "inTransaction", inTransaction, "sqlState", e.getSQLState()));
        } finally {
            ex.shutdownNow();
        }
    }

    /**
     * Companion connection (same role) terminates the main backend, e.g. when cancel does not work. The main
     * connection's statement fails, its transaction is rolled back, and the connection is dead.
     */
    @Test
    void companionCanTerminateItsOwnRolesBackend(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        String t = uniq("t");
        int value = rnd.nextInt(1000);
        try (Connection setup = pg.app(uniq("setup")); Statement s = setup.createStatement()) {
            s.execute("CREATE TABLE " + t + " (id int PRIMARY KEY, v int)");
            s.execute("INSERT INTO " + t + " VALUES (1, " + value + ")");
        }
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Connection main = pg.app(uniq("main"));
        try (Connection companion = pg.app(uniq("companion"))) {
            int pid = backendPid(main);
            Statement ms = main.createStatement();
            ms.execute("BEGIN");
            ms.execute("UPDATE " + t + " SET v = v + 1 WHERE id = 1");
            Future<SQLException> running = ex.submit(() -> {
                try {
                    ms.execute("SELECT pg_sleep(30)");
                    return null;
                } catch (SQLException e) {
                    return e;
                }
            });
            Thread.sleep(100 + rnd.nextInt(500));
            try (Statement cs = companion.createStatement();
                 ResultSet rs = cs.executeQuery("SELECT pg_terminate_backend(" + pid + ", 5000)")) {
                rs.next();
                assertTrue(rs.getBoolean(1), "same-role terminate is allowed");
            }
            SQLException e = running.get(10, TimeUnit.SECONDS);
            assertTrue(e != null, "the running statement failed");
            assertFalse(main.isValid(2), "terminated connection is no longer valid");
            try (Statement cs = companion.createStatement();
                 ResultSet rs = cs.executeQuery("SELECT v FROM " + t + " WHERE id = 1")) {
                rs.next();
                assertEquals(value, rs.getInt(1), "the terminated transaction was rolled back");
            }
            try (Statement cs = companion.createStatement();
                 ResultSet rs = cs.executeQuery("SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE c.relname = '" + t + "'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "no locks remain on the table");
            }
            findings.record("pgjdbc", "companion_terminate",
                    Map.of("mainSqlState", String.valueOf(e.getSQLState()), "result", "rolled back, locks released, connection invalid"));
        } finally {
            ex.shutdownNow();
            try {
                main.close();
            } catch (SQLException ignored) {
                // already dead
            }
        }
    }

    /** A non-superuser cannot cancel or terminate another role's backend (42501), so a companion stays in its lane. */
    @ParameterizedTest
    @ValueSource(strings = {"pg_terminate_backend", "pg_cancel_backend"})
    void companionCannotSignalAnotherRolesBackend(String fn, EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        try (Connection victim = pg.other(uniq("victim")); Connection attacker = pg.app(uniq("attacker"))) {
            int pid = backendPid(victim);
            try (Statement s = attacker.createStatement()) {
                SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("SELECT " + fn + "(" + pid + ")"));
                assertEquals("42501", e.getSQLState());
            }
            assertTrue(victim.isValid(2));
            findings.record("pgjdbc", "cross_role_signal", fn + " on another role's backend: 42501 permission denied");
        }
    }

    /** User-issued SET statement_timeout overrides the role default (S5 finding), so the app needs its own timer. */
    @Test
    void userCanOverrideStatementTimeout(EphemeralPostgres pg, Random rnd, Findings findings) throws Exception {
        int ms = 100 + rnd.nextInt(300);
        try (Connection c = pg.app(uniq("timeout")); Statement s = c.createStatement()) {
            s.execute("SET statement_timeout = " + ms);
            SQLException e = assertThrows(SQLException.class, () -> s.execute("SELECT pg_sleep(5)"));
            assertEquals("57014", e.getSQLState());
            s.execute("SET statement_timeout = 0");
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(ms * 3L);
                    s.cancel();
                } catch (Exception ignored) {
                    // the assertion below reports the outcome
                }
            });
            SQLException e2 = assertThrows(SQLException.class, () -> s.execute("SELECT pg_sleep(10)"));
            f.get(5, TimeUnit.SECONDS);
            assertEquals("57014", e2.getSQLState(), "only the external cancel stopped it");
            findings.record("pgjdbc", "statement_timeout_override",
                    "SET statement_timeout = 0 disables the backstop; an app-side timer with cancel() is required");
        }
    }
}
