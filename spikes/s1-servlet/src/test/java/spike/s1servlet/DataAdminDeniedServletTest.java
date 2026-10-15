package spike.s1servlet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import spike.s1servlet.support.Findings;
import spike.s1servlet.support.NegotiateClient;
import spike.s1servlet.support.RawHttp;
import spike.s1servlet.support.ServerFixture;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataAdmin configured as a group the test account is not in (a random, non-existent domain SID): the account still
 * authenticates, but the server refuses DataAdmin-only endpoints with 403, which stays a 403 through the container's
 * error dispatch (S1 finding W3 does not recur because Waffle is mapped to the REQUEST dispatch only).
 */
class DataAdminDeniedServletTest {

    static SpikeServer server;

    @BeforeAll
    static void start() throws Exception {
        Random rnd = Findings.random("DataAdminDeniedServletTest");
        String foreignSid = "S-1-5-21-" + (1_000_000 + rnd.nextInt(9_000_000)) + "-" + (1_000_000 + rnd.nextInt(9_000_000)) + "-"
                + (1_000_000 + rnd.nextInt(9_000_000)) + "-" + (1000 + rnd.nextInt(9000));
        server = ServerFixture.start(foreignSid, "denied");
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    @Test
    void authenticatedButNotDataAdmin(TestInfo info) throws Exception {
        Random rnd = Findings.random(info.getDisplayName());
        String scheme = rnd.nextBoolean() ? "Negotiate" : "NTLM";
        try (RawHttp http = new RawHttp(server.port())) {
            NegotiateClient.Result who = NegotiateClient.handshake(http, "/whoami", scheme, Map.of());
            assertEquals(200, who.response().status());
            assertTrue(who.response().body().contains("\"dataAdmin\":false"), who.response().body());
        }
        try (RawHttp http = new RawHttp(server.port())) {
            NegotiateClient.Result admin = NegotiateClient.handshake(http, "/admin/ping", scheme, Map.of());
            assertEquals(403, admin.response().status(), "authenticated non-DataAdmin is forbidden: legs=" + admin.legs() + " " + admin.response());
            assertFalse(admin.response().body().contains("Tomcat"), admin.response().body());
            Findings.record("authz", "data_admin_denied", "dataAdminSid=<random foreign SID> /admin/ping -> 403 via " + scheme);
        }
        try (RawHttp http = new RawHttp(server.port())) {
            // A client-supplied header claiming the role changes nothing.
            NegotiateClient.Result spoof = NegotiateClient.handshake(http, "/admin/ping", scheme,
                    Map.of("X-Roles", "DataAdmin", "X-Data-Admin", "true", "X-Forwarded-User", "Administrator"));
            assertEquals(403, spoof.response().status(), "client headers do not grant DataAdmin");
        }
    }
}
