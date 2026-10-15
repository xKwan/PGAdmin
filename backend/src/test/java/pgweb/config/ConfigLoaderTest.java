package pgweb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.testsupport.Seeds;
import pgweb.testsupport.TestConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @TempDir
    Path tmp;

    /** A complete production configuration with every file reference pointing at a real file. */
    private Properties prod() throws IOException {
        Path ks = Files.writeString(tmp.resolve("server.p12"), "x");
        Path ca = Files.writeString(tmp.resolve("ca.pem"), "x");
        Properties p = new Properties();
        p.setProperty("pgweb.mode", "prod");
        p.setProperty("server.publicHost", "pgweb.corp.example");
        p.setProperty("server.tls.keystore", ks.toString());
        p.setProperty("server.tls.keystorePassword", "env:KS_PW");
        p.setProperty("auth.dataAdminSid", "S-1-5-21-1004336348-1177238915-682003330-1105");
        p.setProperty("auth.allowedDomains", "CORP");
        p.setProperty("audit.jdbcUrl", "jdbc:postgresql://audit.corp.example:5434/pgweb_audit");
        p.setProperty("audit.password", "env:AUDIT_PW");
        p.setProperty("audit.hmacKey", "env:HMAC");
        p.setProperty("audit.spoolDir", tmp.resolve("spool").toString());
        p.setProperty("profile.internal.host", "pg-internal.corp.example");
        p.setProperty("profile.internal.sslRootCert", ca.toString());
        p.setProperty("profile.external.host", "pg-external.corp.example");
        p.setProperty("profile.external.sslRootCert", ca.toString());
        return p;
    }

    private static SecretResolver prodSecrets() {
        return new SecretResolver(Mode.PROD, SecretResolver.envOf(Map.of(
                "KS_PW", "keystore-pw", "AUDIT_PW", "audit-pw", "HMAC", "0123456789abcdef0123456789abcdef!!")), b -> b);
    }

    @Test
    void validProductionConfigurationLoadsWithDefaults() throws Exception {
        Config c = ConfigLoader.load(prod(), prodSecrets());
        assertThat(c.mode()).isEqualTo(Mode.PROD);
        assertThat(c.server().port()).isEqualTo(8443);
        assertThat(c.server().tls()).isPresent();
        assertThat(c.auth().protocols()).containsExactly("Negotiate");
        assertThat(c.auth().kerberosOnly()).isTrue();
        assertThat(c.auth().allowedDomains()).containsExactly("CORP");
        assertThat(c.auth().sessionIdle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(c.auth().sessionAbsolute()).isEqualTo(Duration.ofHours(8));
        assertThat(c.profiles()).containsOnlyKeys("internal", "external");
        Config.DbProfile internal = c.profiles().get("internal");
        assertThat(internal.port()).isEqualTo(5433);
        assertThat(internal.requiresDataAdmin()).isTrue();
        assertThat(internal.sslMode()).isEqualTo("verify-full");
        assertThat(internal.statementTimeout()).isEqualTo(Duration.ofSeconds(300));
        Config.DbProfile external = c.profiles().get("external");
        assertThat(external.port()).isEqualTo(5432);
        assertThat(external.requiresDataAdmin()).isFalse();
        assertThat(external.statementTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(c.executionEnabled()).isFalse();
        Config.Limits l = c.limits();
        assertThat(l.maxRows()).isEqualTo(1000);
        assertThat(l.maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(l.maxCellBytes()).isEqualTo(64 * 1024);
        assertThat(l.sessionsPerUser()).isEqualTo(3);
        assertThat(l.sessionsGlobal()).isEqualTo(50);
        assertThat(l.heartbeatAbandon()).isEqualTo(Duration.ofSeconds(120));
        assertThat(c.audit().password().reveal()).isEqualTo("audit-pw");
        assertThat(c.audit().password().toString()).doesNotContain("audit-pw");
    }

    @Test
    void testModeConfigurationLoads() throws Exception {
        Config c = TestConfig.load(TestConfig.base(tmp));
        assertThat(c.mode()).isEqualTo(Mode.TEST);
        assertThat(c.server().tls()).isEmpty();
        assertThat(c.auth().protocols()).containsExactlyInAnyOrder("Negotiate", "NTLM");
        assertThat(c.auth().kerberosOnly()).isFalse();
        assertThat(c.server().bind()).isEqualTo("127.0.0.1");
    }

    /** One way to break a production configuration, and a fragment of the message it must produce. */
    private record Fault(String name, Consumer<Properties> apply, String expected) {
    }

    private List<Fault> faults() {
        List<Fault> f = new ArrayList<>();
        f.add(new Fault("unknown key", p -> p.setProperty("server.prot", "1"), "unknown setting server.prot"));
        f.add(new Fault("unknown profile key", p -> p.setProperty("profile.external.hots", "x"), "unknown setting profile.external.hots"));
        f.add(new Fault("unknown profile", p -> p.setProperty("profile.other.host", "x"), "unknown setting profile.other.host"));
        f.add(new Fault("bad mode", p -> p.setProperty("pgweb.mode", "staging"), "pgweb.mode must be prod or test"));
        f.add(new Fault("ntlm in prod", p -> p.setProperty("auth.protocols", "Negotiate NTLM"), "NTLM is not allowed"));
        f.add(new Fault("unknown protocol", p -> p.setProperty("auth.protocols", "Negotiate Digest"), "unknown protocol Digest"));
        f.add(new Fault("kerberosOnly off in prod", p -> p.setProperty("auth.kerberosOnly", "false"), "auth.kerberosOnly=false is not allowed"));
        f.add(new Fault("bad boolean", p -> p.setProperty("execution.enabled", "yes"), "execution.enabled must be true or false"));
        f.add(new Fault("missing sid", p -> p.remove("auth.dataAdminSid"), "auth.dataAdminSid is required"));
        f.add(new Fault("bad sid", p -> p.setProperty("auth.dataAdminSid", "Domain Admins"), "auth.dataAdminSid is not a SID"));
        f.add(new Fault("bad domain", p -> p.setProperty("auth.allowedDomains", "CORP, bad/domain"), "invalid domain name bad/domain"));
        f.add(new Fault("plain secret in prod", p -> p.setProperty("audit.password", "plain:hunter2"), "plain: secrets are only allowed"));
        f.add(new Fault("not a secret ref", p -> p.setProperty("audit.password", "hunter2"), "audit.password: not a secret reference"));
        f.add(new Fault("missing env secret", p -> p.setProperty("audit.hmacKey", "env:NOPE"), "environment variable NOPE is not set"));
        f.add(new Fault("missing tls", p -> p.remove("server.tls.keystore"), "server.tls.keystore is required"));
        f.add(new Fault("tls file missing", p -> p.setProperty("server.tls.keystore", tmp.resolve("nope.p12").toString()), "server.tls.keystore does not exist"));
        f.add(new Fault("ip public host", p -> p.setProperty("server.publicHost", "10.1.2.3"), "server.publicHost must be a DNS host name"));
        f.add(new Fault("missing public host", p -> p.remove("server.publicHost"), "server.publicHost is required"));
        f.add(new Fault("ip profile host", p -> p.setProperty("profile.external.host", "192.168.1.20"), "must be a DNS host name"));
        f.add(new Fault("ipv6 profile host", p -> p.setProperty("profile.internal.host", "::1"), "must be a DNS host name"));
        f.add(new Fault("weak ssl", p -> p.setProperty("profile.external.sslMode", "require"), "does not verify the host name"));
        f.add(new Fault("bad ssl", p -> p.setProperty("profile.external.sslMode", "prefer"), "sslMode must be one of"));
        f.add(new Fault("no root cert", p -> p.remove("profile.internal.sslRootCert"), "sslRootCert is required"));
        f.add(new Fault("internal not dataadmin", p -> p.setProperty("profile.internal.requiresDataAdmin", "false"), "requiresDataAdmin must be true"));
        f.add(new Fault("bad maintenance db", p -> p.setProperty("profile.external.maintenanceDb", "x; drop"), "not a valid database name"));
        f.add(new Fault("bad port", p -> p.setProperty("profile.external.port", "70000"), "must be between 1 and 65535"));
        f.add(new Fault("not a number", p -> p.setProperty("limits.maxRows", "lots"), "limits.maxRows must be a whole number"));
        f.add(new Fault("rows too high", p -> p.setProperty("limits.maxRows", "1000000"), "limits.maxRows must be between"));
        f.add(new Fault("bad jdbc url", p -> p.setProperty("audit.jdbcUrl", "jdbc:mysql://x/y"), "audit.jdbcUrl must start with jdbc:postgresql://"));
        f.add(new Fault("missing spool", p -> p.remove("audit.spoolDir"), "audit.spoolDir is required"));
        f.add(new Fault("idle over absolute", p -> {
            p.setProperty("auth.session.idleMinutes", "600");
            p.setProperty("auth.session.absoluteHours", "2");
        }, "must not exceed auth.session.absoluteHours"));
        f.add(new Fault("warning after timeout", p -> p.setProperty("limits.idleInTransactionWarningMinutes", "10"), "must be less than limits.idleInTransactionMinutes"));
        return f;
    }

    @Test
    void randomCombinationsOfFaultsAreAllReportedAtOnce() throws Exception {
        Random rnd = Seeds.random("config-faults");
        List<Fault> all = faults();
        for (int round = 0; round < 25; round++) {
            List<Fault> pick = new ArrayList<>(all);
            Collections.shuffle(pick, rnd);
            // Faults that change the same key can't be combined; keep the first of each key group.
            List<Fault> chosen = new ArrayList<>();
            List<String> touched = new ArrayList<>();
            int n = 1 + rnd.nextInt(5);
            for (Fault f : pick) {
                Properties probe = prod();
                Properties before = (Properties) probe.clone();
                f.apply.accept(probe);
                List<String> keys = new ArrayList<>();
                for (String k : probe.stringPropertyNames()) {
                    if (!probe.getProperty(k).equals(before.getProperty(k))) {
                        keys.add(k);
                    }
                }
                for (String k : before.stringPropertyNames()) {
                    if (!probe.containsKey(k)) {
                        keys.add(k);
                    }
                }
                if (keys.stream().anyMatch(touched::contains) || (f.name.equals("bad mode") && !chosen.isEmpty())) {
                    continue;
                }
                if (!chosen.isEmpty() && chosen.get(0).name.equals("bad mode")) {
                    break;
                }
                touched.addAll(keys);
                chosen.add(f);
                if (chosen.size() == n) {
                    break;
                }
            }
            Properties p = prod();
            chosen.forEach(f -> f.apply.accept(p));
            List<String> names = chosen.stream().map(Fault::name).toList();
            assertThatThrownBy(() -> ConfigLoader.load(p, prodSecrets()))
                    .as("faults %s", names)
                    .isInstanceOfSatisfying(ConfigException.class, e -> {
                        for (Fault f : chosen) {
                            assertThat(e.problems()).as("fault %s in %s", f.name, names).anyMatch(m -> m.contains(f.expected));
                        }
                    });
        }
    }

    @Test
    void dnsNamesAreCheckedLabelByLabel() {
        assertThat(ConfigLoader.isDnsName("pg-internal.corp.example")).isTrue();
        assertThat(ConfigLoader.isDnsName("localhost")).isTrue();
        assertThat(ConfigLoader.isDnsName("a".repeat(63) + ".b")).isTrue();
        assertThat(ConfigLoader.isDnsName("a".repeat(64) + ".b")).isFalse();
        assertThat(ConfigLoader.isDnsName(("a".repeat(60) + ".").repeat(5) + "b")).isFalse(); // over 253
        for (String bad : new String[] {"", ".", "a..b", ".a", "a.", "-a", "a-", "a_b", "a b", "ä.example"}) {
            assertThat(ConfigLoader.isDnsName(bad)).as(bad).isFalse();
        }
    }

    @Test
    void sessionLimitsMustBeConsistent() throws Exception {
        Properties p = prod();
        p.setProperty("limits.sessionsPerUser", "40");
        p.setProperty("limits.sessionsGlobal", "20");
        assertThatThrownBy(() -> ConfigLoader.load(p, prodSecrets()))
                .isInstanceOfSatisfying(ConfigException.class,
                        e -> assertThat(e.problems()).anyMatch(m -> m.contains("limits.sessionsPerUser must not exceed limits.sessionsGlobal")));
    }

    @Test
    void cellLimitMustFitTheResponseLimit() throws Exception {
        Properties p = prod();
        p.setProperty("limits.maxCellBytes", "16000000");
        p.setProperty("limits.maxResponseBytes", "1000000");
        assertThatThrownBy(() -> ConfigLoader.load(p, prodSecrets()))
                .isInstanceOfSatisfying(ConfigException.class,
                        e -> assertThat(e.problems()).anyMatch(m -> m.contains("limits.maxCellBytes must not exceed")));
    }

    @Test
    void shortHmacKeyIsRejected() throws Exception {
        Properties p = prod();
        p.setProperty("audit.hmacKey", "env:SHORT");
        SecretResolver s = new SecretResolver(Mode.PROD, SecretResolver.envOf(Map.of("KS_PW", "k", "AUDIT_PW", "a", "SHORT", "tooshort")), b -> b);
        assertThatThrownBy(() -> ConfigLoader.load(p, s))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems()).anyMatch(m -> m.contains("at least 32 bytes")));
    }

    @Test
    void atLeastOneProfileIsRequired() throws Exception {
        Properties p = prod();
        p.stringPropertyNames().stream().filter(k -> k.startsWith("profile.")).forEach(p::remove);
        assertThatThrownBy(() -> ConfigLoader.load(p, prodSecrets()))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems()).anyMatch(m -> m.contains("at least one PostgreSQL profile")));
    }

    @Test
    void missingModeIsReported() throws Exception {
        Properties p = prod();
        p.remove("pgweb.mode");
        assertThatThrownBy(() -> ConfigLoader.load(p, prodSecrets()))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems()).anyMatch(m -> m.contains("pgweb.mode is required")));
    }

    @Test
    void optionalSettingsAreParsed() throws Exception {
        Properties p = prod();
        p.setProperty("audit.migrator.user", "pgweb_migrator");
        p.setProperty("audit.migrator.password", "env:AUDIT_PW");
        p.setProperty("execution.enabled", "TRUE");
        p.setProperty("profile.external.sslMode", "disable");
        p.remove("profile.external.sslRootCert");
        p.setProperty("profile.external.displayName", "Analytics");
        p.setProperty("limits.maxRows", "250");
        p.setProperty("auth.protocols", "negotiate");
        Config c = ConfigLoader.load(p, prodSecrets());
        assertThat(c.audit().migratorUser()).contains("pgweb_migrator");
        assertThat(c.audit().migratorPassword()).isPresent();
        assertThat(c.executionEnabled()).isTrue();
        assertThat(c.profiles().get("external").sslMode()).isEqualTo("disable");
        assertThat(c.profiles().get("external").displayName()).isEqualTo("Analytics");
        assertThat(c.limits().maxRows()).isEqualTo(250);
    }

    @Test
    void loadsFromAFileAndReportsUnreadableFiles() throws Exception {
        Properties p = TestConfig.base(tmp);
        Path f = TestConfig.write(p, tmp.resolve("pgweb.properties"));
        assertThat(ConfigLoader.load(f).mode()).isEqualTo(Mode.TEST);
        assertThatThrownBy(() -> ConfigLoader.load(tmp.resolve("missing.properties")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("cannot read configuration file");
    }
}
