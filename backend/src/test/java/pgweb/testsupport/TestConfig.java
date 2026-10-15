package pgweb.testsupport;

import pgweb.config.Config;
import pgweb.config.ConfigException;
import pgweb.config.ConfigLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Builds test-mode configurations that point at a {@link PgTestCluster}. */
public final class TestConfig {

    /** A local account SID, used as the DataAdmin group in tests: BUILTIN\Users. */
    public static final String USERS_SID = "S-1-5-32-545";

    private TestConfig() {
    }

    /** Minimal valid test-mode settings (no cluster): audit points at a closed port. */
    public static Properties base(Path tempDir) {
        Properties p = new Properties();
        p.setProperty("pgweb.mode", "test");
        p.setProperty("server.port", "0");
        p.setProperty("server.publicHost", "localhost");
        p.setProperty("auth.protocols", "Negotiate NTLM");
        p.setProperty("auth.kerberosOnly", "false");
        p.setProperty("auth.dataAdminSid", USERS_SID);
        p.setProperty("audit.jdbcUrl", "jdbc:postgresql://localhost:9/pgweb_audit");
        p.setProperty("audit.password", "plain:unused-password");
        p.setProperty("audit.hmacKey", "plain:0123456789abcdef0123456789abcdef-test-key");
        p.setProperty("audit.spoolDir", tempDir.resolve("spool").toString());
        p.setProperty("profile.external.host", "localhost");
        p.setProperty("profile.external.port", "5432");
        p.setProperty("logging.dir", tempDir.resolve("logs").toString());
        p.setProperty("server.workDir", tempDir.resolve("work").toString());
        return p;
    }

    /** Test-mode settings whose audit DB is {@code database} on {@code pg} (bootstrapped and migrated by the caller). */
    public static Properties forCluster(PgTestCluster pg, String database, Path tempDir) {
        Properties p = base(tempDir);
        p.setProperty("audit.jdbcUrl", pg.jdbcUrl(database));
        p.setProperty("audit.user", "pgweb_app");
        p.setProperty("audit.password", "plain:" + pg.appPassword());
        p.setProperty("audit.migrator.user", "pgweb_migrator");
        p.setProperty("audit.migrator.password", "plain:" + pg.migratorPassword());
        p.setProperty("profile.external.port", String.valueOf(pg.port()));
        p.setProperty("profile.internal.host", "localhost");
        p.setProperty("profile.internal.port", String.valueOf(pg.port()));
        return p;
    }

    public static Config load(Properties p) throws ConfigException {
        return ConfigLoader.load(p, null);
    }

    public static Path write(Properties p, Path file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "pgweb test configuration");
        }
        return file;
    }
}
