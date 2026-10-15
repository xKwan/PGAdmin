package spike.s1servlet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import spike.s1servlet.support.Findings;
import spike.s1servlet.support.NegotiateClient;
import spike.s1servlet.support.RawHttp;
import spike.s1servlet.support.ServerFixture;
import waffle.windows.auth.IWindowsSecurityContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 servlet variant (workgroup machine): Waffle's servlet filter on embedded Tomcat 11, no Spring, authenticates the
 * Windows account running the test through a real SSPI handshake, refuses everything else, and exposes SID and groups
 * for server-side authorisation. DataAdmin = BUILTIN\Users (S-1-5-32-545) here.
 */
class WaffleServletAuthTest {

    static SpikeServer server;

    @BeforeAll
    static void start() throws Exception {
        server = ServerFixture.start("S-1-5-32-545", "auth");
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    private static int port() {
        return server.port();
    }

    private static String randomPath(Random rnd) {
        return rnd.nextBoolean() ? "/whoami" : "/whoami?r=" + rnd.nextInt(1_000_000);
    }

    private static String sessionCookie(RawHttp.Response r) {
        return r.header("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).map(c -> c.split(";")[0]).findFirst().orElse(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Negotiate", "NTLM"})
    void handshakeAuthenticatesTheCurrentWindowsAccount(String scheme, TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        try (RawHttp http = new RawHttp(port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, randomPath(rnd), scheme, Map.of());
            assertEquals(200, r.response().status(), r.response().toString());
            String body = r.response().body();
            String fqn = NegotiateClient.currentUserFqn();
            assertTrue(body.toLowerCase(Locale.ROOT).contains("\"name\":\"" + fqn.toLowerCase(Locale.ROOT).replace("\\", "\\\\") + "\""), body);
            assertTrue(body.contains("\"sid\":\"" + NegotiateClient.currentUserSid() + "\""), body);
            assertTrue(body.contains("S-1-5-32-545"), "member of BUILTIN\\Users: " + body);
            assertTrue(body.contains("S-1-1-0"), "member of Everyone: " + body);
            assertTrue(body.contains("\"dataAdmin\":true"), body);
            assertEquals(List.of("no-store"), r.response().header("Cache-Control"));
            Findings.record("auth", "handshake_" + scheme, "status=200 legs=" + r.legs() + " mechanism=" + r.mechanism());
        }
    }

    @Test
    void noAuthorizationHeaderGetsAChallengeWithoutBasic(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String path = rnd.nextBoolean() ? randomPath(rnd) : "/admin/ping";
        try (RawHttp http = new RawHttp(port())) {
            RawHttp.Response r = http.get(path, Map.of());
            assertEquals(401, r.status());
            List<String> challenges = r.header("WWW-Authenticate");
            assertTrue(challenges.contains("Negotiate"), challenges.toString());
            assertTrue(challenges.contains("NTLM"), challenges.toString());
            assertFalse(challenges.stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).startsWith("basic")), "no Basic offered: " + challenges);
            assertFalse(r.body().contains("\"name\""), "no identity data in a 401");
            assertFalse(r.body().contains("Tomcat"), "no server version in the error page: " + r.body());
            assertTrue(r.header("Set-Cookie").isEmpty(), "no session before authentication: " + r.header("Set-Cookie"));
            Findings.record("auth", "challenge", path + " -> 401 " + challenges);
        }
    }

    /** Random garbage in the Authorization header never authenticates and never causes a server error. */
    @Test
    void garbageTokensAreRejected(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String[] schemes = {"Negotiate", "NTLM", "Basic", "Bearer", "negotiate"};
        List<String> seen = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        int cases = 10 + rnd.nextInt(15);
        for (int i = 0; i < cases; i++) {
            byte[] junk = new byte[1 + rnd.nextInt(rnd.nextBoolean() ? 64 : 3000)];
            rnd.nextBytes(junk);
            if (rnd.nextInt(4) == 0 && junk.length > 12) {
                System.arraycopy("NTLMSSP\0".getBytes(StandardCharsets.ISO_8859_1), 0, junk, 0, 8);
                junk[8] = (byte) (1 + rnd.nextInt(3)); // plausible NTLM message type with a corrupt body
            }
            // The first two cases are always Basic and Bearer, which Waffle alone answers with 500 (W8).
            String scheme = i < 2 ? schemes[2 + i] : schemes[rnd.nextInt(schemes.length)];
            String kind = scheme + "/" + junk.length + (junk.length > 12 && junk[0] == 'N' && junk[1] == 'T' ? "/ntlmssp" + junk[8] : "");
            try (RawHttp http = new RawHttp(port())) {
                RawHttp.Response r = http.get(randomPath(rnd), Map.of("Authorization", scheme + " " + Base64.getEncoder().encodeToString(junk)));
                seen.add(kind + "->" + r.status());
                if (r.status() == 200) {
                    bad.add("authenticated: " + kind);
                } else if (r.status() >= 500) {
                    bad.add("server error: " + kind + "->" + r.status());
                }
            } catch (java.io.IOException closedByServer) {
                seen.add(kind + "->connection closed");
            }
        }
        Findings.record("auth", "garbage_tokens", seen);
        assertTrue(bad.isEmpty(), bad + " of " + seen);
    }

    /** The final client token of a completed handshake, replayed on a new connection, does not authenticate. */
    @ParameterizedTest
    @ValueSource(strings = {"Negotiate", "NTLM"})
    void replayedTokenIsRejected(String scheme, TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        NegotiateClient.Result first;
        try (RawHttp http = new RawHttp(port())) {
            first = NegotiateClient.handshake(http, randomPath(rnd), scheme, Map.of());
            assertEquals(200, first.response().status());
        }
        byte[] last = first.clientTokens().get(first.clientTokens().size() - 1);
        try (RawHttp other = new RawHttp(port())) {
            RawHttp.Response r = other.get(randomPath(rnd), Map.of("Authorization", scheme + " " + Base64.getEncoder().encodeToString(last)));
            assertNotEquals(200, r.status(), "replayed token authenticated");
            Findings.record("auth", "replay_" + scheme, "legs=" + first.legs() + " replay -> " + r.status());
        }
    }

    /** NTLM is connection-bound: finishing the handshake on a different TCP connection fails. */
    @ParameterizedTest
    @ValueSource(strings = {"Negotiate", "NTLM"})
    void handshakeSplitAcrossConnectionsFails(String scheme, TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        IWindowsSecurityContext ctx = NegotiateClient.start(scheme);
        try (RawHttp a = new RawHttp(port()); RawHttp b = new RawHttp(port())) {
            RawHttp.Response challenge = a.get(randomPath(rnd), Map.of("Authorization", scheme + " " + Base64.getEncoder().encodeToString(ctx.getToken())));
            assertEquals(401, challenge.status());
            byte[] serverToken = NegotiateClient.serverToken(challenge, scheme);
            assertNotNull(serverToken, "server sent a continuation token");
            byte[] next = NegotiateClient.continueWith(ctx, serverToken);
            RawHttp.Response r = b.get(randomPath(rnd), Map.of("Authorization", scheme + " " + Base64.getEncoder().encodeToString(next)));
            assertNotEquals(200, r.status(), "handshake completed on another connection");
            Findings.record("auth", "split_connection_" + scheme, "second leg on new connection -> " + r.status());
        } finally {
            ctx.dispose();
        }
    }

    /** Several handshakes at once, each on its own connection, all succeed for the right account. */
    @Test
    void concurrentHandshakes(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        int n = 2 + rnd.nextInt(7);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String scheme = rnd.nextBoolean() ? "Negotiate" : "NTLM";
                String path = randomPath(rnd);
                results.add(pool.submit(() -> {
                    try (RawHttp http = new RawHttp(port())) {
                        NegotiateClient.Result r = NegotiateClient.handshake(http, path, scheme, Map.of());
                        assertTrue(r.response().body().contains(NegotiateClient.currentUserSid()));
                        return r.response().status();
                    }
                }));
            }
            for (Future<Integer> f : results) {
                assertEquals(200, f.get(60, TimeUnit.SECONDS));
            }
            Findings.record("auth", "concurrent", n + " parallel handshakes, all 200");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dataAdminEndpointAllowedForConfiguredGroup(TestInfo info) throws Exception {
        try (RawHttp http = new RawHttp(port())) {
            String scheme = Findings.random(info.getDisplayName()).nextBoolean() ? "Negotiate" : "NTLM";
            NegotiateClient.Result r = NegotiateClient.handshake(http, "/admin/ping", scheme, Map.of());
            assertEquals(200, r.response().status(), r.response().body());
        }
    }

    /**
     * After a handshake the server issues a session cookie (HttpOnly, SameSite=Strict). The session alone, with no
     * Authorization header, is then accepted for the same account (Waffle keeps the principal in the session).
     */
    @Test
    void sessionCookieAfterHandshake(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String session;
        try (RawHttp http = new RawHttp(port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, randomPath(rnd), rnd.nextBoolean() ? "Negotiate" : "NTLM", Map.of());
            assertEquals(200, r.response().status());
            String setCookie = r.response().header("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElse("");
            assertTrue(setCookie.contains("HttpOnly"), setCookie.replaceAll("=[^;]*", "=<redacted>"));
            assertTrue(setCookie.contains("SameSite=Strict"), setCookie.replaceAll("=[^;]*", "=<redacted>"));
            session = sessionCookie(r.response());
            assertNotNull(session);
        }
        try (RawHttp again = new RawHttp(port())) {
            RawHttp.Response r = again.get(randomPath(rnd), Map.of("Cookie", session));
            assertEquals(200, r.status(), "session-only request");
            assertTrue(r.body().contains(NegotiateClient.currentUserSid()));
        }
        Findings.record("session", "after_handshake", "cookie HttpOnly+SameSite=Strict; session-only request -> 200");
    }

    /**
     * Session fixation: a session created before authentication (planted by an attacker) must not become the
     * authenticated session. Waffle stores the principal in whatever session exists; AuthenticatedSessionFilter
     * rotates the ID on first authentication.
     */
    @Test
    void plantedSessionIsRotatedOnAuthentication(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String planted;
        try (RawHttp http = new RawHttp(port())) {
            RawHttp.Response r = http.get("/anon/session", Map.of());
            assertEquals(204, r.status());
            planted = sessionCookie(r);
            assertNotNull(planted, "anonymous session created");
        }
        try (RawHttp http = new RawHttp(port())) {
            RawHttp.Response r = http.get(randomPath(rnd), Map.of("Cookie", planted));
            assertEquals(401, r.status(), "a session without authentication does not grant access");
        }
        String rotated;
        try (RawHttp http = new RawHttp(port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, randomPath(rnd), rnd.nextBoolean() ? "Negotiate" : "NTLM", Map.of("Cookie", planted));
            assertEquals(200, r.response().status());
            rotated = sessionCookie(r.response());
            assertNotNull(rotated, "a new session ID is issued on authentication");
            assertNotEquals(planted, rotated);
        }
        try (RawHttp http = new RawHttp(port())) {
            assertEquals(401, http.get(randomPath(rnd), Map.of("Cookie", planted)).status(), "planted ID is not authenticated");
        }
        try (RawHttp http = new RawHttp(port())) {
            assertEquals(200, http.get(randomPath(rnd), Map.of("Cookie", rotated)).status(), "rotated ID is authenticated");
        }
        Findings.record("session", "fixation", "planted session ID rotated on authentication; old ID -> 401, new ID -> 200");
    }

    /** Error pages carry no stack trace and no server version. */
    @Test
    void errorPagesDoNotLeakServerDetails(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String path = "/missing-" + rnd.nextInt(1_000_000);
        try (RawHttp http = new RawHttp(port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, path, "Negotiate", Map.of());
            assertEquals(404, r.response().status());
            assertFalse(r.response().body().contains("Tomcat"), r.response().body());
            assertFalse(r.response().body().contains("Exception"), r.response().body());
            assertTrue(r.response().header("Server").isEmpty(), "no Server header: " + r.response().header("Server"));
            Findings.record("errors", "not_found", "404 without server version or stack trace");
        }
    }
}
