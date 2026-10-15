package pgweb.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import pgweb.web.Json;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * An HTTP client acting as one mock Windows user (or anonymous): its own cookie jar, the {@code X-Mock-User} header,
 * and the session's CSRF token on unsafe requests.
 */
public final class MockClient {

    private final int port;
    private final String userHeader;
    private final HttpClient http;
    private String csrf;

    private MockClient(int port, String userHeader) {
        this.port = port;
        this.userHeader = userHeader;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).cookieHandler(new CookieManager()).build();
    }

    public static MockClient anonymous(int port) {
        return new MockClient(port, null);
    }

    public static MockClient user(int port, String account, String sid, String... groups) {
        return new MockClient(port, MockAuthFilter.header(account, sid, groups));
    }

    public String origin() {
        return "http://localhost:" + port;
    }

    /** Signs in (GET /api/me) and keeps the CSRF token. */
    public JsonNode me() throws Exception {
        HttpResponse<String> r = send("GET", "/api/me", null, Map.of());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("sign-in failed: " + r.statusCode() + " " + r.body());
        }
        JsonNode n = Json.mapper().readTree(r.body());
        csrf = n.path("csrfToken").asText();
        return n;
    }

    public String csrf() {
        return csrf;
    }

    public HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, Map.of());
    }

    /** POST with JSON body, own origin and the CSRF token (when signed in). */
    public HttpResponse<String> post(String path, String json) throws Exception {
        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Origin", origin());
        if (csrf != null) {
            h.put("X-CSRF-Token", csrf);
        }
        return send("POST", path, json, h);
    }

    public HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (userHeader != null && !headers.containsKey(MockAuthFilter.HEADER)) {
            b.header(MockAuthFilter.HEADER, userHeader);
        }
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
