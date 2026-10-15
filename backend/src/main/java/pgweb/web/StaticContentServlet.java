package pgweb.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pgweb.security.PublicPaths;
import pgweb.security.SecurityHeadersFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Default servlet ({@code /}): serves the single-page app built into the JAR under {@code static/} (plan §2).
 * <ul>
 *   <li>{@code index.html} gets a fresh CSP nonce per response (in a {@code <meta name="csp-nonce">} tag, which the
 *       editor uses for its styles) and the SPA policy from {@link SecurityHeadersFilter#spaCsp}; never cached.</li>
 *   <li>Hashed assets ({@code /assets/...}) are cached as immutable.</li>
 *   <li>Paths without a file extension fall back to {@code index.html} (client-side views).</li>
 *   <li>Unknown {@code /api/...} paths keep the JSON 404 envelope. Paths with {@code ..}, backslashes, control or
 *       encoded characters are refused.</li>
 * </ul>
 * Without a built SPA on the classpath (backend-only development) every non-API path is a 404.
 */
public final class StaticContentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String ROOT = "static/";
    private static final String INDEX = "index.html";
    private static final String NOT_FOUND = "Not found";
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._/-]*$");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html;charset=UTF-8"), Map.entry("js", "text/javascript;charset=UTF-8"),
            Map.entry("css", "text/css;charset=UTF-8"), Map.entry("json", "application/json"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("ico", "image/x-icon"), Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain;charset=UTF-8"), Map.entry("map", "application/json"));

    private final transient ClassLoader loader;
    private final transient String indexTemplate;

    public StaticContentServlet() {
        this(StaticContentServlet.class.getClassLoader());
    }

    StaticContentServlet(ClassLoader loader) {
        this.loader = loader;
        this.indexTemplate = read(ROOT + INDEX);
    }

    private String read(String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String method = req.getMethod();
        String path = PublicPaths.path(req);
        if (path.startsWith("/api/") || path.equals("/api")) {
            notFound(resp, NOT_FOUND);
            return;
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            ErrorEnvelope.write(resp, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
            return;
        }
        if (!SAFE_PATH.matcher(path).matches() || path.contains("..") || path.contains("//")) {
            notFound(resp, NOT_FOUND);
            return;
        }
        if (indexTemplate == null) {
            notFound(resp, "Not found (the web interface is not built into this server)");
            return;
        }
        String file = path.equals("/") ? INDEX : path.substring(1);
        String ext = extension(file);
        if (file.equals(INDEX) || ext.isEmpty()) {
            sendIndex(req, resp, method.equals("HEAD"));
            return;
        }
        try (InputStream in = file.endsWith("/") ? null : loader.getResourceAsStream(ROOT + file)) {
            if (in == null || !TYPES.containsKey(ext)) {
                notFound(resp, NOT_FOUND);
                return;
            }
            byte[] body = in.readAllBytes();
            resp.setStatus(200);
            resp.setContentType(TYPES.get(ext));
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.setHeader("Cache-Control", file.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache");
            resp.setContentLength(body.length);
            if (!method.equals("HEAD")) {
                resp.getOutputStream().write(body);
            }
        }
    }

    private void sendIndex(HttpServletRequest req, HttpServletResponse resp, boolean head) throws IOException {
        String nonce = (String) req.getAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE);
        String html = indexTemplate.replaceFirst("(?i)<head>", "<head>\n    <meta name=\"csp-nonce\" content=\"" + nonce + "\" />");
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        resp.setStatus(200);
        resp.setContentType(TYPES.get("html"));
        resp.setHeader("Content-Security-Policy", SecurityHeadersFilter.spaCsp(nonce));
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentLength(body.length);
        if (!head) {
            resp.getOutputStream().write(body);
        }
    }

    private static void notFound(HttpServletResponse resp, String message) throws IOException {
        ErrorEnvelope.write(resp, 404, "NOT_FOUND", message);
    }

    static String extension(String file) {
        int slash = file.lastIndexOf('/');
        int dot = file.lastIndexOf('.');
        return dot > slash ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
