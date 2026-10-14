package spike.s3.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A throwaway PostgreSQL cluster created with initdb/pg_ctl from the locally
 * installed PostgreSQL binaries (ADR-0007; same approach as spike S5).
 *
 * <p>Safety: the cluster lives in a fresh temp directory, listens on 127.0.0.1
 * on a random port, and is stamped with a per-run token (marker file plus a
 * server setting). {@link #assertOwned()} refuses any cluster whose token does
 * not match, so tests can never run against a real server by mistake.
 *
 * <p>Logging: {@code log_statement=all} to a jsonlog file, so tests can see
 * exactly what the driver sent to the server.
 */
public final class EphemeralPostgres implements AutoCloseable {

    public static final String SUPERUSER = "pgweb_test_super";
    public static final String APP_USER = "s3_user";
    public static final String OTHER_USER = "s3_other";
    public static final String DATABASE = "s3";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path pgBin;
    private final Path root;
    private final Path data;
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final String superPassword = secret();
    private final String appPassword = secret();
    private final String otherPassword = secret();
    private int port;
    private boolean started;
    private String serverVersion;

    private EphemeralPostgres(Path pgBin) throws IOException {
        this.pgBin = pgBin;
        this.root = Files.createTempDirectory("pgweb-s3-");
        this.data = root.resolve("data");
    }

    public static EphemeralPostgres start(String explicitBin) throws Exception {
        EphemeralPostgres pg = new EphemeralPostgres(resolvePgBin(explicitBin));
        try {
            pg.init();
            return pg;
        } catch (Exception e) {
            pg.close();
            throw e;
        }
    }

    private void init() throws Exception {
        Files.writeString(root.resolve(".pgweb-test-cluster"), token, StandardCharsets.US_ASCII);
        Path pwFile = root.resolve("pw.txt");
        Files.writeString(pwFile, superPassword, StandardCharsets.US_ASCII);
        run(Duration.ofMinutes(3), "initdb", exe("initdb"), "-D", data.toString(), "-U", SUPERUSER,
                "-A", "scram-sha-256", "--pwfile=" + pwFile, "-E", "UTF8", "--no-locale", "--no-sync");
        Files.delete(pwFile);

        IOException last = null;
        for (int attempt = 1; attempt <= 3 && !started; attempt++) {
            port = freePort();
            String opts = "-p " + port
                    + " -c listen_addresses=127.0.0.1"
                    + " -c pgweb.test_run_token=" + token
                    + " -c fsync=off"
                    + " -c logging_collector=on"
                    + " -c log_destination=jsonlog"
                    + " -c log_directory=log"
                    + " -c log_filename=pg.log"
                    + " -c log_rotation_size=0"
                    + " -c log_statement=all";
            try {
                run(Duration.ofSeconds(90), "pgctl-start", exe("pg_ctl"), "start", "-D", data.toString(),
                        "-l", root.resolve("pgctl.log").toString(), "-w", "-t", "60", "-o", opts);
                started = true;
            } catch (IOException e) {
                last = e;
            }
        }
        if (!started) {
            throw new IOException("pg_ctl start failed", last);
        }
        assertOwned();
        try (Connection c = connect(SUPERUSER, superPassword, "postgres", "setup", new Properties());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + APP_USER + " LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB PASSWORD '" + appPassword + "'");
            s.execute("CREATE ROLE " + OTHER_USER + " LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB PASSWORD '" + otherPassword + "'");
            s.execute("CREATE DATABASE " + DATABASE + " OWNER " + APP_USER);
            try (ResultSet rs = s.executeQuery("SHOW server_version")) {
                rs.next();
                serverVersion = rs.getString(1);
            }
        }
    }

    /** Refuses to continue unless the server is the cluster this object created. */
    public void assertOwned() throws IOException, SQLException {
        String marker = Files.readString(root.resolve(".pgweb-test-cluster"), StandardCharsets.US_ASCII).trim();
        if (!marker.equals(token)) {
            throw new IllegalStateException("Safety guard: marker token mismatch; refusing to use this cluster.");
        }
        try (Connection c = connect(SUPERUSER, superPassword, "postgres", "guard", new Properties());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_setting('pgweb.test_run_token', true)")) {
            rs.next();
            if (!token.equals(rs.getString(1))) {
                throw new IllegalStateException("Safety guard: server run token mismatch; refusing to use this cluster.");
            }
        }
    }

    public String jdbcUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }

    public Connection connect(String user, String password, String database, String applicationName, Properties extra)
            throws SQLException {
        Properties p = new Properties();
        p.putAll(extra);
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("ApplicationName", applicationName);
        p.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(jdbcUrl(database), p);
    }

    /** Connection as the non-superuser application role, which is how the app will connect. */
    public Connection app(String applicationName) throws SQLException {
        return app(applicationName, new Properties());
    }

    public Connection app(String applicationName, Properties extra) throws SQLException {
        return connect(APP_USER, appPassword, DATABASE, applicationName, extra);
    }

    public Connection other(String applicationName) throws SQLException {
        return connect(OTHER_USER, otherPassword, DATABASE, applicationName, new Properties());
    }

    /** Observation connection (superuser); never used for the behaviour under test. */
    public Connection superuser(String applicationName) throws SQLException {
        return connect(SUPERUSER, superPassword, DATABASE, applicationName, new Properties());
    }

    public int port() {
        return port;
    }

    public String appPassword() {
        return appPassword;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public Path pgBin() {
        return pgBin;
    }

    /**
     * Statement log entries (from log_statement=all) for one application_name,
     * in server order. Waits until a sentinel, sent on {@code sameSession}
     * after the statements of interest, has been written, so all earlier
     * entries from that backend are guaranteed to be in the file.
     */
    public List<LoggedStatement> loggedStatements(String applicationName, Connection sameSession) throws Exception {
        String sentinel = "sentinel_" + UUID.randomUUID().toString().replace("-", "");
        try (Statement s = sameSession.createStatement()) {
            s.execute("SELECT '" + sentinel + "'");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            List<LoggedStatement> all = readLog(applicationName);
            int idx = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).text().contains(sentinel)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                return all.subList(0, idx);
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("sentinel not found in the server log for " + applicationName);
            }
            Thread.sleep(50);
        }
    }

    /** Same as {@link #loggedStatements(String, Connection)} but the sentinel is sent by the caller. */
    public List<LoggedStatement> loggedStatementsUntil(String applicationName, String sentinel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            List<LoggedStatement> all = readLog(applicationName);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).text().contains(sentinel)) {
                    return all.subList(0, i);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("sentinel not found in the server log for " + applicationName);
            }
            Thread.sleep(50);
        }
    }

    private List<LoggedStatement> readLog(String applicationName) throws IOException {
        List<LoggedStatement> out = new ArrayList<>();
        Path log = data.resolve("log").resolve("pg.json");
        if (!Files.exists(log)) {
            return out;
        }
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n;
            try {
                n = JSON.readTree(line);
            } catch (IOException partialLine) {
                continue; // the collector may be mid-write on the last line
            }
            if (!applicationName.equals(n.path("application_name").asText())) {
                continue;
            }
            String msg = n.path("message").asText();
            LoggedStatement ls = LoggedStatement.parse(msg);
            if (ls != null) {
                out.add(ls);
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (started) {
            try {
                run(Duration.ofSeconds(45), "pgctl-stop", exe("pg_ctl"), "stop", "-D", data.toString(), "-m", "fast", "-w", "-t", "30");
            } catch (Exception ignored) {
                // fall through to directory removal; leftovers are reported by isGone()
            }
        }
        for (int i = 0; i < 20 && Files.exists(root); i++) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // retried below
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
    }

    /** True when the directory is deleted and the port is free again. */
    public boolean isGone() {
        return !Files.exists(root) && portFree(port);
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------

    private Path exe(String name) {
        return pgBin.resolve(name + ".exe");
    }

    /**
     * Runs a native tool with output redirected to files. Pipes are not used:
     * postgres.exe inherits the handles, so a pipe would never close (S5 finding).
     */
    private void run(Duration timeout, String tag, Object... cmd) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        for (Object o : cmd) {
            args.add(o.toString());
        }
        Path out = root.resolve(tag + ".out");
        Path err = root.resolve(tag + ".err");
        Process p = new ProcessBuilder(args)
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException(tag + " did not finish within " + timeout);
        }
        if (p.exitValue() != 0) {
            throw new IOException(tag + " failed (exit " + p.exitValue() + "): " + Files.readString(err));
        }
    }

    static Path resolvePgBin(String explicit) {
        List<Path> candidates = new ArrayList<>();
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Paths.get(explicit));
        }
        String env = System.getenv("PGWEB_PG_BIN");
        if (env != null && !env.isBlank()) {
            candidates.add(Paths.get(env));
        }
        for (String base : new String[] {System.getenv("ProgramFiles"), "C:\\Program Files", "S:\\Program Files"}) {
            if (base == null) {
                continue;
            }
            Path pg = Paths.get(base, "PostgreSQL");
            if (Files.isDirectory(pg)) {
                try (Stream<Path> dirs = Files.list(pg)) {
                    dirs.sorted(Comparator.comparingInt(EphemeralPostgres::majorOf).reversed())
                            .forEach(d -> candidates.add(d.resolve("bin")));
                } catch (IOException ignored) {
                    // try the next base
                }
            }
        }
        for (Path c : candidates) {
            if (Files.exists(c.resolve("initdb.exe")) && Files.exists(c.resolve("pg_ctl.exe"))) {
                return c.toAbsolutePath();
            }
        }
        throw new IllegalStateException("PostgreSQL binaries not found. Set -Ds3.pgBin or PGWEB_PG_BIN.");
    }

    private static int majorOf(Path dir) {
        try {
            return Integer.parseInt(dir.getFileName().toString().replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static boolean portFree(int port) {
        if (port <= 0) {
            return true;
        }
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String secret() {
        String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
