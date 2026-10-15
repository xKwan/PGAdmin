package pgweb;

import pgweb.audit.AuditMigrator;
import pgweb.audit.ChainVerifier;
import pgweb.config.Config;
import pgweb.config.ConfigException;
import pgweb.config.ConfigLoader;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line entry point.
 * <pre>
 *   java -jar pgweb.jar [serve]            --config C:\ProgramData\pgweb\config\pgweb.properties
 *   java -jar pgweb.jar check-config       --config ...
 *   java -jar pgweb.jar migrate            --config ...   (audit schema, as the migrator role)
 *   java -jar pgweb.jar verify-audit-chain --config ...   (as the migrator role)
 *   java -jar pgweb.jar version
 * </pre>
 * The config path can also come from {@code PGWEB_CONFIG}. Exit codes: 0 success, 1 failure, 2 invalid
 * configuration or usage, 3 audit chain broken.
 */
public final class Main {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int BAD_CONFIG = 2;
    public static final int CHAIN_BROKEN = 3;

    /** Tomcat logs through java.util.logging; keep it to warnings (held here so the setting isn't garbage-collected). */
    private static final java.util.logging.Logger TOMCAT_JUL = java.util.logging.Logger.getLogger("org.apache");

    private Main() {
    }

    public static void main(String[] args) {
        int rc = run(args, System.out, System.err, System.getenv("PGWEB_CONFIG"));
        if (rc != OK) {
            System.exit(rc);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, String envConfig) {
        String command = "serve";
        String configPath = envConfig;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--config") && i + 1 < args.length) {
                configPath = args[++i];
            } else if (a.startsWith("--config=")) {
                configPath = a.substring("--config=".length());
            } else if (!a.startsWith("-") && i == 0) {
                command = a;
            } else {
                err.println("unknown argument: " + a);
                return BAD_CONFIG;
            }
        }
        if (command.equals("version")) {
            out.println(BuildInfo.describe());
            return OK;
        }
        if (!command.matches("serve|check-config|migrate|verify-audit-chain")) {
            err.println("unknown command: " + command + " (serve, check-config, migrate, verify-audit-chain, version)");
            return BAD_CONFIG;
        }
        if (configPath == null || configPath.isBlank()) {
            err.println("no configuration: pass --config <file> or set PGWEB_CONFIG");
            return BAD_CONFIG;
        }
        Path file = Path.of(configPath);
        if (!Files.isRegularFile(file)) {
            err.println("configuration file not found: " + file);
            return BAD_CONFIG;
        }
        Config config;
        try {
            config = ConfigLoader.load(file);
        } catch (ConfigException e) {
            err.println(e.getMessage());
            return BAD_CONFIG;
        }
        System.setProperty("pgweb.logDir", config.logDir().toAbsolutePath().toString());
        TOMCAT_JUL.setLevel(java.util.logging.Level.WARNING);

        return switch (command) {
            case "check-config" -> {
                out.println("configuration OK (mode " + config.mode().name().toLowerCase(java.util.Locale.ROOT) + ", profiles " + config.profiles().keySet() + ")");
                yield OK;
            }
            case "migrate" -> migrate(config, out, err);
            case "verify-audit-chain" -> verify(config, out, err);
            default -> serve(config, err);
        };
    }

    private static int migrate(Config config, PrintStream out, PrintStream err) {
        Config.Audit a = config.audit();
        String user = a.migratorUser().orElse(null);
        pgweb.config.Secret password = a.migratorPassword().orElse(null);
        if (user == null || password == null) {
            err.println("migrate needs audit.migrator.user and audit.migrator.password");
            return BAD_CONFIG;
        }
        try {
            var result = AuditMigrator.migrate(a.jdbcUrl(), user, password.reveal(), a.user());
            out.println("audit schema at version " + result.targetSchemaVersion + " (" + result.migrationsExecuted + " migrations applied)");
            return OK;
        } catch (RuntimeException e) {
            err.println("migration failed: " + pgweb.logging.Redactor.redact(String.valueOf(e.getMessage())));
            return FAILED;
        }
    }

    private static int verify(Config config, PrintStream out, PrintStream err) {
        Config.Audit a = config.audit();
        String user = a.migratorUser().orElse(null);
        pgweb.config.Secret password = a.migratorPassword().orElse(null);
        if (user == null || password == null) {
            err.println("verify-audit-chain needs audit.migrator.user and audit.migrator.password (or a reader account)");
            return BAD_CONFIG;
        }
        try (Connection c = DriverManager.getConnection(a.jdbcUrl(), user, password.reveal())) {
            ChainVerifier.Result r = ChainVerifier.verify(c);
            if (r.intact()) {
                out.println("audit chain intact: " + r.rows() + " records");
                return OK;
            }
            err.println("AUDIT CHAIN BROKEN at seq " + r.firstBadSeq() + ": " + r.problem());
            return CHAIN_BROKEN;
        } catch (java.sql.SQLException e) {
            err.println("verification failed: " + pgweb.logging.Redactor.redact(String.valueOf(e.getMessage())));
            return FAILED;
        }
    }

    private static int serve(Config config, PrintStream err) {
        CountDownLatch stopped = new CountDownLatch(1);
        Application app;
        try {
            app = Application.start(config);
        } catch (Exception e) {
            err.println("startup failed: " + pgweb.logging.Redactor.redact(String.valueOf(e.getMessage())));
            return FAILED;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.close();
            stopped.countDown();
        }, "pgweb-shutdown"));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return OK;
    }
}
