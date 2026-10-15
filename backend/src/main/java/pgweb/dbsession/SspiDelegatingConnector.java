package pgweb.dbsession;

import pgweb.config.Config;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.IWindowsImpersonationContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Production connector (ADR-0002, spike S7): impersonates the request's Windows identity on this thread only while
 * pgJDBC performs an SSPI (Kerberos) logon, then reverts. No password exists and nothing is kept: the connection stays
 * authenticated after the impersonation ends (S7-4). Across machines this needs constrained delegation from the
 * service account to {@code POSTGRES/<host>} (target-environment checklist K3).
 */
public final class SspiDelegatingConnector implements DbConnector {

    @Override
    public Connection open(Object credential, Config.DbProfile profile, String database, String role, String applicationName) throws SQLException {
        if (!(credential instanceof IWindowsIdentity identity)) {
            throw new SQLException("a delegated Windows identity is required to connect", "28000");
        }
        Properties p = baseProperties(profile, role, applicationName);
        p.setProperty("gsslib", "sspi");
        p.setProperty("sspiServiceClass", "POSTGRES");
        IWindowsImpersonationContext ctx = identity.impersonate();
        try {
            return DriverManager.getConnection(url(profile, database), p);
        } finally {
            ctx.revertToSelf();
        }
    }

    /** JDBC URL with a URL-encoded database name (host names are DNS names; validated in config, S7-3). */
    public static String url(Config.DbProfile profile, String database) {
        return "jdbc:postgresql://" + profile.host() + ":" + profile.port() + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Connection settings shared by every connector (ADR-0009 rules for pgJDBC). */
    public static Properties baseProperties(Config.DbProfile profile, String role, String applicationName) {
        Properties p = new Properties();
        p.setProperty("user", role);
        p.setProperty("ApplicationName", applicationName);
        p.setProperty("connectTimeout", "10");
        p.setProperty("loginTimeout", "15");
        p.setProperty("tcpKeepAlive", "true");
        p.setProperty("preferQueryMode", "extended"); // never simple (ADR-0009 J2)
        p.setProperty("reWriteBatchedInserts", "false");
        p.setProperty("sslmode", profile.sslMode());
        profile.sslRootCert().ifPresent(c -> p.setProperty("sslrootcert", c.toAbsolutePath().toString()));
        return p;
    }
}
