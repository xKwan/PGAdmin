package pgweb.audit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import pgweb.config.Config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * Connection pool for the audit database, as the INSERT-only runtime role (ADR-0005). The pool starts lazily and
 * never blocks startup: if the audit DB is down, the service still starts, reports not-ready, and refuses audited
 * operations (fail-closed, AUD-02).
 */
public final class AuditDatabase implements AutoCloseable {

    private final HikariDataSource ds;

    public AuditDatabase(Config.Audit audit) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("pgweb-audit");
        hc.setJdbcUrl(audit.jdbcUrl());
        hc.setUsername(audit.user());
        hc.setPassword(audit.password().reveal());
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(Duration.ofSeconds(3).toMillis());
        hc.setValidationTimeout(Duration.ofSeconds(2).toMillis());
        hc.setInitializationFailTimeout(-1);
        hc.setAutoCommit(true);
        hc.addDataSourceProperty("ApplicationName", "pgweb-audit");
        hc.addDataSourceProperty("connectTimeout", "3");
        hc.addDataSourceProperty("socketTimeout", "15");
        hc.addDataSourceProperty("tcpKeepAlive", "true");
        this.ds = new HikariDataSource(hc);
    }

    public Connection connection() throws SQLException {
        return ds.getConnection();
    }

    /** True if the audit DB answers within the pool's connection timeout. */
    public boolean ping() {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.setQueryTimeout(2);
            s.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Creates monthly partitions ahead of time (the app role may only create, never drop). */
    public int ensurePartitions(int monthsAhead) throws SQLException {
        try (Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT audit.ensure_partitions(?)")) {
            ps.setInt(1, monthsAhead);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Override
    public void close() {
        ds.close();
    }
}
