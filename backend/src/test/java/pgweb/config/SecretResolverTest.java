package pgweb.config;

import com.sun.jna.platform.win32.Crypt32Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.testsupport.Seeds;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretResolverTest {

    @TempDir
    Path tmp;

    private static String randomSecret(Random r) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!$%&*+-=?@^_~";
        StringBuilder b = new StringBuilder();
        int n = 8 + r.nextInt(40);
        for (int i = 0; i < n; i++) {
            b.append(chars.charAt(r.nextInt(chars.length())));
        }
        return b.toString();
    }

    @Test
    void realDpapiRoundTripOnThisMachine() throws Exception {
        String secret = randomSecret(Seeds.random("dpapi"));
        byte[] blob = Crypt32Util.cryptProtectData(secret.getBytes(StandardCharsets.UTF_8));
        Secret s = new SecretResolver(Mode.PROD).resolve("audit.password", "dpapi:" + Base64.getEncoder().encodeToString(blob));
        assertThat(s.reveal()).isEqualTo(secret);
        assertThat(s.toString()).isEqualTo("Secret[***]").doesNotContain(secret);
        assertThat(s.length()).isEqualTo(secret.getBytes(StandardCharsets.UTF_8).length);
        assertThat(s.bytes()).isEqualTo(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void dpapiFailuresAreReportedWithoutTheValue() {
        SecretResolver r = new SecretResolver(Mode.PROD, k -> null, b -> {
            throw new IllegalStateException("bad blob");
        });
        assertThatThrownBy(() -> r.resolve("k", "dpapi:AAAA")).isInstanceOf(ConfigException.class).hasMessageContaining("DPAPI could not decrypt");
        assertThatThrownBy(() -> r.resolve("k", "dpapi:not base64!")).isInstanceOf(ConfigException.class).hasMessageContaining("not valid base64");
    }

    @Test
    void fileSecretUsesTheFirstLine() throws Exception {
        Random rnd = Seeds.random("file-secret");
        String secret = randomSecret(rnd);
        Path f = Files.writeString(tmp.resolve("s.txt"), secret + (rnd.nextBoolean() ? "\r\n" : "\n") + "ignored", StandardCharsets.UTF_8);
        assertThat(new SecretResolver(Mode.PROD).resolve("k", "file:" + f).reveal()).isEqualTo(secret);
        assertThatThrownBy(() -> new SecretResolver(Mode.PROD).resolve("k", "file:" + tmp.resolve("missing")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("cannot read secret file");
        Path empty = Files.writeString(tmp.resolve("e.txt"), "\n");
        assertThatThrownBy(() -> new SecretResolver(Mode.PROD).resolve("k", "file:" + empty))
                .isInstanceOf(ConfigException.class).hasMessageContaining("the secret is empty");
    }

    @Test
    void envSecret() throws Exception {
        SecretResolver r = new SecretResolver(Mode.PROD, SecretResolver.envOf(Map.of("X", "value")), b -> b);
        assertThat(r.resolve("k", "env:X").reveal()).isEqualTo("value");
        assertThatThrownBy(() -> r.resolve("k", "env:Y")).isInstanceOf(ConfigException.class).hasMessageContaining("Y is not set");
    }

    @Test
    void plainSecretsOnlyInTestMode() throws Exception {
        String secret = randomSecret(Seeds.random("plain"));
        assertThat(new SecretResolver(Mode.TEST).resolve("k", "plain:" + secret).reveal()).isEqualTo(secret);
        assertThatThrownBy(() -> new SecretResolver(Mode.PROD).resolve("k", "plain:" + secret))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("only allowed with pgweb.mode=test")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
    }

    @Test
    void missingOrUnknownReferences() {
        SecretResolver prod = new SecretResolver(Mode.PROD);
        assertThatThrownBy(() -> prod.resolve("audit.password", null)).hasMessageContaining("audit.password is required");
        assertThatThrownBy(() -> prod.resolve("audit.password", " ")).hasMessageContaining("is required");
        assertThatThrownBy(() -> prod.resolve("audit.password", "hunter2"))
                .hasMessageContaining("not a secret reference").satisfies(e -> assertThat(e.getMessage()).doesNotContain("hunter2"));
        assertThatThrownBy(() -> new SecretResolver(Mode.TEST).resolve("k", "vault:x")).hasMessageContaining("or plain:");
    }

    @Test
    void secretsCompareByValue() {
        Secret a = new Secret("x".getBytes(StandardCharsets.UTF_8));
        assertThat(a).isEqualTo(new Secret("x".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(new Secret(new byte[] {1}));
        assertThat(a.hashCode()).isEqualTo(new Secret("x".getBytes(StandardCharsets.UTF_8)).hashCode());
        assertThat(a.equals("x")).isFalse();
    }
}
