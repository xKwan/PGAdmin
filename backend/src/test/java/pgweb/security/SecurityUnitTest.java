package pgweb.security;

import org.junit.jupiter.api.Test;
import pgweb.testsupport.MutableClock;
import pgweb.testsupport.Seeds;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the parsing and counting logic in the security filters. */
class SecurityUnitTest {

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void schemeParsing() {
        assertThat(AuthSchemeFilter.scheme(null)).isNull();
        assertThat(AuthSchemeFilter.scheme("  ")).isNull();
        assertThat(AuthSchemeFilter.scheme("Negotiate abc")).isEqualTo("negotiate");
        assertThat(AuthSchemeFilter.scheme("  NTLM   abc")).isEqualTo("ntlm");
        assertThat(AuthSchemeFilter.scheme("Basic")).isEqualTo("basic");
    }

    @Test
    void ntlmTokensAreRecognisedRawOrInsideSpnego() {
        Random r = Seeds.random("ntlm-detect");
        for (int i = 0; i < 50; i++) {
            byte[] prefix = new byte[r.nextInt(60)];
            r.nextBytes(prefix);
            byte[] suffix = new byte[r.nextInt(200)];
            r.nextBytes(suffix);
            byte[] ntlm = "NTLMSSP\0".getBytes(StandardCharsets.ISO_8859_1);
            byte[] token = new byte[prefix.length + ntlm.length + suffix.length];
            System.arraycopy(prefix, 0, token, 0, prefix.length);
            System.arraycopy(ntlm, 0, token, prefix.length, ntlm.length);
            System.arraycopy(suffix, 0, token, prefix.length + ntlm.length, suffix.length);
            assertThat(AuthSchemeFilter.isNtlmToken("Negotiate " + b64(token))).isTrue();
        }
        byte[] kerberos = new byte[300];
        r.nextBytes(kerberos);
        kerberos[0] = 0x60; // GSS-API InitialContextToken tag, as a Kerberos AP-REQ inside SPNEGO starts
        assertThat(AuthSchemeFilter.isNtlmToken("Negotiate " + b64(kerberos))).isFalse();
        assertThat(AuthSchemeFilter.isNtlmToken("Negotiate")).isFalse();
        assertThat(AuthSchemeFilter.isNtlmToken("Negotiate !!!not-base64!!!")).isFalse();
        assertThat(AuthSchemeFilter.indexOf(new byte[] {1, 2, 3}, new byte[] {2, 3})).isEqualTo(1);
        assertThat(AuthSchemeFilter.indexOf(new byte[] {1, 2}, new byte[] {2, 3})).isEqualTo(-1);
    }

    @Test
    void rateLimiterBlocksAfterTheLimitWithinTheWindow() {
        Random r = Seeds.random("rate");
        int limit = 1 + r.nextInt(6);
        Duration window = Duration.ofMinutes(1 + r.nextInt(10));
        MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
        RateLimiter rl = new RateLimiter(limit, window, clock);
        assertThat(rl.blocked("u")).isFalse();
        assertThat(rl.retryAfter("u")).isZero();
        for (int i = 0; i < limit - 1; i++) {
            rl.recordFailure("u");
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(rl.blocked("u")).isFalse();
        assertThat(rl.retryAfter("u")).isZero();
        rl.recordFailure("u");
        assertThat(rl.blocked("u")).isTrue();
        assertThat(rl.blocked("other")).isFalse();
        assertThat(rl.retryAfter("u")).isPositive().isLessThanOrEqualTo(window);
        clock.advance(window);
        assertThat(rl.blocked("u")).isFalse();
        rl.recordFailure("u");
        rl.reset("u");
        assertThat(rl.blocked("u")).isFalse();
        assertThatThrownBy(() -> new RateLimiter(0, window, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void originsAndTheirNormalisation() {
        assertThat(CsrfFilter.originsFor("PGWEB.corp.example", 443, true)).containsExactly("https://pgweb.corp.example");
        assertThat(CsrfFilter.originsFor("pgweb", 8443, true)).containsExactly("https://pgweb:8443");
        assertThat(CsrfFilter.originsFor("localhost", 80, false)).containsExactly("http://localhost");
        assertThat(CsrfFilter.originsFor("localhost", 0, false)).containsExactly("http://localhost");
        assertThat(CsrfFilter.normaliseOrigin("HTTPS://PGWEB.corp.example:443")).isEqualTo("https://pgweb.corp.example");
        assertThat(CsrfFilter.normaliseOrigin("http://localhost:80")).isEqualTo("http://localhost");
        assertThat(CsrfFilter.normaliseOrigin("http://localhost:8080")).isEqualTo("http://localhost:8080");
        assertThat(CsrfFilter.normaliseOrigin("null")).isNull();
        assertThat(CsrfFilter.normaliseOrigin("http://evil.example/path")).isNull();
        assertThat(CsrfFilter.normaliseOrigin("http://[bad")).isNull();
    }

    @Test
    void sessionTokensAreRandomAndUrlSafe() {
        String a = SessionFilter.newToken();
        String b = SessionFilter.newToken();
        assertThat(a).isNotEqualTo(b).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void userContextSplitsTheAccountName() {
        UserContext u = new UserContext("corp\\alice", "S-1-5-21-1", java.util.Set.of(), false, "Kerberos", "h", Instant.EPOCH, "127.0.0.1");
        assertThat(u.domain()).isEqualTo("CORP");
        assertThat(u.samAccountName()).isEqualTo("alice");
        UserContext local = new UserContext("alice", "S-1-5-21-1", java.util.Set.of(), false, "NTLM", "h", Instant.EPOCH, "127.0.0.1");
        assertThat(local.domain()).isEmpty();
        assertThat(local.samAccountName()).isEqualTo("alice");
    }
}
