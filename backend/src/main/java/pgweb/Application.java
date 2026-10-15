package pgweb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgweb.audit.AuditDatabase;
import pgweb.config.Config;
import pgweb.server.WebServer;
import pgweb.web.HealthServlet;

import java.util.function.Consumer;

/**
 * Wires the service together with plain constructors (no DI container, ADR-0001) and owns the shutdown order:
 * stop accepting requests, then close database resources.
 */
public final class Application implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    private final Config config;
    private final AuditDatabase audit;
    private final WebServer server;
    private volatile boolean closed;

    private Application(Config config, AuditDatabase audit, WebServer.Routes routes) throws java.io.IOException {
        this.config = config;
        this.audit = audit;
        this.server = new WebServer(config, routes); // owned by this application; released by close()
    }

    public static Application start(Config config) throws Exception {
        return start(config, routes -> { });
    }

    /** {@code extra} lets tests add routes; production code passes none. */
    public static Application start(Config config, Consumer<WebServer.Routes> extra) throws Exception {
        AuditDatabase audit = new AuditDatabase(config.audit());
        WebServer.Routes routes = new WebServer.Routes()
                .servlet("health", new HealthServlet(null), "/api/health")
                .servlet("ready", new HealthServlet(audit::ping), "/api/ready");
        extra.accept(routes);
        routes.servlet("default", new pgweb.web.NotFoundServlet(), "/");
        Application app;
        try {
            app = new Application(config, audit, routes);
        } catch (Exception e) {
            audit.close();
            throw e;
        }
        // From here the application owns the server and the audit pool; close() releases both.
        try {
            app.server.start();
        } catch (Exception e) {
            app.close();
            throw e;
        }
        app.maintainPartitions();
        LOG.info("pgweb {} started on {}:{} (mode {}, execution {})", BuildInfo.version(), config.server().bind(), app.port(),
                config.mode(), config.executionEnabled() ? "ENABLED" : "disabled");
        return app;
    }

    private void maintainPartitions() {
        try {
            audit.ensurePartitions(3);
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

    public AuditDatabase audit() {
        return audit;
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
    }
}
