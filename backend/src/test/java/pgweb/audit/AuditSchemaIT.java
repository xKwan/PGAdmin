package pgweb.audit;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pgweb.testsupport.PgTestCluster;
import pgweb.testsupport.Seeds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit schema on a real PostgreSQL 18 throwaway cluster (plan Phase 3): the shipped bootstrap script, the
 * Flyway migration, INSERT-only runtime role, append-only triggers, hash chain and partitions.
 */
class AuditSchemaIT {

    static PgTestCluster pg;
    static final AtomicInteger DBS = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        pg = PgTestCluster.start();
        pg.bootstrapAudit();
        MigrateResult r = migrate("pgweb_audit");
        assertThat(r.migrationsExecuted).isEqualTo(1);
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.close();
        }
    }

    static MigrateResult migrate(String db) {
        return AuditMigrator.migrate(pg.jdbcUrl(db), "pgweb_migrator", pg.migratorPassword(), "pgweb_app");
    }

    /** A fresh, migrated audit database for tests that damage the chain on purpose. */
    static String freshDb() throws Exception {
        String name = "audit_t" + DBS.incrementAndGet();
        pg.createAuditDatabase(name);
        migrate(name);
        return name;
    }

    static Connection app(String db) throws SQLException {
        return pg.connect("pgweb_app", pg.appPassword(), db);
    }

    static Connection migrator(String db) throws SQLException {
        return pg.connect("pgweb_migrator", pg.migratorPassword(), db);
    }

    static void insertEvent(Connection c, Random r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit.event (event_id, event_type, windows_user, user_sid, data_admin, profile, database_name, "
                        + "sql_normalised, sql_hmac, row_count, duration_ms, details) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, List.of("exec_intent", "exec_outcome", "app_login", "authz_denied", "db_connect_result").get(r.nextInt(5)));
            ps.setString(3, "CORP\\user" + r.nextInt(50));
            ps.setString(4, "S-1-5-21-" + r.nextInt(1_000_000) + "-" + (1000 + r.nextInt(9000)));
            ps.setBoolean(5, r.nextBoolean());
            ps.setString(6, r.nextBoolean() ? "internal" : "external");
            ps.setString(7, "db_" + r.nextInt(10));
            ps.setString(8, "SELECT * FROM t WHERE id = $1 AND name = $2 -- " + "é✓漢".charAt(r.nextInt(3)));
            ps.setString(9, String.format("%064x", new java.math.BigInteger(256, r)));
            ps.setLong(10, r.nextInt(100_000));
            ps.setLong(11, r.nextInt(60_000));
            ps.setString(12, "{\"n\":" + r.nextInt() + ",\"k\":\"quote\\\" é ' \\\\ " + r.nextInt(100) + "\"}");
            ps.executeUpdate();
        }
    }

    static ChainVerifier.Result verify(String db) throws SQLException {
        try (Connection c = migrator(db)) {
            return ChainVerifier.verify(c);
        }
    }

    // ------------------------------------------------------------------

    @Test
    void migrationIsIdempotent() {
        assertThat(migrate("pgweb_audit").migrationsExecuted).isZero();
    }

    @Test
    void concurrentInsertsFormAnIntactContiguousChain() throws Exception {
        String db = freshDb();
        Random rnd = Seeds.random("concurrent-chain");
        int threads = 2 + rnd.nextInt(7);
        int perThread = 10 + rnd.nextInt(40);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                long s = rnd.nextLong();
                fs.add(pool.submit(() -> {
                    Random r = new Random(s);
                    try (Connection c = app(db)) {
                        for (int i = 0; i < perThread; i++) {
                            insertEvent(c, r);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        ChainVerifier.Result res = verify(db);
        assertThat(res.intact()).as(res.problem()).isTrue();
        assertThat(res.rows()).isEqualTo((long) threads * perThread);
    }

    @Test
    void runtimeRoleCanOnlyInsert() throws Exception {
        String db = "pgweb_audit";
        Random r = Seeds.random("privileges");
        try (Connection c = app(db)) {
            insertEvent(c, r);
            for (String sql : List.of(
                    "SELECT count(*) FROM audit.event",
                    "UPDATE audit.event SET outcome = 'x'",
                    "DELETE FROM audit.event",
                    "TRUNCATE audit.event",
                    "SELECT * FROM audit.chain_head",
                    "UPDATE audit.chain_head SET seq = 0",
                    "INSERT INTO audit.event (event_id, event_type, seq) VALUES (gen_random_uuid(), 'app_login', 1)",
                    "INSERT INTO audit.event (event_id, event_type, occurred_at) VALUES (gen_random_uuid(), 'app_login', now() - interval '1 year')",
                    "INSERT INTO audit.event (event_id, event_type, row_hash, prev_hash) VALUES (gen_random_uuid(), 'app_login', '\\x00', '\\x00')",
                    "CREATE TABLE audit.x (i int)",
                    "DROP TABLE audit.event_default",
                    "SELECT audit.chain_event()")) {
                try (Statement s = c.createStatement()) {
                    assertThatThrownBy(() -> s.execute(sql)).as(sql).isInstanceOfSatisfying(SQLException.class,
                            e -> assertThat(e.getSQLState()).as(sql + ": " + e.getMessage()).isIn("42501", "0A000"));
                }
            }
        }
    }

    @Test
    void evenTheOwnerCannotChangeOrDeleteRecords() throws Exception {
        String db = freshDb();
        try (Connection c = app(db)) {
            insertEvent(c, Seeds.random("owner"));
        }
        try (Connection c = migrator(db); Statement s = c.createStatement()) {
            for (String sql : List.of("UPDATE audit.event SET outcome = 'forged'", "DELETE FROM audit.event", "TRUNCATE audit.event",
                    "DELETE FROM audit.chain_head")) {
                assertThatThrownBy(() -> s.execute(sql)).as(sql)
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("append-only"));
            }
        }
        assertThat(verify(db).intact()).isTrue();
    }

    @Test
    void verifierDetectsAnEditedRecord() throws Exception {
        String db = freshDb();
        Random r = Seeds.random("tamper-edit");
        int n = 5 + r.nextInt(20);
        try (Connection c = app(db)) {
            for (int i = 0; i < n; i++) {
                insertEvent(c, r);
            }
        }
        long victim = 1 + r.nextInt(n);
        try (Connection c = pg.superuser(db); Statement s = c.createStatement()) {
            // A superuser can disable the protection: the chain must still reveal the change.
            s.execute("SET session_replication_role = replica");
            String column = List.of("outcome = 'forged'", "row_count = row_count + 1", "windows_user = 'CORP\\\\someone_else'",
                    "occurred_at = occurred_at + interval '1 second'").get(r.nextInt(4));
            assertThat(s.executeUpdate("UPDATE audit.event SET " + column + " WHERE seq = " + victim)).isEqualTo(1);
        }
        ChainVerifier.Result res = verify(db);
        assertThat(res.intact()).isFalse();
        assertThat(res.firstBadSeq()).isEqualTo(victim);
        assertThat(res.problem()).contains("does not match its hash");
    }

    @Test
    void verifierDetectsADeletedRecord() throws Exception {
        String db = freshDb();
        Random r = Seeds.random("tamper-delete");
        int n = 5 + r.nextInt(20);
        try (Connection c = app(db)) {
            for (int i = 0; i < n; i++) {
                insertEvent(c, r);
            }
        }
        long victim = 1 + r.nextInt(n - 1);
        try (Connection c = pg.superuser(db); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("DELETE FROM audit.event WHERE seq = " + victim);
        }
        ChainVerifier.Result res = verify(db);
        assertThat(res.intact()).isFalse();
        assertThat(res.firstBadSeq()).isEqualTo(victim);
        assertThat(res.problem()).contains("missing record");
    }

    @Test
    void verifierDetectsRecordsRemovedFromTheEnd() throws Exception {
        String db = freshDb();
        Random r = Seeds.random("tamper-tail");
        int n = 5 + r.nextInt(20);
        try (Connection c = app(db)) {
            for (int i = 0; i < n; i++) {
                insertEvent(c, r);
            }
        }
        int removed = 1 + r.nextInt(3);
        try (Connection c = pg.superuser(db); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("DELETE FROM audit.event WHERE seq > " + (n - removed));
        }
        ChainVerifier.Result res = verify(db);
        assertThat(res.intact()).isFalse();
        assertThat(res.problem()).contains("chain head");
    }

    @Test
    void verifierDetectsABrokenLink() throws Exception {
        String db = freshDb();
        Random r = Seeds.random("tamper-link");
        int n = 3 + r.nextInt(10);
        try (Connection c = app(db)) {
            for (int i = 0; i < n; i++) {
                insertEvent(c, r);
            }
        }
        long victim = 2 + r.nextInt(n - 1);
        try (Connection c = pg.superuser(db); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("UPDATE audit.event SET prev_hash = sha256(prev_hash) WHERE seq = " + victim);
        }
        ChainVerifier.Result res = verify(db);
        assertThat(res.intact()).isFalse();
        assertThat(res.firstBadSeq()).isEqualTo(victim);
        assertThat(res.problem()).contains("does not link");
    }

    @Test
    void verifierDetectsAForgedChainHeadHash() throws Exception {
        String db = freshDb();
        try (Connection c = app(db)) {
            insertEvent(c, Seeds.random("forged-head"));
        }
        try (Connection c = pg.superuser(db); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE audit.chain_head SET row_hash = sha256(row_hash)");
        }
        ChainVerifier.Result res = verify(db);
        assertThat(res.intact()).isFalse();
        assertThat(res.problem()).contains("chain head");
    }

    @Test
    void verifierDetectsAMissingChainHead() throws Exception {
        String db = freshDb();
        try (Connection c = pg.superuser(db); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("DELETE FROM audit.chain_head");
        }
        ChainVerifier.Result res = verify(db);
        assertThat(res.intact()).isFalse();
        assertThat(res.problem()).contains("chain head missing");
    }

    @Test
    void verifierLeavesTheConnectionAsItFoundIt() throws Exception {
        String db = freshDb();
        try (Connection c = migrator(db)) {
            c.setAutoCommit(true);
            assertThat(ChainVerifier.verify(c).intact()).isTrue();
            assertThat(c.getAutoCommit()).isTrue();
            c.setAutoCommit(false);
            assertThat(ChainVerifier.verify(c).intact()).isTrue();
            assertThat(c.getAutoCommit()).isFalse();
        }
    }

    @Test
    void emptyChainIsIntact() throws Exception {
        ChainVerifier.Result res = verify(freshDb());
        assertThat(res.intact()).isTrue();
        assertThat(res.rows()).isZero();
    }

    @Test
    void recordsLandInMonthlyPartitionsAndAppCanCreateMore() throws Exception {
        String db = freshDb();
        try (Connection c = app(db)) {
            insertEvent(c, Seeds.random("partitions"));
            try (PreparedStatement ps = c.prepareStatement("SELECT audit.ensure_partitions(?)")) {
                ps.setInt(1, 15);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(3); // migration created months 0..12; 13..15 are new
                }
            }
            try (Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.execute("SELECT audit.ensure_partitions(100)")).hasMessageContaining("between 0 and 36");
            }
        }
        try (Connection c = pg.superuser(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT tableoid::regclass::text FROM audit.event")) {
            rs.next();
            assertThat(rs.getString(1)).matches("audit\\.event_y\\d{4}m\\d{2}");
        }
    }

    @Test
    void auditDatabaseHelperPingsAndMaintainsPartitions() throws Exception {
        String db = freshDb();
        pgweb.config.Config.Audit cfg = new pgweb.config.Config.Audit(pg.jdbcUrl(db), "pgweb_app",
                new pgweb.config.Secret(pg.appPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new pgweb.config.Secret(new byte[32]), java.nio.file.Path.of("spool"), java.util.Optional.empty(), java.util.Optional.empty());
        try (AuditDatabase a = new AuditDatabase(cfg)) {
            assertThat(a.ping()).isTrue();
            assertThat(a.ensurePartitions(12)).isZero();
            try (Connection c = a.connection()) {
                assertThat(c.isValid(2)).isTrue();
            }
        }
        pgweb.config.Config.Audit wrong = new pgweb.config.Config.Audit(pg.jdbcUrl(db), "pgweb_app",
                new pgweb.config.Secret("wrong".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new pgweb.config.Secret(new byte[32]), java.nio.file.Path.of("spool"), java.util.Optional.empty(), java.util.Optional.empty());
        try (AuditDatabase a = new AuditDatabase(wrong)) {
            assertThat(a.ping()).isFalse();
        }
    }

    @Test
    void roleNamesAreQuotedForGrants() {
        assertThat(AuditMigrator.quoteIdent("pgweb_app")).isEqualTo("\"pgweb_app\"");
        assertThat(AuditMigrator.quoteIdent("we\"ird")).isEqualTo("\"we\"\"ird\"");
        assertThatThrownBy(() -> AuditMigrator.quoteIdent("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditMigrator.quoteIdent("x".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditMigrator.quoteIdent(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditMigrator.quoteIdent("a b")).isInstanceOf(IllegalArgumentException.class);
    }
}
