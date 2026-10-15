package pgweb.testsupport;

import pgweb.config.Config;
import pgweb.dbsession.DbConnector;
import pgweb.dbsession.SspiDelegatingConnector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Test scope only.</b> Stands in for Kerberos delegation so tests can act as several PostgreSQL roles on a
 * throwaway cluster: it logs on with a per-role password the test created. Same URL and connection settings as the
 * production connector. The application refuses a connector override in prod mode.
 */
public final class PasswordTestConnector implements DbConnector {

    private final Map<String, String> passwords = new ConcurrentHashMap<>();
    /** Roles whose next N connection attempts fail (to test degraded companions and failures). */
    private final Map<String, Integer> failNext = new ConcurrentHashMap<>();
    /** Roles whose companion connection always fails (degraded sessions). */
    private final java.util.Set<String> failCompanion = ConcurrentHashMap.newKeySet();
    /** Requested role -> role actually used (to simulate a server mapping to another role). */
    private final Map<String, String> redirect = new ConcurrentHashMap<>();

    public void failCompanion(String role, boolean fail) {
        if (fail) {
            failCompanion.add(role);
        } else {
            failCompanion.remove(role);
        }
    }

    public void redirect(String requestedRole, String actualRole) {
        redirect.put(requestedRole, actualRole);
    }

    public void password(String role, String password) {
        passwords.put(role, password);
    }

    public void failNext(String role, int times) {
        failNext.put(role, times);
    }

    @Override
    public Connection open(Object credential, Config.DbProfile profile, String database, String role, String applicationName) throws SQLException {
        Integer f = failNext.get(role);
        if (f != null && f > 0) {
            failNext.put(role, f - 1);
            throw new SQLException("simulated connection failure", "08006");
        }
        if (failCompanion.contains(role) && "pgweb-companion".equals(applicationName)) {
            throw new SQLException("simulated companion failure: too many connections for role", "53300");
        }
        String actual = redirect.getOrDefault(role, role);
        Properties p = SspiDelegatingConnector.baseProperties(profile, actual, applicationName);
        p.setProperty("password", passwords.getOrDefault(actual, "no-such-password"));
        return DriverManager.getConnection(SspiDelegatingConnector.url(profile, database), p);
    }
}
