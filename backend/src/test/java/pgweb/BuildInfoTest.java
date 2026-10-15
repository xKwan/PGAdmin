package pgweb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoTest {

    @Test
    void unpackagedClassesReportDevelopment() {
        assertThat(BuildInfo.versionOf(null)).isEqualTo(BuildInfo.UNKNOWN);
        assertThat(BuildInfo.versionOf(" ")).isEqualTo(BuildInfo.UNKNOWN);
        assertThat(BuildInfo.versionOf("1.2.3")).isEqualTo("1.2.3");
        assertThat(BuildInfo.version()).isEqualTo(BuildInfo.UNKNOWN);
    }

    @Test
    void describeNamesTheJavaRuntime() {
        assertThat(BuildInfo.describe()).startsWith("pgweb ").contains("Java " + Runtime.version().feature());
    }

    @Test
    void versionCommandPrintsTheDescription() {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(Main.run(new String[] {"version"}, out, out, null)).isEqualTo(Main.OK);
        assertThat(buf.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("pgweb");
    }
}
