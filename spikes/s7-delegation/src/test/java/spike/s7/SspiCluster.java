package spike.s7;

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
 * Throwaway PostgreSQL cluster (ADR-0007) whose {@code pg_hba.conf} authenticates everyone except the test superuser
 * with Windows SSPI, and maps {@code user@REALM} to a role through {@code pg_ident.conf}. This is the server-side
 * shape ADR-0002 requires, on one machine.
 */
final class SspiCluster implements AutoCloseable {

    static final String SUPERUSER = "pgweb_test_super";
    static final String DATABASE = "s7";

    private final Path pgBin;
    private final Path root;
    private final Path data;
    private final String superPassword = secret();
    private int port;
    private boolean started;

    private SspiCluster(Path pgBin) throws IOException {
        this.pgBin = pgBin;
        this.root = Files.createTempDirectory("pgweb-s7-");
        this.data = root.resolve("data");
    }

    /** Creates the cluster; {@code roleForWindowsUser} is the role created for the account running the test. */
    static SspiCluster start(String roleForWindowsUser) throws Exception {
        SspiCluster c = new SspiCluster(resolvePgBin());
        try {
            c.init(roleForWindowsUser);
            return c;
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    private void init(String role) throws Exception {
        Path pwFile = root.resolve("pw.txt");
        Files.writeString(pwFile, superPassword, StandardCharsets.US_ASCII);
        run(Duration.ofMinutes(3), "initdb", exe("initdb"), "-D", data.toString(), "-U", SUPERUSER,
                "-A", "scram-sha-256", "--pwfile=" + pwFile, "-E", "UTF8", "--no-locale", "--no-sync");
        Files.delete(pwFile);
        // Superuser by password (test setup only); everyone else by SSPI with a realm-stripping, lowercasing map.
        Files.writeString(data.resolve("pg_hba.conf"),
                "host all " + SUPERUSER + " 127.0.0.1/32 scram-sha-256\n"
                        + "host all all 127.0.0.1/32 sspi include_realm=1 map=win\n",
                StandardCharsets.US_ASCII);
        // pg_ident matches case-sensitively; the role is created in lowercase, so map any case of the account name.
        Files.writeString(data.resolve("pg_ident.conf"),
                "win \"/(?i)^" + role + "@.*$\" " + role + "\n",
                StandardCharsets.US_ASCII);

        for (int attempt = 1; attempt <= 3 && !started; attempt++) {
            port = freePort();
            String opts = "-p " + port + " -c listen_addresses=127.0.0.1,::1 -c fsync=off -c log_connections=on"
                    + " -c logging_collector=on -c log_directory=log -c log_filename=pg.log";
            try {
                run(Duration.ofSeconds(90), "pgctl-start", exe("pg_ctl"), "start", "-D", data.toString(),
                        "-l", root.resolve("pgctl.log").toString(), "-w", "-t", "60", "-o", opts);
                started = true;
            } catch (IOException e) {
                if (attempt == 3) {
                    throw e;
                }
            }
        }
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE \"" + role + "\" LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            s.execute("CREATE DATABASE " + DATABASE + " OWNER \"" + role + "\"");
        }
    }

    Connection superuser() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", SUPERUSER);
        p.setProperty("password", superPassword);
        return DriverManager.getConnection(url("postgres"), p);
    }

    /** SSPI logon as whatever identity the calling thread currently has; no password is given. */
    Connection sspi(String role, String applicationName) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", role);
        p.setProperty("gsslib", "sspi");
        p.setProperty("ApplicationName", applicationName);
        p.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(url(DATABASE), p);
    }

    String url(String db) {
        return "jdbc:postgresql://localhost:" + port + "/" + db;
    }

    String serverLog() throws IOException {
        Path log = data.resolve("log").resolve("pg.log");
        return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    boolean isGone() {
        if (Files.exists(root)) {
            return false;
        }
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (started) {
            try {
                run(Duration.ofSeconds(45), "pgctl-stop", exe("pg_ctl"), "stop", "-D", data.toString(), "-m", "fast", "-w", "-t", "30");
            } catch (Exception ignored) {
                // directory removal below; leftovers are reported by isGone()
            }
        }
        for (int i = 0; i < 20 && Files.exists(root); i++) {
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
    }

    private Path exe(String name) {
        return pgBin.resolve(name + ".exe");
    }

    /** Output goes to files, never pipes: postgres.exe inherits the handles (S5 finding). */
    private void run(Duration timeout, String tag, Object... cmd) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        for (Object o : cmd) {
            args.add(o.toString());
        }
        Process p = new ProcessBuilder(args)
                .redirectOutput(root.resolve(tag + ".out").toFile())
                .redirectError(root.resolve(tag + ".err").toFile())
                .start();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException(tag + " did not finish within " + timeout);
        }
        if (p.exitValue() != 0) {
            throw new IOException(tag + " failed (exit " + p.exitValue() + "): " + Files.readString(root.resolve(tag + ".err")));
        }
    }

    private static Path resolvePgBin() {
        List<Path> candidates = new ArrayList<>();
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
            if (Files.exists(c.resolve("initdb.exe"))) {
                return c;
            }
        }
        throw new IllegalStateException("PostgreSQL 18 binaries not found; set PGWEB_PG_BIN");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static String secret() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b) + UUID.randomUUID().toString().substring(0, 4);
    }
}
