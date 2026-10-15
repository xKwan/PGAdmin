package pgweb.testsupport;

import pgweb.Application;
import pgweb.audit.AuditMigrator;
import pgweb.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * One migrated throwaway cluster per test class, and any number of application instances against it with different
 * settings. Closes everything in reverse order.
 */
public final class TestApps implements AutoCloseable {

    private final PgTestCluster pg;
    private final Path tmp;
    private final List<Application> apps = new ArrayList<>();
    private int n;

    private TestApps(PgTestCluster pg, Path tmp) {
        this.pg = pg;
        this.tmp = tmp;
    }

    public static TestApps start(Path tmp) throws Exception {
        PgTestCluster pg = PgTestCluster.start();
        try {
            pg.bootstrapAudit();
            AuditMigrator.migrate(pg.jdbcUrl("pgweb_audit"), "pgweb_migrator", pg.migratorPassword(), "pgweb_app");
            return new TestApps(pg, tmp);
        } catch (Exception e) {
            pg.close();
            throw e;
        }
    }

    public PgTestCluster pg() {
        return pg;
    }

    /** Starts an application; {@code settings} adjusts the test configuration. */
    public synchronized Application app(Consumer<Properties> settings, Application.Options options) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app" + (++n)));
        Properties p = TestConfig.forCluster(pg, "pgweb_audit", dir);
        settings.accept(p);
        Config cfg = TestConfig.load(p);
        Application a = Application.start(cfg, options);
        apps.add(a);
        return a;
    }

    /** Audit rows of a type, newest first, as column maps (read as superuser). */
    public List<Map<String, Object>> events(String type, String userSid) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = pg.superuser("pgweb_audit");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM audit.event WHERE event_type = ? AND (?::text IS NULL OR user_sid = ?) ORDER BY seq DESC")) {
            ps.setString(1, type);
            ps.setString(2, userSid);
            ps.setString(3, userSid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** Makes the audit DB unreachable for the runtime role (true) or reachable again (false). */
    public void auditDown(boolean down) throws Exception {
        try (Connection c = pg.superuser("postgres"); var s = c.createStatement()) {
            s.execute("ALTER ROLE pgweb_app " + (down ? "NOLOGIN" : "LOGIN"));
            if (down) {
                s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'pgweb_app'");
            }
        }
    }

    @Override
    public synchronized void close() {
        for (int i = apps.size() - 1; i >= 0; i--) {
            apps.get(i).close();
        }
        pg.close();
    }
}
