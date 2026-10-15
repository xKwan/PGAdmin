package pgweb.server;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import pgweb.config.Config;
import pgweb.web.JsonErrorReportValve;
import pgweb.web.RequestIdFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedded Tomcat 11 (ADR-0001): one connector (HTTPS in production), one root context, error pages as JSON.
 * Components register filters and servlets through {@link Routes}; the order of filter registration is the order
 * of the chain.
 */
public final class WebServer implements AutoCloseable {

    /** Collects the filters and servlets of the application before the server starts. */
    public static final class Routes {
        private record F(String name, Filter filter, Map<String, String> params, List<String> patterns) {
        }

        private record S(String name, HttpServlet servlet, List<String> patterns) {
        }

        private final List<F> filters = new ArrayList<>();
        private final List<S> servlets = new ArrayList<>();

        public Routes filter(String name, Filter filter, Map<String, String> params, String... patterns) {
            filters.add(new F(name, filter, params, List.of(patterns)));
            return this;
        }

        public Routes servlet(String name, HttpServlet servlet, String... patterns) {
            servlets.add(new S(name, servlet, List.of(patterns)));
            return this;
        }
    }

    private final Tomcat tomcat;
    private final Connector connector;

    public WebServer(Config config, Routes routes) throws IOException {
        // A fixed working folder (server.workDir), not %TEMP%: Tomcat keeps files open in it, so a temp folder
        // could not be removed on Windows and a killed service would leave one behind each time.
        Path baseDir = Files.createDirectories(config.server().workDir().toAbsolutePath());
        this.tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.getServer().setPort(-1); // no shutdown port

        connector = new Connector("HTTP/1.1");
        connector.setPort(config.server().port());
        connector.setProperty("address", config.server().bind());
        connector.setProperty("server", "pgweb");
        connector.setXpoweredBy(false);
        connector.setProperty("maxHttpHeaderSize", "65536"); // Kerberos tokens with many groups are large
        connector.setProperty("connectionTimeout", "20000");
        connector.setProperty("maxPostSize", String.valueOf(2 * 1024 * 1024));
        connector.setAllowTrace(false);
        config.server().tls().ifPresent(tls -> {
            connector.setSecure(true);
            connector.setScheme("https");
            connector.setProperty("SSLEnabled", "true");
            SSLHostConfig ssl = new SSLHostConfig();
            ssl.setProtocols("TLSv1.2,TLSv1.3");
            SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
            cert.setCertificateKeystoreFile(tls.keystore().toAbsolutePath().toString());
            cert.setCertificateKeystorePassword(tls.keystorePassword().reveal());
            cert.setCertificateKeystoreType("PKCS12");
            ssl.addCertificate(cert);
            connector.addSslHostConfig(ssl);
        });
        tomcat.getService().addConnector(connector);
        tomcat.setConnector(connector);

        tomcat.getHost().setAutoDeploy(false);
        // Replace the host's default HTML error valve (which prints the Tomcat version) rather than adding a second one.
        ((org.apache.catalina.core.StandardHost) tomcat.getHost()).setErrorReportValveClass(JsonErrorReportValve.class.getName());
        Context ctx = tomcat.addContext("", baseDir.toAbsolutePath().toString());
        Rfc6265CookieProcessor cookies = new Rfc6265CookieProcessor();
        cookies.setSameSiteCookies("strict");
        ctx.setCookieProcessor(cookies);
        ctx.setUseHttpOnly(true);
        ctx.setSessionTimeout((int) config.auth().sessionIdle().toMinutes());
        ctx.getManager();

        addFilter(ctx, "requestId", new RequestIdFilter(), Map.of(), "/*");
        for (Routes.F f : routes.filters) {
            addFilter(ctx, f.name, f.filter, f.params, f.patterns.toArray(String[]::new));
        }
        for (Routes.S s : routes.servlets) {
            Tomcat.addServlet(ctx, s.name, s.servlet);
            for (String p : s.patterns) {
                ctx.addServletMapping(p, s.name);
            }
        }
    }

    private static void addFilter(Context ctx, String name, Filter filter, Map<String, String> params, String... patterns) {
        FilterDef def = new FilterDef();
        def.setFilterName(name);
        def.setFilter(filter);
        params.forEach(def::addInitParameter);
        ctx.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName(name);
        for (String p : patterns) {
            map.addURLPattern(p);
        }
        map.setDispatcher("REQUEST"); // never re-run on ERROR/FORWARD dispatches (S1 W3)
        ctx.addFilterMap(map);
    }

    public void start() throws LifecycleException {
        tomcat.start();
    }

    /** The bound port (useful with port 0 in tests). */
    public int port() {
        return connector.getLocalPort();
    }

    /** Stops accepting requests, lets running ones finish, then releases everything. Idempotent. */
    @Override
    public void close() {
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException e) {
            // already stopping
        }
    }
}
