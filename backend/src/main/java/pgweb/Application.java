package pgweb;

import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgweb.audit.AuditDatabase;
import pgweb.audit.AuditService;
import pgweb.audit.AuditSpool;
import pgweb.authz.Access;
import pgweb.authz.AccessFilter;
import pgweb.config.Config;
import pgweb.config.Mode;
import pgweb.security.AuthSchemeFilter;
import pgweb.security.CsrfFilter;
import pgweb.security.SecurityHeadersFilter;
import pgweb.security.SessionFilter;
import pgweb.security.WaffleAuthenticationFilter;
import pgweb.server.WebServer;
import pgweb.web.HealthServlet;
import pgweb.web.Json;
import pgweb.web.LogoutServlet;
import pgweb.web.MeServlet;
import pgweb.web.NotFoundServlet;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Wires the service together with plain constructors (no DI container, ADR-0001) and owns the shutdown order:
 * stop accepting requests, then close database resources.
 *
 * <p>Filter chain for every request, in order (plan §5): request ID → security headers → Authorization scheme
 * (W8, Kerberos-only) → Waffle → app session (fail-closed, fixation, lifetimes, domains) → CSRF → route access.
 */
public final class Application implements AutoCloseable {

    /**
     * Test hooks. {@code authOverride} replaces Waffle (the mock provider in tests); it is refused in prod mode
     * (threat T26). {@code extraRoutes} adds test-only endpoints; {@code clock} drives session lifetimes.
     */
    public record Options(Filter authOverride, Consumer<WebServer.Routes> extraRoutes, Clock clock) {
        public static Options defaults() {
            return new Options(null, r -> { }, Clock.systemUTC());
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    private final Config config;
    private final AuditDatabase auditDb;
    private final AuditService audit;
    private final WebServer server;
    private final List<WebServer.Routes.Route> inventory;
    private volatile boolean closed;

    private Application(Config config, AuditDatabase auditDb, AuditService audit, WebServer.Routes routes) throws java.io.IOException {
        this.config = config;
        this.auditDb = auditDb;
        this.audit = audit;
        this.inventory = routes.inventory();
        this.server = new WebServer(config, routes); // owned by this application; released by close()
    }

    public static Application start(Config config) throws Exception {
        return start(config, Options.defaults());
    }

    public static Application start(Config config, Options options) throws Exception {
        if (config.mode() == Mode.PROD && options.authOverride() != null) {
            throw new IllegalStateException("an authentication override is refused with pgweb.mode=prod (threat T26)");
        }
        AuditDatabase auditDb = new AuditDatabase(config.audit());
        AuditService audit;
        try {
            AuditSpool spool = new AuditSpool(config.audit().spoolDir(), Json.mapper(), options.clock());
            audit = new AuditService(auditDb, spool, config.audit().failClosed() ? AuditService.FailMode.CLOSED : AuditService.FailMode.OPEN,
                    config.audit().hmacKey(), Json.mapper(), true);
        } catch (RuntimeException e) {
            auditDb.close();
            throw e;
        }

        WebServer.Routes routes = new WebServer.Routes()
                .servlet("health", new HealthServlet(null), Access.PUBLIC, "/api/health")
                .servlet("ready", new HealthServlet(auditDb::ping), Access.PUBLIC, "/api/ready")
                .servlet("me", new MeServlet(config), Access.AUTHENTICATED, "/api/me")
                .servlet("logout", new LogoutServlet(audit), Access.AUTHENTICATED, "/api/logout");
        options.extraRoutes().accept(routes);
        routes.servlet("default", new NotFoundServlet(), Access.AUTHENTICATED, "/");

        AtomicReference<Application> self = new AtomicReference<>();
        boolean tls = config.server().tls().isPresent();
        Map<String, Access> access = routes.accessByServlet();
        routes.filter("securityHeaders", new SecurityHeadersFilter(), Map.of(), "/*")
                .filter("authScheme", new AuthSchemeFilter(config.auth().kerberosOnly()), Map.of(), "/*")
                .filter("authentication", options.authOverride() != null ? options.authOverride()
                        : new WaffleAuthenticationFilter(config.auth().protocols()), Map.of(), "/*")
                .filter("session", new SessionFilter(config.auth(), audit, options.clock()), Map.of(), "/*")
                .filter("csrf", new CsrfFilter(() -> CsrfFilter.originsFor(config.server().publicHost(),
                        self.get() == null ? config.server().port() : self.get().port(), tls), audit), Map.of(), "/*")
                .filter("access", new AccessFilter(access, audit), Map.of(), "/*");

        Application app;
        try {
            app = new Application(config, auditDb, audit, routes);
        } catch (Exception e) {
            audit.close();
            auditDb.close();
            throw e;
        }
        self.set(app);
        // From here the application owns the server and the audit resources; close() releases them.
        try {
            app.server.start();
        } catch (Exception e) {
            app.close();
            throw e;
        }
        app.maintainPartitions();
        LOG.info("pgweb {} started on {}:{} (mode {}, execution {}, audit fail mode {})", BuildInfo.version(), config.server().bind(),
                app.port(), config.mode(), config.executionEnabled() ? "ENABLED" : "disabled", audit.failMode());
        return app;
    }

    private void maintainPartitions() {
        try {
            auditDb.ensurePartitions(3);
        } catch (java.sql.SQLException e) {
            LOG.warn("audit database not reachable at startup; audited operations are refused until it is (fail-closed)");
        }
    }

    public int port() {
        return server.port();
    }

    public Config config() {
        return config;
    }

    public AuditDatabase auditDatabase() {
        return auditDb;
    }

    public AuditService audit() {
        return audit;
    }

    /** Every registered endpoint with its declared access (the authorisation matrix test iterates it). */
    public List<WebServer.Routes.Route> inventory() {
        return inventory;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        LOG.info("pgweb shutting down");
        server.close();
        audit.close();
        auditDb.close();
    }
}
