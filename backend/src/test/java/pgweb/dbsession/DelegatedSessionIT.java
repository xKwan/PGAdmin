package pgweb.dbsession;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Sspi;
import com.sun.jna.platform.win32.SspiUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.audit.AuditMigrator;
import pgweb.config.Config;
import pgweb.testsupport.NegotiateClient;
import pgweb.testsupport.PgTestCluster;
import pgweb.testsupport.RawHttp;
import pgweb.testsupport.TestConfig;
import pgweb.web.Json;
import waffle.windows.auth.IWindowsSecurityContext;
import waffle.windows.auth.impl.WindowsSecurityContextImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production connection path end to end on this machine (ADR-0002, S7): real Waffle sign-in, a fresh Windows
 * handshake on the request that opens the session, impersonation, and a pgJDBC SSPI logon to a cluster whose
 * pg_hba.conf authenticates this account with {@code sspi}. NTLM loopback with one account: Kerberos and delegation
 * to another host remain target-environment items (checklist K1-K4).
 */
class DelegatedSessionIT {

    @TempDir
    static Path tmp;
    static PgTestCluster pg;
    static Application app;
    static String role;

    @BeforeAll
    static void start() throws Exception {
        role = Advapi32Util.getUserName().toLowerCase(Locale.ROOT);
        String rx = "\"/(?i)^" + role + "@.*$\"";
        pg = PgTestCluster.start(
                List.of("host all " + role + " 127.0.0.1/32 sspi include_realm=1 map=win", "host all " + role + " ::1/128 sspi include_realm=1 map=win"),
                List.of("win " + rx + " " + role));
        pg.bootstrapAudit();
        AuditMigrator.migrate(pg.jdbcUrl("pgweb_audit"), "pgweb_migrator", pg.migratorPassword(), "pgweb_app");
        try (Connection c = pg.superuser("postgres"); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE \"" + role + "\" LOGIN NOSUPERUSER");
            s.execute("CREATE DATABASE delegated_db OWNER \"" + role + "\"");
        }
        Properties p = TestConfig.forCluster(pg, "pgweb_audit", Files.createDirectories(tmp.resolve("app")));
        Config cfg = TestConfig.load(p);
        app = Application.start(cfg); // production connector and fresh-handshake filter; real Waffle
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (pg != null) {
            pg.close();
        }
    }

    /** Signs in on /api/me and returns cookie + CSRF token. */
    static Map<String, String> signIn() throws Exception {
        try (RawHttp http = new RawHttp(app.port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, "/api/me", "Negotiate", Map.of());
            assertThat(r.response().status()).isEqualTo(200);
            String cookie = r.response().header("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow().split(";", 2)[0];
            JsonNode me = Json.mapper().readTree(r.response().body());
            return Map.of("Cookie", cookie, "X-CSRF-Token", me.path("csrfToken").asText(), "Origin", "http://localhost:" + app.port(),
                    "Content-Type", "application/json");
        }
    }

    /** POST with a fresh Negotiate handshake on one connection, as the browser does after a 401 challenge. */
    static RawHttp.Response freshPost(String path, Map<String, String> session, String body) throws Exception {
        try (RawHttp http = new RawHttp(app.port())) {
            RawHttp.Response first = http.send("POST", path, session, body);
            assertThat(first.status()).as(first.body()).isEqualTo(401);
            assertThat(first.body()).contains("DELEGATION_HANDSHAKE");
            IWindowsSecurityContext ctx = WindowsSecurityContextImpl.getCurrent("Negotiate", "localhost");
            try {
                byte[] token = ctx.getToken();
                for (int leg = 0; leg < 5; leg++) {
                    Map<String, String> h = new HashMap<>(session);
                    h.put("Authorization", "Negotiate " + Base64.getEncoder().encodeToString(token));
                    RawHttp.Response r = http.send("POST", path, h, body);
                    byte[] server = NegotiateClient.serverToken(r, "Negotiate");
                    if (r.status() != 401 || server == null || !ctx.isContinue()) {
                        return r;
                    }
                    ctx.initialize(ctx.getHandle(), new SspiUtil.ManagedSecBufferDesc(Sspi.SECBUFFER_TOKEN, server), "localhost");
                    token = ctx.getToken();
                }
                throw new IllegalStateException("handshake did not finish");
            } finally {
                ctx.dispose();
            }
        }
    }

    static List<String> backends() throws Exception {
        try (Connection c = pg.superuser("postgres");
             PreparedStatement ps = c.prepareStatement("SELECT application_name FROM pg_stat_activity WHERE usename = ?")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    @Test
    void databasesAreListedAndASessionOpensAsTheUserWithoutAPassword() throws Exception {
        Map<String, String> session = signIn();
        RawHttp.Response list = freshPost("/api/profiles/external/databases", session, "{}");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).contains("delegated_db");

        RawHttp.Response open = freshPost("/api/db-sessions/external", session, "{\"database\":\"delegated_db\"}");
        assertThat(open.status()).as(open.body()).isEqualTo(201);
        JsonNode s = Json.mapper().readTree(open.body());
        assertThat(s.path("role").asText()).isEqualTo(role);
        assertThat(s.path("companion").asBoolean()).isTrue();
        assertThat(backends()).contains("pgweb", "pgweb-companion");
        // The server authenticated these backends with sspi, not a password.
        String log = Files.readString(pg.root().resolve("data").resolve("log").resolve("pg.log"));
        assertThat(log).contains("method=sspi");

        try (RawHttp http = new RawHttp(app.port())) {
            RawHttp.Response del = http.send("DELETE", "/api/db-sessions/" + s.path("id").asText(), session, null);
            assertThat(del.status()).isEqualTo(204);
        }
    }

    @Test
    void openingWithoutAHandshakeIsChallengedNotPerformed() throws Exception {
        Map<String, String> session = signIn();
        try (RawHttp http = new RawHttp(app.port())) {
            RawHttp.Response r = http.send("POST", "/api/db-sessions/external", session, "{\"database\":\"delegated_db\"}");
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.header("WWW-Authenticate")).contains("Negotiate");
            RawHttp.Response bad = http.send("POST", "/api/db-sessions/external", withAuth(session, "Negotiate !!!"), "{\"database\":\"delegated_db\"}");
            assertThat(bad.status()).isEqualTo(401);
        }
        assertThat(backends()).doesNotContain("pgweb");
    }

    static Map<String, String> withAuth(Map<String, String> s, String auth) {
        Map<String, String> h = new HashMap<>(s);
        h.put("Authorization", auth);
        return h;
    }
}
