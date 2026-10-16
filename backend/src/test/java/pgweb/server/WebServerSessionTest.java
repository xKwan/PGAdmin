package pgweb.server;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.authz.Access;
import pgweb.config.Config;
import pgweb.testsupport.TestConfig;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sessions are never written to disk, so a service restart signs everyone out (docs/operations/runbooks.md relies on
 * it to make a DataAdmin removal take effect at once). Guards against a session manager that saves sessions to
 * SESSIONS.ser on a graceful stop and restores them, which would also leave identities in the work folder.
 */
class WebServerSessionTest {

    @TempDir
    Path tmp;

    /** GET: reports whether a session existed; with ?create, creates one holding a serializable attribute. */
    static final class SessionServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            HttpSession existing = req.getSession(false);
            if (req.getParameter("create") != null) {
                req.getSession(true).setAttribute("user", "TEST\\someone");
            }
            res.setContentType("text/plain");
            res.getWriter().write(existing == null ? "none" : String.valueOf(existing.getAttribute("user")));
        }
    }

    /** Starts a server on {@code port} (0: any free port) with the same work folder each time. */
    WebServer start(int port) throws Exception {
        java.util.Properties p = TestConfig.base(tmp);
        p.setProperty("server.port", String.valueOf(port));
        Config cfg = TestConfig.load(p);
        WebServer w = new WebServer(cfg, new WebServer.Routes().servlet("s", new SessionServlet(), Access.PUBLIC, "/s"));
        w.start();
        return w;
    }

    @Test
    void aRestartDoesNotRestoreSessions() throws Exception {
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        WebServer first = start(0);
        int port = first.port();
        assertThat(get(http, port, "/s?create")).isEqualTo("none");
        assertThat(get(http, port, "/s")).isEqualTo("TEST\\someone");
        first.close();

        try (Stream<Path> files = Files.walk(tmp.resolve("work"))) {
            assertThat(files.filter(f -> f.getFileName().toString().endsWith(".ser"))).isEmpty();
        }

        WebServer second = start(port);
        try {
            assertThat(get(http, port, "/s")).isEqualTo("none");
        } finally {
            second.close();
        }
    }

    static String get(HttpClient http, int port, String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }
}
