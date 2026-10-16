package pgweb;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.testsupport.PgTestCluster;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestConfig;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The command line: exit codes and messages for config, migrate and chain verification (plan Phase 3). */
class MainIT {

    static PgTestCluster pg;

    @TempDir
    Path tmp;

    @BeforeAll
    static void start() throws Exception {
        pg = PgTestCluster.start();
        pg.bootstrapAudit();
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.close();
        }
    }

    record Run(int rc, String out, String err) {
    }

    static Run run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int rc = Main.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), null);
        return new Run(rc, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void invalidConfigurationExitsWithCode2AndListsEveryProblem() throws Exception {
        Properties p = TestConfig.base(tmp);
        p.setProperty("auth.dataAdminSid", "admins");
        p.setProperty("server.portt", "1");
        p.setProperty("limits.maxRows", "-1");
        Path f = TestConfig.write(p, tmp.resolve("bad.properties"));
        for (String cmd : new String[] {"check-config", "serve", "migrate"}) {
            Run r = run(cmd, "--config", f.toString());
            assertThat(r.rc).as(cmd).isEqualTo(Main.BAD_CONFIG);
            assertThat(r.err).contains("auth.dataAdminSid is not a SID").contains("unknown setting server.portt").contains("limits.maxRows must be between");
        }
    }

    @Test
    void usageErrors() throws Exception {
        assertThat(run("serve").rc).isEqualTo(Main.BAD_CONFIG);
        assertThat(run("serve").err).contains("no configuration");
        assertThat(run("frobnicate", "--config", "x").err).contains("unknown command");
        assertThat(run("check-config", "--bogus").err).contains("unknown argument");
        assertThat(run("check-config", "--config=" + tmp.resolve("missing.properties")).err).contains("configuration file not found");
        assertThat(run("version").out).startsWith("pgweb ");
        assertThat(Main.run(new String[] {"check-config"}, System.out, System.err, tmp.resolve("missing.properties").toString())).isEqualTo(Main.BAD_CONFIG);
    }

    @Test
    void checkMigrateAndVerifyAgainstARealAuditDatabase() throws Exception {
        String db = pg.createAuditDatabase("audit_main_" + Math.abs(Seeds.random("main-db").nextInt(1_000_000)));
        Properties p = TestConfig.forCluster(pg, db, tmp);
        Path f = TestConfig.write(p, tmp.resolve("pgweb.properties"));

        Run check = run("check-config", "--config", f.toString());
        assertThat(check.rc).as(check.err).isEqualTo(Main.OK);
        assertThat(check.out).contains("configuration OK (mode test");

        Run m1 = run("migrate", "--config", f.toString());
        assertThat(m1.rc).as(m1.err).isEqualTo(Main.OK);
        assertThat(m1.out).contains("audit schema at version 1 (1 migrations applied)");
        assertThat(run("migrate", "--config", f.toString()).out).contains("audit schema at version 1 (0 migrations applied)");

        Random r = Seeds.random("main-events");
        int n = 3 + r.nextInt(10);
        try (Connection c = pg.connect("pgweb_app", pg.appPassword(), db);
             PreparedStatement ps = c.prepareStatement("INSERT INTO audit.event (event_id, event_type) VALUES (?, 'app_login')")) {
            for (int i = 0; i < n; i++) {
                ps.setObject(1, UUID.randomUUID());
                ps.executeUpdate();
            }
        }
        Run v = run("verify-audit-chain", "--config", f.toString());
        assertThat(v.rc).as(v.err).isEqualTo(Main.OK);
        assertThat(v.out).contains("audit chain intact: " + n + " records");

        try (Connection c = pg.superuser(db); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("UPDATE audit.event SET event_type = 'app_logout' WHERE seq = " + (1 + r.nextInt(n)));
        }
        Run broken = run("verify-audit-chain", "--config", f.toString());
        assertThat(broken.rc).isEqualTo(Main.CHAIN_BROKEN);
        assertThat(broken.err).contains("AUDIT CHAIN BROKEN at seq");
    }

    @Test
    void migrateAndVerifyNeedMigratorCredentials() throws Exception {
        Path f = TestConfig.write(TestConfig.base(tmp), tmp.resolve("nomig.properties"));
        assertThat(run("migrate", "--config", f.toString()).err).contains("audit.migrator.user");
        assertThat(run("verify-audit-chain", "--config", f.toString()).err).contains("audit.migrator.user");
    }

    @Test
    void unreachableAuditDatabaseIsAFailureWithoutSecrets() throws Exception {
        Properties p = TestConfig.base(tmp);
        String pw = "Pw" + UUID.randomUUID();
        p.setProperty("audit.migrator.user", "pgweb_migrator");
        p.setProperty("audit.migrator.password", "plain:" + pw);
        Path f = TestConfig.write(p, tmp.resolve("unreach.properties"));
        Run m = run("migrate", "--config", f.toString());
        assertThat(m.rc).isEqualTo(Main.FAILED);
        assertThat(m.err).contains("migration failed").doesNotContain(pw);
        Run v = run("verify-audit-chain", "--config", f.toString());
        assertThat(v.rc).isEqualTo(Main.FAILED);
        assertThat(v.err).contains("verification failed").doesNotContain(pw);
    }

    @Test
    void serviceStartsAndStopsWhileTheAuditDatabaseIsDown() throws Exception {
        // Fail-closed is enforced per request; startup itself must not depend on the audit DB (AUD-02).
        var cfg = TestConfig.load(TestConfig.base(tmp));
        try (Application app = Application.start(cfg)) {
            assertThat(app.port()).isPositive();
            assertThat(app.auditDatabase().ping()).isFalse();
            assertThat(app.config()).isSameAs(cfg);
        }
        Files.createDirectories(tmp.resolve("logs"));
    }
}
