package spike.s1servlet;

import jakarta.servlet.Filter;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import waffle.servlet.NegotiateSecurityFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Embedded Apache Tomcat 11 with Waffle's servlet filter and plain servlet filters for authorisation. No Spring.
 * <p>Filter order (all on the REQUEST dispatch only, so a container ERROR forward never re-runs Waffle; S1 finding W3):
 * <ol>
 *   <li>{@link AuthorizationSchemeFilter}: hides Authorization headers Waffle cannot handle (W8).</li>
 *   <li>Waffle {@link NegotiateSecurityFilter}: Negotiate and NTLM only, no guest, no impersonation.</li>
 *   <li>{@link AuthenticatedSessionFilter}: refuses any request without a Windows principal, rotates the session ID on
 *       first authentication and binds the session to the SID (S1 finding W4).</li>
 *   <li>{@link DataAdminFilter} on {@code /admin/*}: DataAdmin by group SID, decided on the server.</li>
 * </ol>
 */
public final class SpikeServer implements AutoCloseable {

    /** Spike-only path outside Waffle, used to plant a pre-authentication session (session fixation test). */
    static final String ANON_PREFIX = "/anon/";

    private final Tomcat tomcat;
    private final Connector connector;

    private SpikeServer(Tomcat tomcat, Connector connector) {
        this.tomcat = tomcat;
        this.connector = connector;
    }

    public static SpikeServer start(int port, String dataAdminSid, Path baseDir) throws Exception {
        Files.createDirectories(baseDir);
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toAbsolutePath().toString());

        Connector connector = new Connector();
        connector.setPort(port);
        connector.setProperty("address", "127.0.0.1");
        tomcat.setConnector(connector);

        // No stack traces or server version in error pages.
        ErrorReportValve errors = new ErrorReportValve();
        errors.setShowReport(false);
        errors.setShowServerInfo(false);
        tomcat.getHost().getPipeline().addValve(errors);

        Context ctx = tomcat.addContext("", baseDir.toAbsolutePath().toString());
        Rfc6265CookieProcessor cookies = new Rfc6265CookieProcessor();
        cookies.setSameSiteCookies("strict");
        ctx.setCookieProcessor(cookies);
        ctx.setUseHttpOnly(true);
        ctx.setSessionTimeout(30);

        addFilter(ctx, "authorizationScheme", new AuthorizationSchemeFilter(), Map.of(), "/*");
        addFilter(ctx, "waffle", new NegotiateSecurityFilter(), Map.of(
                "principalFormat", "fqn",
                "roleFormat", "both",
                "allowGuestLogin", "false",
                "impersonate", "false",
                // Waffle matches exclusions against the full request URL.
                "excludePatterns", "^https?://[^/]+" + ANON_PREFIX + ".*",
                "securityFilterProviders", "waffle.servlet.spi.NegotiateSecurityFilterProvider",
                "waffle.servlet.spi.NegotiateSecurityFilterProvider/protocols", "Negotiate NTLM"), "/*");
        addFilter(ctx, "authenticatedSession", new AuthenticatedSessionFilter(ANON_PREFIX), Map.of(), "/*");
        addFilter(ctx, "dataAdmin", new DataAdminFilter(dataAdminSid), Map.of(), "/admin/*");

        Tomcat.addServlet(ctx, "whoami", new WhoAmIServlet(dataAdminSid));
        ctx.addServletMappingDecoded("/whoami", "whoami");
        Tomcat.addServlet(ctx, "adminPing", new AdminPingServlet());
        ctx.addServletMappingDecoded("/admin/ping", "adminPing");
        Tomcat.addServlet(ctx, "anonSession", new AnonSessionServlet());
        ctx.addServletMappingDecoded(ANON_PREFIX + "session", "anonSession");

        try {
            tomcat.start();
        } catch (LifecycleException e) {
            tomcat.destroy();
            throw e;
        }
        if (connector.getLocalPort() <= 0) {
            tomcat.stop();
            tomcat.destroy();
            throw new IllegalStateException("connector did not start: " + connector.getStateName());
        }
        return new SpikeServer(tomcat, connector);
    }

    private static void addFilter(Context ctx, String name, Filter filter, Map<String, String> params, String pattern) {
        FilterDef def = new FilterDef();
        def.setFilterName(name);
        def.setFilter(filter);
        params.forEach(def::addInitParameter);
        ctx.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName(name);
        map.addURLPattern(pattern);
        map.setDispatcher("REQUEST");
        ctx.addFilterMap(map);
    }

    public int port() {
        return connector.getLocalPort();
    }

    @Override
    public void close() throws LifecycleException {
        tomcat.stop();
        tomcat.destroy();
    }

    /** {@code java -cp ... spike.s1servlet.SpikeServer --port=8080 [--data-admin-sid=S-1-5-32-545]}. */
    public static void main(String[] args) throws Exception {
        int port = 0;
        String sid = "S-1-5-32-545";
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = Integer.parseInt(a.substring(7));
            } else if (a.startsWith("--data-admin-sid=")) {
                sid = a.substring(17);
            } else {
                throw new IllegalArgumentException("unknown argument: " + a);
            }
        }
        SpikeServer server = start(port, sid, Path.of("target", "tomcat-main"));
        System.out.println("listening on 127.0.0.1:" + server.port());
        server.tomcat.getServer().await();
    }
}
