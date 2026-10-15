package pgweb.dbsession;

import pgweb.config.Config;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens one PostgreSQL connection as the user. Production uses {@link SspiDelegatingConnector} (Kerberos constrained
 * delegation, ADR-0002); tests may supply another implementation, which the application refuses in prod mode.
 */
public interface DbConnector {

    /**
     * @param credential the delegated Windows identity of this request ({@code IWindowsIdentity}) in production;
     *                   whatever the test connector needs in tests. Never stored.
     */
    Connection open(Object credential, Config.DbProfile profile, String database, String role, String applicationName) throws SQLException;
}
