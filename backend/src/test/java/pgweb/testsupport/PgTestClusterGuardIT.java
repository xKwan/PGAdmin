package pgweb.testsupport;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The test-cluster safety guard (plan §7.4): tests refuse any server they did not create. */
class PgTestClusterGuardIT {

    @Test
    void guardRefusesAClusterWhoseMarkerDoesNotMatch() throws Exception {
        try (PgTestCluster pg = PgTestCluster.start()) {
            pg.assertOwned();
            pg.corruptMarkerForTest();
            assertThatThrownBy(pg::assertOwned).hasMessageContaining("marker token mismatch");
            assertThatThrownBy(() -> pg.superuser("postgres")).hasMessageContaining("Safety guard");
        }
    }

    @Test
    void guardRefusesAServerWithoutThisRunsToken() throws Exception {
        try (PgTestCluster pg = PgTestCluster.start()) {
            try (Connection c = pg.superuser("postgres"); Statement s = c.createStatement()) {
                s.execute("ALTER SYSTEM SET pgweb.test_run_token = 'someone-elses-server'");
            }
            // Same port and credentials, but the server no longer carries this run's token: what a foreign server
            // that happens to listen on the expected port looks like.
            pg.stop();
            pg.restartWithoutTokenForTest();
            assertThatThrownBy(pg::assertOwned).hasMessageContaining("server run token mismatch");
            assertThatThrownBy(() -> pg.superuser("postgres")).hasMessageContaining("Safety guard");
        }
    }

    @Test
    void clusterIsRemovedAfterClose() throws Exception {
        PgTestCluster pg = PgTestCluster.start();
        var root = pg.root();
        assertThat(Files.exists(root)).isTrue();
        pg.close();
        assertThat(Files.exists(root)).isFalse();
        pg.close(); // idempotent
    }

    @Test
    void stopAndRestartKeepTheGuard() throws Exception {
        try (PgTestCluster pg = PgTestCluster.start()) {
            pg.stop();
            assertThatThrownBy(() -> pg.connect(PgTestCluster.SUPERUSER, pg.superPassword(), "postgres")).isInstanceOf(java.sql.SQLException.class);
            pg.restart();
            pg.assertOwned();
        }
    }
}
