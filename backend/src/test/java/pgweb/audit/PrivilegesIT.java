package pgweb.audit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.testsupport.PgTestCluster;
import pgweb.testsupport.TestApps;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * deploy/sql/verify-privileges.sql (plan Phase 11) against the shipped bootstrap and migration: every check passes,
 * and each kind of excess privilege is caught.
 */
class PrivilegesIT {

    @TempDir
    static Path tmp;
    static TestApps apps;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp); // runs deploy/sql/audit-bootstrap.sql and the audit migration
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    record Result(int exit, String output) {
    }

    static Result verify() throws Exception {
        PgTestCluster pg = apps.pg();
        Path script = Path.of("..", "deploy", "sql", "verify-privileges.sql").toAbsolutePath().normalize();
        Path out = Files.createTempFile(tmp, "verify", ".txt");
        ProcessBuilder pb = new ProcessBuilder(pg.pgBin().resolve("psql.exe").toString(), "-X", "-v", "ON_ERROR_STOP=1",
                "-h", "localhost", "-p", String.valueOf(pg.port()), "-U", PgTestCluster.SUPERUSER, "-d", "pgweb_audit", "-f", script.toString())
                .redirectErrorStream(true).redirectOutput(out.toFile()).redirectInput(ProcessBuilder.Redirect.from(Files.createTempFile(tmp, "in", ".txt").toFile()));
        pb.environment().put("PGPASSWORD", pg.superPassword());
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
    }

    static void sql(String database, String... statements) throws Exception {
        try (Connection c = apps.pg().superuser(database); Statement s = c.createStatement()) {
            for (String st : statements) {
                s.execute(st);
            }
        }
    }

    @Test
    void theShippedSetupPassesEveryCheck() throws Exception {
        Result r = verify();
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.output()).contains("all checks passed").doesNotContain("FAIL");
        assertThat(r.output().lines().filter(l -> l.contains("| OK "))).hasSizeGreaterThan(40);
    }

    @Test
    void excessPrivilegesAreCaught() throws Exception {
        List<String[]> cases = List.of(
                new String[] {"pgweb_audit", "GRANT SELECT ON audit.event TO pgweb_app", "REVOKE SELECT ON audit.event FROM pgweb_app", "pgweb_app cannot SELECT audit.event"},
                new String[] {"pgweb_audit", "GRANT UPDATE ON audit.event TO pgweb_audit_reader", "REVOKE UPDATE ON audit.event FROM pgweb_audit_reader", "pgweb_audit_reader cannot UPDATE audit.event"},
                new String[] {"postgres", "ALTER ROLE pgweb_app CREATEROLE", "ALTER ROLE pgweb_app NOCREATEROLE", "role pgweb_app has no superuser"},
                new String[] {"postgres", "GRANT pg_read_all_data TO pgweb_audit_reader", "REVOKE pg_read_all_data FROM pgweb_audit_reader", "pgweb_audit_reader is not a member of pg_read_all_data"},
                new String[] {"pgweb_audit", "GRANT CREATE ON SCHEMA audit TO pgweb_app", "REVOKE CREATE ON SCHEMA audit FROM pgweb_app", "pgweb_app cannot create objects in schema audit"},
                new String[] {"postgres", "GRANT CONNECT ON DATABASE pgweb_audit TO PUBLIC", "REVOKE CONNECT ON DATABASE pgweb_audit FROM PUBLIC", "PUBLIC cannot connect to pgweb_audit"});
        for (String[] c : cases) {
            sql(c[0], c[1]);
            try {
                Result r = verify();
                assertThat(r.exit()).as(c[1]).isNotZero();
                assertThat(r.output()).as(c[1]).contains("check(s) failed");
                assertThat(r.output().lines().filter(l -> l.contains("FAIL") && l.contains(c[3]))).as(c[1] + "\n" + r.output()).isNotEmpty();
            } finally {
                sql(c[0], c[2]);
            }
        }
        assertThat(verify().exit()).as("back to the shipped state").isZero();
    }
}
