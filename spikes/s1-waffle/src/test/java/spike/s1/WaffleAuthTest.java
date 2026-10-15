package spike.s1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import spike.s1.support.Findings;
import spike.s1.support.NegotiateClient;
import spike.s1.support.RawHttp;
import waffle.windows.auth.IWindowsSecurityContext;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan S1 (partial, workgroup machine): Waffle + Spring Security authenticate the Windows account running the test
 * through a real SSPI handshake, refuse everything else, and expose SID and groups for server-side authorisation.
 * The default configuration makes BUILTIN\Users (S-1-5-32-545) the DataAdmin group.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WaffleAuthTest {

    @Autowired
    Environment env;

    private int port() {
        return Integer.parseInt(env.getProperty("local.server.port"));
    }

    private static String randomPath(Random rnd) {
        return rnd.nextBoolean() ? "/whoami" : "/whoami?r=" + rnd.nextInt(1_000_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Negotiate", "NTLM"})
    void handshakeAuthenticatesTheCurrentWindowsAccount(String scheme, TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String path = randomPath(rnd);
        try (RawHttp http = new RawHttp(port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, path, scheme, Map.of());
            assertEquals(200, r.response().status(), r.response().toString());
            String body = r.response().body();
            String fqn = NegotiateClient.currentUserFqn();
            assertTrue(body.toLowerCase().contains("\"name\":\"" + fqn.toLowerCase().replace("\\", "\\\\") + "\""), body);
            assertTrue(body.contains("\"sid\":\"" + NegotiateClient.currentUserSid() + "\""), body);
            assertTrue(body.contains("S-1-5-32-545"), "member of BUILTIN\\Users: " + body);
            assertTrue(body.contains("S-1-1-0"), "member of Everyone: " + body);
            assertTrue(body.contains("\"dataAdmin\":true"), body);
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
            assertFalse(challenges.stream().anyMatch(c -> c.toLowerCase().startsWith("basic")), "no Basic offered: " + challenges);
            assertFalse(r.body().contains("\"name\""), "no identity data in a 401");
            Findings.record("auth", "challenge", path + " -> 401 " + challenges);
        }
    }

    /** Random garbage in the Authorization header never authenticates and never causes a server error. */
    @Test
    void garbageTokensAreRejected(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String[] schemes = {"Negotiate", "NTLM", "Basic", "Bearer", "negotiate"};
        List<String> seen = new ArrayList<>();
        int cases = 10 + rnd.nextInt(15);
        for (int i = 0; i < cases; i++) {
            byte[] junk = new byte[1 + rnd.nextInt(rnd.nextBoolean() ? 64 : 3000)];
            rnd.nextBytes(junk);
            if (rnd.nextInt(4) == 0 && junk.length > 12) {
                System.arraycopy("NTLMSSP\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, junk, 0, 8);
                junk[8] = (byte) (1 + rnd.nextInt(3)); // plausible NTLM message type with a corrupt body
            }
            String scheme = schemes[rnd.nextInt(schemes.length)];
            try (RawHttp http = new RawHttp(port())) {
                RawHttp.Response r = http.get(randomPath(rnd), Map.of("Authorization", scheme + " " + Base64.getEncoder().encodeToString(junk)));
                seen.add(scheme + "/" + junk.length + "->" + r.status());
                assertNotEquals(200, r.status(), "garbage authenticated: " + scheme);
                assertTrue(r.status() < 500, "server error for garbage token: " + r.status());
            } catch (java.io.IOException closedByServer) {
                seen.add(scheme + "/" + junk.length + "->connection closed");
            }
        }
        Findings.record("auth", "garbage_tokens", seen);
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
            assertTrue(serverToken != null, "server sent a continuation token");
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
            NegotiateClient.Result r = NegotiateClient.handshake(http, "/admin/ping", Findings.random(info.getDisplayName()).nextBoolean() ? "Negotiate" : "NTLM", Map.of());
            assertEquals(200, r.response().status(), r.response().body());
        }
    }

    /**
     * After a successful handshake the server issues a session cookie. Observed and pinned here: whether the
     * session alone (no Authorization header) is accepted on the next request.
     */
    @Test
    void sessionCookieAfterHandshake(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        try (RawHttp http = new RawHttp(port())) {
            NegotiateClient.Result r = NegotiateClient.handshake(http, randomPath(rnd), "Negotiate", Map.of());
            assertEquals(200, r.response().status());
            List<String> cookies = r.response().header("Set-Cookie");
            String session = cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).map(c -> c.split(";")[0]).findFirst().orElse(null);
            String next = "no session cookie issued";
            if (session != null) {
                try (RawHttp again = new RawHttp(port())) {
                    next = "session-only request -> " + again.get(randomPath(rnd), Map.of("Cookie", session)).status();
                }
            }
            Findings.record("session", "after_handshake", "Set-Cookie=" + cookies.stream().map(c -> c.replaceAll("=[^;]*", "=<redacted>")).toList() + "; " + next);
        }
    }
}
