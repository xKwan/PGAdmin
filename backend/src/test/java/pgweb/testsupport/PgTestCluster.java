package pgweb.testsupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A throwaway PostgreSQL 18 cluster for integration tests (ADR-0007, plan §7.4), created with the installed
 * {@code initdb}/{@code pg_ctl}: temp directory {@code %TEMP%\pgweb-it-*}, random port, localhost only, generated
 * superuser password. Stopped and deleted by {@link #close()}, and by a JVM shutdown hook if a test run dies.
 *
 * <p><b>Safety guard:</b> the cluster carries a per-run token in a marker file and in a server setting.
 * {@link #assertOwned()} runs before every superuser connection and refuses a server whose token doesn't match,
 * so tests can't touch a real server by mistake.
 */
public final class PgTestCluster implements AutoCloseable {

    public static final String SUPERUSER = "pgweb_test_super";
    private static final String MARKER = ".pgweb-test-cluster";

    private final Path pgBin;
    private final Path root;
    private final Path data;
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final String superPassword = secret();
    private final String migratorPassword = secret();
    private final String appPassword = secret();
    private final List<String> hbaExtra;
    private final Thread hook;
    private int port;
    private boolean started;
    private boolean auditBootstrapped;

    private PgTestCluster(Path pgBin, List<String> hbaExtra) throws IOException {
        this.pgBin = pgBin;
        this.hbaExtra = hbaExtra;
        this.root = Files.createTempDirectory("pgweb-it-");
        this.data = root.resolve("data");
        this.hook = new Thread(this::close, "pgweb-it-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    public static PgTestCluster start() throws Exception {
        return start(List.of());
    }

    /** @param hbaExtra extra {@code pg_hba.conf} lines, inserted after the superuser line (e.g. sspi rules) */
    public static PgTestCluster start(List<String> hbaExtra) throws Exception {
        PgTestCluster c = new PgTestCluster(resolvePgBin(), hbaExtra);
        try {
            c.init();
            return c;
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    private void init() throws Exception {
        Files.writeString(root.resolve(MARKER), token, StandardCharsets.US_ASCII);
        Path pw = root.resolve("pw.txt");
        Files.writeString(pw, superPassword, StandardCharsets.US_ASCII);
        run(Duration.ofMinutes(3), "initdb", Map.of(), exe("initdb"), "-D", data, "-U", SUPERUSER, "-A", "scram-sha-256",
                "--pwfile=" + pw, "-E", "UTF8", "--no-locale", "--no-sync");
        Files.delete(pw);
        StringBuilder hba = new StringBuilder();
        hba.append("host all ").append(SUPERUSER).append(" 127.0.0.1/32 scram-sha-256\n");
        hba.append("host all ").append(SUPERUSER).append(" ::1/128 scram-sha-256\n");
        for (String line : hbaExtra) {
            hba.append(line).append('\n');
        }
        hba.append("host all all 127.0.0.1/32 scram-sha-256\n");
        hba.append("host all all ::1/128 scram-sha-256\n");
        Files.writeString(data.resolve("pg_hba.conf"), hba.toString(), StandardCharsets.US_ASCII);

        IOException last = null;
        for (int attempt = 1; attempt <= 3 && !started; attempt++) {
            port = freePort();
            String opts = "-p " + port + " -c listen_addresses=127.0.0.1,::1 -c pgweb.test_run_token=" + token
                    + " -c fsync=off -c synchronous_commit=off -c full_page_writes=off -c max_connections=200"
                    + " -c logging_collector=on -c log_directory=log -c log_filename=pg.log -c log_connections=on";
            try {
                run(Duration.ofSeconds(90), "pgctl-start", Map.of(), exe("pg_ctl"), "start", "-D", data,
                        "-l", root.resolve("pgctl.log"), "-w", "-t", "60", "-o", opts);
                started = true;
            } catch (IOException e) {
                last = e;
            }
        }
        if (!started) {
            throw new IOException("pg_ctl start failed", last);
        }
        assertOwned();
    }

    /** Refuses to continue unless the server is the cluster this object created (plan §7.4 safety guard). */
    public void assertOwned() throws IOException, SQLException {
        String marker = Files.readString(root.resolve(MARKER), StandardCharsets.US_ASCII).trim();
        if (!marker.equals(token)) {
            throw new IllegalStateException("Safety guard: marker token mismatch; refusing to use this cluster.");
        }
        try (Connection c = rawConnect(SUPERUSER, superPassword, "postgres");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_setting('pgweb.test_run_token', true)")) {
            rs.next();
            if (!token.equals(rs.getString(1))) {
                throw new IllegalStateException("Safety guard: server run token mismatch; refusing to use this cluster.");
            }
        }
    }

    /** Runs the shipped deploy/sql/audit-bootstrap.sql through psql, exactly as a DBA would. */
    public synchronized void bootstrapAudit() throws Exception {
        if (auditBootstrapped) {
            return;
        }
        assertOwned();
        Path script = repoRoot().resolve("deploy").resolve("sql").resolve("audit-bootstrap.sql");
        run(Duration.ofMinutes(1), "psql-bootstrap", Map.of("PGPASSWORD", superPassword), exe("psql"),
                "-X", "-q", "-h", "localhost", "-p", port, "-U", SUPERUSER, "-d", "postgres",
                "-v", "migrator_password=" + migratorPassword, "-v", "app_password=" + appPassword, "-f", script);
        auditBootstrapped = true;
    }

    /** Creates another audit database (same roles) for tests that damage the chain on purpose. */
    public String createAuditDatabase(String name) throws Exception {
        bootstrapAudit();
        try (Connection c = superuser("postgres"); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name + " OWNER pgweb_migrator TEMPLATE template0");
            s.execute("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC");
            s.execute("GRANT CONNECT ON DATABASE " + name + " TO pgweb_app, pgweb_audit_reader, pgweb_migrator");
        }
        try (Connection c = superuser(name); Statement s = c.createStatement()) {
            s.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        }
        return name;
    }

    public Connection superuser(String database) throws Exception {
        assertOwned();
        return rawConnect(SUPERUSER, superPassword, database);
    }

    public Connection connect(String user, String password, String database) throws SQLException {
        return rawConnect(user, password, database);
    }

    private Connection rawConnect(String user, String password, String database) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("connectTimeout", "10");
        p.setProperty("ApplicationName", "pgweb-it");
        return DriverManager.getConnection(jdbcUrl(database), p);
    }

    public String jdbcUrl(String database) {
        return "jdbc:postgresql://localhost:" + port + "/" + database;
    }

    public int port() {
        return port;
    }

    public String migratorPassword() {
        return migratorPassword;
    }

    public String appPassword() {
        return appPassword;
    }

    public String superPassword() {
        return superPassword;
    }

    public Path root() {
        return root;
    }

    public Path pgBin() {
        return pgBin;
    }

    /** Stops the server without deleting anything (for "database down" tests); {@link #restart()} brings it back. */
    public void stop() throws Exception {
        run(Duration.ofSeconds(60), "pgctl-stop", Map.of(), exe("pg_ctl"), "stop", "-D", data, "-m", "fast", "-w", "-t", "45");
    }

    public void restart() throws Exception {
        String opts = "-p " + port + " -c listen_addresses=127.0.0.1,::1 -c pgweb.test_run_token=" + token
                + " -c fsync=off -c synchronous_commit=off -c full_page_writes=off -c max_connections=200";
        run(Duration.ofSeconds(90), "pgctl-restart", Map.of(), exe("pg_ctl"), "start", "-D", data,
                "-l", root.resolve("pgctl.log"), "-w", "-t", "60", "-o", opts);
        assertOwned();
    }

    /** For the guard test only: start the server without this run's token. */
    void restartWithoutTokenForTest() throws Exception {
        run(Duration.ofSeconds(90), "pgctl-restart-foreign", Map.of(), exe("pg_ctl"), "start", "-D", data,
                "-l", root.resolve("pgctl.log"), "-w", "-t", "60", "-o", "-p " + port + " -c listen_addresses=127.0.0.1,::1");
    }

    /** For the guard test only: overwrite the marker so the guard must refuse. */
    void corruptMarkerForTest() throws IOException {
        Files.writeString(root.resolve(MARKER), "not-the-token", StandardCharsets.US_ASCII);
    }

    @Override
    public synchronized void close() {
        if (started) {
            started = false;
            try {
                run(Duration.ofSeconds(60), "pgctl-stop-final", Map.of(), exe("pg_ctl"), "stop", "-D", data, "-m", "immediate", "-w", "-t", "45");
            } catch (Exception ignored) {
                // the directory removal below still runs; leftovers are reported by the test loop
            }
        }
        for (int i = 0; i < 40 && Files.exists(root); i++) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // retried
            }
            if (Files.exists(root)) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // already shutting down
        }
    }

    // ------------------------------------------------------------------

    private Path exe(String name) {
        return pgBin.resolve(name + ".exe");
    }

    /** Output to files, never pipes: postgres.exe inherits the handles (S5 finding). */
    private void run(Duration timeout, String tag, Map<String, String> env, Object... cmd) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        for (Object o : cmd) {
            args.add(o.toString());
        }
        Path out = root.resolve(tag + ".out");
        Path err = root.resolve(tag + ".err");
        ProcessBuilder pb = new ProcessBuilder(args).redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().putAll(env);
        Process p = pb.start();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException(tag + " did not finish within " + timeout);
        }
        if (p.exitValue() != 0) {
            throw new IOException(tag + " failed (exit " + p.exitValue() + "): " + Files.readString(err) + Files.readString(out));
        }
    }

    static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("deploy").resolve("sql"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repository root (with deploy/sql) not found above " + Paths.get("").toAbsolutePath());
        }
        return p;
    }

    static Path resolvePgBin() {
        List<Path> candidates = new ArrayList<>();
        String prop = System.getProperty("pgweb.test.pgBin");
        if (prop != null && !prop.isBlank()) {
            candidates.add(Paths.get(prop));
        }
        String env = System.getenv("PGWEB_PG_BIN");
        if (env != null && !env.isBlank()) {
            candidates.add(Paths.get(env));
        }
        for (String base : new String[] {System.getenv("ProgramFiles"), "C:\\Program Files", "S:\\Program Files"}) {
            if (base != null) {
                candidates.add(Paths.get(base, "PostgreSQL", "18", "bin"));
            }
        }
        for (Path c : candidates) {
            if (Files.exists(c.resolve("initdb.exe")) && Files.exists(c.resolve("pg_ctl.exe")) && Files.exists(c.resolve("psql.exe"))) {
                return c.toAbsolutePath();
            }
        }
        throw new IllegalStateException("PostgreSQL 18 binaries not found; set -Dpgweb.test.pgBin or PGWEB_PG_BIN");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static String secret() {
        String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder("Pw");
        for (int i = 0; i < 24; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
