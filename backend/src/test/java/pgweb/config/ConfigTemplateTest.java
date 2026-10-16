package pgweb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped production template (deploy/windows/pgweb.properties.template, plan Phase 12) is a valid prod
 * configuration once its placeholders are filled in, and keeps execution switched off.
 */
class ConfigTemplateTest {

    @TempDir
    Path tmp;

    Properties filled() throws Exception {
        String text = Files.readString(Path.of("..", "deploy", "windows", "pgweb.properties.template"), StandardCharsets.UTF_8);
        Path ks = Files.writeString(tmp.resolve("pgweb.p12"), "keystore");
        Path ca = Files.writeString(tmp.resolve("root.crt"), "ca");
        String esc = tmp.toString().replace("\\", "\\\\");
        text = text.replace("C:\\\\ProgramData\\\\pgweb\\\\config\\\\pgweb.p12", ks.toString().replace("\\", "\\\\"))
                .replace("C:\\\\ProgramData\\\\pgweb\\\\config\\\\root.crt", ca.toString().replace("\\", "\\\\"))
                .replace("C:\\\\ProgramData\\\\pgweb", esc)
                .replace("dpapi:<...>", "dpapi:" + java.util.Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)))
                .replace("<pgweb.corp.example>", "pgweb.corp.example").replace("<S-1-5-21-...>", "S-1-5-21-1-2-3-4567")
                .replace("<CORP>", "CORP").replace("<audit-db-host>", "auditdb.corp.example")
                .replace("<internal-pg.corp.example>", "pg-internal.corp.example").replace("<external-pg.corp.example>", "pg-external.corp.example");
        assertThat(text.lines().filter(l -> !l.startsWith("#") && l.contains("<"))).as("placeholders left").isEmpty();
        Properties p = new Properties();
        try (Reader r = new StringReader(text)) {
            p.load(r);
        }
        return p;
    }

    static SecretResolver fakeDpapi() {
        // A 40-byte key for any dpapi: value (the real one is decrypted with Crypt32 on the server).
        return new SecretResolver(Mode.PROD, k -> null, blob -> "0123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theFilledTemplateIsAValidProductionConfiguration() throws Exception {
        Config c = ConfigLoader.load(filled(), fakeDpapi());
        assertThat(c.mode()).isEqualTo(Mode.PROD);
        assertThat(c.executionEnabled()).as("execution stays off until G4").isFalse();
        assertThat(c.auth().kerberosOnly()).isTrue();
        assertThat(c.auth().protocols()).containsExactly("Negotiate");
        assertThat(c.audit().failClosed()).isTrue();
        assertThat(c.server().tls()).isPresent();
        assertThat(c.profiles()).containsKeys("internal", "external");
        assertThat(c.profiles().get("internal").requiresDataAdmin()).isTrue();
        assertThat(c.profiles().get("internal").sslMode()).isEqualTo("verify-full");
        assertThat(c.profiles().get("external").sslMode()).isEqualTo("verify-full");
        assertThat(c.profiles().get("internal").bannerText()).contains("INTERNAL");
    }

    @Test
    void anUnfilledTemplateIsRefused() throws Exception {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of("..", "deploy", "windows", "pgweb.properties.template"), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        assertThatThrownBy(() -> ConfigLoader.load(p, fakeDpapi())).isInstanceOf(ConfigException.class);
    }
}
