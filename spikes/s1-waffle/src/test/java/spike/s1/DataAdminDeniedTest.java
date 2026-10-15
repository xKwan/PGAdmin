package spike.s1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import spike.s1.support.Findings;
import spike.s1.support.NegotiateClient;
import spike.s1.support.RawHttp;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataAdmin configured as a group the test account is not in (a random, non-existent domain SID): the account still
 * authenticates, but the server refuses DataAdmin-only endpoints. Authorisation is decided on the server from the
 * group SIDs in the Windows token, never from anything the client sends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataAdminDeniedTest {

    static final String FOREIGN_SID;

    static {
        Random rnd = Findings.random("DataAdminDeniedTest");
        FOREIGN_SID = "S-1-5-21-" + (1_000_000 + rnd.nextInt(9_000_000)) + "-" + (1_000_000 + rnd.nextInt(9_000_000)) + "-"
                + (1_000_000 + rnd.nextInt(9_000_000)) + "-" + (1000 + rnd.nextInt(9000));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("pgweb.data-admin-sid", () -> FOREIGN_SID);
    }

    @Autowired
    Environment env;

    @Test
    void authenticatedButNotDataAdmin(TestInfo info) throws Exception {
        int port = Integer.parseInt(env.getProperty("local.server.port"));
        Random rnd = Findings.random(info.getDisplayName());
        String scheme = rnd.nextBoolean() ? "Negotiate" : "NTLM";
        try (RawHttp http = new RawHttp(port)) {
            NegotiateClient.Result who = NegotiateClient.handshake(http, "/whoami", scheme, Map.of());
            assertEquals(200, who.response().status());
            assertTrue(who.response().body().contains("\"dataAdmin\":false"), who.response().body());
        }
        try (RawHttp http = new RawHttp(port)) {
            NegotiateClient.Result admin = NegotiateClient.handshake(http, "/admin/ping", scheme, Map.of());
            assertEquals(403, admin.response().status(), "authenticated non-DataAdmin is forbidden: legs=" + admin.legs() + " " + admin.response());
            Findings.record("authz", "data_admin_denied", "dataAdminSid=<random foreign SID> /admin/ping -> 403 via " + scheme);
        }
        try (RawHttp http = new RawHttp(port)) {
            // A client-supplied header claiming the role changes nothing.
            NegotiateClient.Result spoof = NegotiateClient.handshake(http, "/admin/ping", scheme,
                    Map.of("X-Roles", "DataAdmin", "X-Data-Admin", "true", "X-Forwarded-User", "Administrator"));
            assertEquals(403, spoof.response().status(), "client headers do not grant DataAdmin");
        }
    }
}
