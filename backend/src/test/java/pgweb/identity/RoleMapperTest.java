package pgweb.identity;

import org.junit.jupiter.api.Test;
import pgweb.config.ConfigException;
import pgweb.config.ConfigLoader;
import pgweb.security.UserContext;
import pgweb.testsupport.TestConfig;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleMapperTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    static UserContext user(String account) {
        return new UserContext(account, "S-1-5-21-1", Set.of(), false, "Kerberos", "h", Instant.EPOCH, "127.0.0.1");
    }

    @Test
    void defaultIsTheLowerCasedAccountName() {
        RoleMapper m = new RoleMapper(true, Map.of());
        assertThat(m.roleFor(user("CORP\\JSmith"))).isEqualTo("jsmith");
        assertThat(m.roleFor(user("jsmith"))).isEqualTo("jsmith");
        assertThat(new RoleMapper(false, Map.of()).roleFor(user("CORP\\JSmith"))).isEqualTo("JSmith");
    }

    @Test
    void overridesWinAndAreCaseInsensitiveOnTheAccount() {
        RoleMapper m = new RoleMapper(true, Map.of("CORP\\JSMITH", "john_smith"));
        assertThat(m.roleFor(user("corp\\jsmith"))).isEqualTo("john_smith");
        assertThat(m.roleFor(user("OTHER\\jsmith"))).isEqualTo("jsmith");
    }

    @Test
    void accountsThatAreNotPlainIdentifiersAreRefused() {
        RoleMapper m = new RoleMapper(true, Map.of());
        for (String bad : new String[] {"CORP\\john smith", "CORP\\x\"; drop", "CORP\\" + "a".repeat(64), "CORP\\1abc", "CORP\\"}) {
            assertThatThrownBy(() -> m.roleFor(user(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void identitySettingsAreParsedAndValidated() throws Exception {
        Properties p = TestConfig.base(Files.createDirectories(tmp.resolve("a")));
        p.setProperty("identity.roleCase", "asis");
        p.setProperty("identity.roleOverrides", "CORP\\jsmith=john_smith; corp\\ann = ann_b ;");
        var cfg = ConfigLoader.load(p, null);
        assertThat(cfg.identity().lowerCase()).isFalse();
        assertThat(cfg.identity().overrides()).containsEntry("CORP\\JSMITH", "john_smith").containsEntry("CORP\\ANN", "ann_b");
        p.setProperty("identity.roleCase", "upper");
        p.setProperty("identity.roleOverrides", "jsmith=john;CORP\\x=bad role");
        assertThatThrownBy(() -> ConfigLoader.load(p, null)).isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems())
                .anyMatch(x -> x.contains("identity.roleCase must be lower or asis"))
                .anyMatch(x -> x.contains("not jsmith=john"))
                .anyMatch(x -> x.contains("not CORP\\x=bad role")));
        Properties d = TestConfig.base(Files.createDirectories(tmp.resolve("b")));
        assertThat(ConfigLoader.load(d, null).identity().lowerCase()).isTrue();
    }
}
