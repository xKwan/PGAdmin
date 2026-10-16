package pgweb.execution;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestApps;
import pgweb.testsupport.TestConfig;
import pgweb.testsupport.TestServerMain;
import pgweb.web.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Plan section 8.1, "app-process kill": pgweb runs in its own process, a user has an uncommitted change and a row
 * lock open, and the process is killed without any shutdown. PostgreSQL must roll back and release the lock.
 */
class ProcessKillIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static Process server;

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        String pw = "Pw" + UUID.randomUUID();
        try (Connection c = apps.pg().superuser("postgres"); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE kim LOGIN PASSWORD '" + pw + "'");
            s.execute("CREATE DATABASE kill_db OWNER kim");
        }
        try (Connection c = apps.pg().superuser("kill_db"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE public.t (id int PRIMARY KEY, v text)");
            s.execute("INSERT INTO public.t SELECT g, 'v' FROM generate_series(1, 20) g");
            s.execute("ALTER TABLE public.t OWNER TO kim");
        }
        Path dir = Files.createDirectories(tmp.resolve("child"));
        Properties cfg = TestConfig.forCluster(apps.pg(), "pgweb_audit", dir);
        cfg.setProperty("execution.enabled", "true");
        Path cfgFile = TestConfig.write(cfg, dir.resolve("app.properties"));
        Properties roles = new Properties();
        roles.setProperty("kim", pw);
        Path rolesFile = TestConfig.write(roles, dir.resolve("roles.properties"));
        server = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), TestServerMain.class.getName(), cfgFile.toString(), rolesFile.toString())
                .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(Files.createFile(dir.resolve("stdin")).toFile()))
                .start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.destroyForcibly();
        }
        if (apps != null) {
            apps.close();
        }
    }

    static int readyPort() throws Exception {
        BufferedReader out = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        CompletableFuture<Integer> port = CompletableFuture.supplyAsync(() -> {
            try {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    if (line.startsWith("READY ")) {
                        // keep draining so the child never blocks on a full pipe
                        Thread drain = new Thread(() -> out.lines().forEach(l -> { }), "child-output");
                        drain.setDaemon(true);
                        drain.start();
                        return Integer.parseInt(line.substring(6).trim());
                    }
                }
                throw new IllegalStateException("child exited before READY");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return port.get(90, TimeUnit.SECONDS);
    }

    static int count(String sql) throws Exception {
        try (Connection c = apps.pg().superuser("kill_db"); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int backendsOf(String role) throws Exception {
        try (Connection c = apps.pg().superuser("postgres");
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE usename = ?")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void killingTheAppRollsBackOpenWorkAndReleasesLocks() throws Exception {
        int port = readyPort();
        MockClient c = MockClient.user(port, "TEST\\kim", "S-1-5-21-8-8-8-1");
        c.me();
        HttpResponse<String> open = c.post("/api/db-sessions/external", "{\"database\":\"kill_db\"}");
        assertThat(open.statusCode()).as(open.body()).isEqualTo(201);
        String id = Json.mapper().readTree(open.body()).path("id").asText();
        int row = 1 + Seeds.random("kill").nextInt(20);
        for (String sql : new String[] {"BEGIN", "INSERT INTO t VALUES (100, 'uncommitted')", "UPDATE t SET v = 'changed' WHERE id = " + row}) {
            HttpResponse<String> r = c.post("/api/db-sessions/" + id + "/execute", Json.write(Map.of("sql", sql)));
            JsonNode doc = Json.mapper().readTree(r.body());
            assertThat(doc.path("transaction").asText()).as(sql).isEqualTo("OPEN");
        }
        assertThat(backendsOf("kim")).isEqualTo(2); // main + companion

        server.destroyForcibly(); // no shutdown hook, no rollback by the app
        assertThat(server.waitFor(30, TimeUnit.SECONDS)).isTrue();

        // The OS closes the sockets; PostgreSQL ends both backends and rolls back.
        await().atMost(Duration.ofSeconds(30)).until(() -> backendsOf("kim") == 0);
        assertThat(count("SELECT count(*) FROM t WHERE id = 100")).isZero();
        assertThat(count("SELECT count(*) FROM t WHERE v = 'changed'")).isZero();
        try (Connection s = apps.pg().superuser("kill_db"); Statement st = s.createStatement()) {
            st.execute("SET lock_timeout = '2s'");
            assertThat(st.executeUpdate("UPDATE t SET v = 'free' WHERE id = " + row)).as("the row lock was released").isEqualTo(1);
        }
    }
}
