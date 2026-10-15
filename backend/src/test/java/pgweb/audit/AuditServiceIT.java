package pgweb.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.Application;
import pgweb.authz.Access;
import pgweb.security.UserContext;
import pgweb.server.WebServer;
import pgweb.testsupport.MockAuthFilter;
import pgweb.testsupport.MockClient;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestApps;
import pgweb.web.ApiServlet;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit service against a real audit DB (plan Phase 4): fail-closed refusal, fail-open spooling, best-effort
 * spooling during an outage, replay, and the chain staying intact throughout. Uses a test-only audited endpoint.
 */
class AuditServiceIT {

    @TempDir
    static Path tmp;
    static TestApps apps;
    static Application closed;
    static Application open;
    static final AtomicInteger PERFORMED = new AtomicInteger();

    /** A test-only endpoint whose operation requires an audit record first (as SQL execution will). */
    static Consumer<WebServer.Routes> audited(AuditServiceHolder h) {
        return routes -> routes.servlet("t_audited", new ApiServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected Object post(HttpServletRequest req) {
                UserContext u = UserContext.of(req);
                h.service.require(AuditEvent.of("exec_intent").user(u).request(req).statement("select", "SELECT $1", h.service.sqlHmac("SELECT 1"), 8, "ok"));
                PERFORMED.incrementAndGet();
                return Map.of("done", true);
            }
        }, Access.AUTHENTICATED, "/api/test/audited");
    }

    /** The servlet is built before the application, so it reaches the service through this holder. */
    static final class AuditServiceHolder {
        AuditService service;
    }

    static final AuditServiceHolder CLOSED_HOLDER = new AuditServiceHolder();
    static final AuditServiceHolder OPEN_HOLDER = new AuditServiceHolder();

    @BeforeAll
    static void start() throws Exception {
        apps = TestApps.start(tmp);
        closed = apps.app(p -> { }, new Application.Options(new MockAuthFilter(), audited(CLOSED_HOLDER), Clock.systemUTC()));
        CLOSED_HOLDER.service = closed.audit();
        open = apps.app(p -> p.setProperty("audit.failMode", "open"), new Application.Options(new MockAuthFilter(), audited(OPEN_HOLDER), Clock.systemUTC()));
        OPEN_HOLDER.service = open.audit();
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.close();
        }
    }

    static MockClient user(Application app, Random r) throws Exception {
        int id = 1000 + r.nextInt(9000);
        MockClient c = MockClient.user(app.port(), "TEST\\aud" + id, "S-1-5-21-6-6-6-" + id);
        c.me();
        return c;
    }

    static boolean chainIntact() throws Exception {
        try (Connection c = apps.pg().connect("pgweb_migrator", apps.pg().migratorPassword(), "pgweb_audit")) {
            return ChainVerifier.verify(c).intact();
        }
    }

    @Test
    void outageFailsClosedSpoolsBestEffortRecordsAndReplays() throws Exception {
        Random r = Seeds.random("outage");
        MockClient c = user(closed, r);
        MockClient o = user(open, r);
        String csid = c.me().path("user").path("sid").asText();

        assertThat(c.post("/api/test/audited", "{}").statusCode()).isEqualTo(200);
        assertThat(apps.events("exec_intent", csid)).hasSize(1);

        int performed = PERFORMED.get();
        long spooledBefore = closed.audit().spooled();
        apps.auditDown(true);
        try {
            // Fail-closed: the operation is refused and never performed.
            HttpResponse<String> refused = c.post("/api/test/audited", "{}");
            assertThat(refused.statusCode()).isEqualTo(503);
            assertThat(refused.body()).contains("AUDIT_UNAVAILABLE");
            assertThat(PERFORMED.get()).isEqualTo(performed);

            // Best-effort records (a new sign-in) go to the spool; the user is not blocked.
            int extra = 1 + r.nextInt(3);
            for (int i = 0; i < extra; i++) {
                user(closed, r);
            }
            assertThat(closed.audit().spooled()).isEqualTo(spooledBefore + extra);

            // Fail-open: the operation proceeds and its intent is spooled.
            long openSpooled = open.audit().spooled();
            assertThat(o.post("/api/test/audited", "{}").statusCode()).isEqualTo(200);
            assertThat(open.audit().spooled()).isEqualTo(openSpooled + 1);

            // Replaying while the DB is still down keeps everything in the spool.
            assertThat(closed.audit().replay()).isZero();
            assertThat(closed.audit().spooled()).isEqualTo(spooledBefore + extra);
        } finally {
            apps.auditDown(false);
        }
        // A connection attempt already in flight during the outage can still fail once afterwards, and the service's
        // own background replay may get there first: retry until both spools are empty, as the service does.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60)).pollInterval(java.time.Duration.ofSeconds(1)).until(() -> {
            closed.audit().replay();
            open.audit().replay();
            return closed.audit().spooled() == 0 && open.audit().spooled() == 0;
        });
        assertThat(apps.events("audit_spool_replayed", null)).isNotEmpty();
        assertThat(apps.events("app_login", null).size()).isGreaterThanOrEqualTo(2);
        assertThat(apps.events("exec_intent", o.me().path("user").path("sid").asText())).hasSize(1);
        assertThat(chainIntact()).isTrue();
        assertThat(c.post("/api/test/audited", "{}").statusCode()).isEqualTo(200);
    }

    @Test
    void everyFieldSurvivesTheSpoolAndReplay() throws Exception {
        Random r = Seeds.random("full-event");
        java.util.UUID exec = java.util.UUID.randomUUID();
        java.util.UUID intent = java.util.UUID.randomUUID();
        java.time.Instant start = java.time.Instant.parse("2026-10-16T02:00:00.123456Z");
        String sid = "S-1-5-21-4-4-4-" + (1000 + r.nextInt(9000));
        long rows = r.nextInt(1_000_000);
        AuditEvent e = AuditEvent.of("exec_outcome").user("TEST\\full", sid, true).session("s".repeat(32)).client("10.0.0.5")
                .target("external", "sales", "full", 4242, "db-session-1").execution(exec, intent)
                .statement("dml", "UPDATE t SET a = $1", "a".repeat(64), 33, "ok")
                .outcome("success", "00000", rows, false, 12L).cancel("none").transaction("IDLE", "INTRANS")
                .times(start, start.plusMillis(12)).detail("note", "é ' \" quote").detail("n", 7);
        apps.auditDown(true);
        try {
            closed.audit().record(e);
        } finally {
            apps.auditDown(false);
        }
        assertThat(closed.audit().spooled()).isPositive();
        // The scheduled replay path; retried because the first attempt after an outage can still fail (see above).
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60)).pollInterval(java.time.Duration.ofSeconds(1)).until(() -> {
            closed.audit().replayQuietly();
            return closed.audit().spooled() == 0;
        });
        closed.audit().replayQuietly(); // nothing left: no-op
        assertThat(closed.audit().replay()).isZero();
        Map<String, Object> row = apps.events("exec_outcome", sid).get(0);
        assertThat(row.get("event_id")).isEqualTo(e.id());
        assertThat(row).containsEntry("windows_user", "TEST\\full").containsEntry("data_admin", true).containsEntry("client_ip", "10.0.0.5")
                .containsEntry("profile", "external").containsEntry("database_name", "sales").containsEntry("pg_session_user", "full")
                .containsEntry("backend_pid", 4242).containsEntry("db_session_id", "db-session-1").containsEntry("execution_id", exec)
                .containsEntry("intent_id", intent).containsEntry("statement_class", "dml").containsEntry("sql_normalised", "UPDATE t SET a = $1")
                .containsEntry("sql_length", 33).containsEntry("parse_status", "ok").containsEntry("outcome", "success")
                .containsEntry("sqlstate", "00000").containsEntry("row_count", rows).containsEntry("truncated", false)
                .containsEntry("duration_ms", 12L).containsEntry("cancel_outcome", "none").containsEntry("tx_before", "IDLE")
                .containsEntry("tx_after", "INTRANS");
        assertThat(((java.sql.Timestamp) row.get("started_at")).toInstant()).isEqualTo(start);
        assertThat(String.valueOf(row.get("details"))).contains("quote").contains("spooledAt");
        assertThat(e.windowsUser()).isEqualTo("TEST\\full");
        assertThat(e.outcome()).isEqualTo("success");
        assertThat(chainIntact()).isTrue();
    }

    @Test
    void recordsCarryIdentityRequestAndKeyedHashesButNoSqlText() throws Exception {
        MockClient c = user(closed, Seeds.random("fields"));
        String sid = c.me().path("user").path("sid").asText();
        HttpResponse<String> r = c.post("/api/test/audited", "{}");
        Map<String, Object> e = apps.events("exec_intent", sid).get(0);
        assertThat(e.get("windows_user")).asString().startsWith("TEST\\aud");
        assertThat(e.get("request_id")).isEqualTo(r.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(e.get("sql_hmac")).asString().matches("[0-9a-f]{64}").isEqualTo(closed.audit().sqlHmac("SELECT 1"));
        assertThat(e.get("sql_normalised")).isEqualTo("SELECT $1");
        assertThat(e.get("app_session_hash")).asString().hasSize(32);
        assertThat(e.get("client_ip")).asString().isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
    }
}
