package pgweb.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Validated, immutable service configuration. Built only by {@link ConfigLoader}. */
public record Config(
        Mode mode,
        Server server,
        Auth auth,
        Audit audit,
        Map<String, DbProfile> profiles,
        Limits limits,
        boolean executionEnabled,
        Path logDir) {

    public record Server(String bind, int port, String publicHost, Optional<Tls> tls, Path workDir) {
    }

    public record Tls(Path keystore, Secret keystorePassword) {
    }

    /**
     * @param protocols      Waffle Negotiate provider protocols: {@code Negotiate} only in production; {@code NTLM}
     *                       additionally in test mode (no domain on the workstation)
     * @param kerberosOnly   refuse NTLM tokens, including NTLM negotiated inside SPNEGO (ADR-0002)
     * @param dataAdminSid   SID of the DataAdmin AD group
     * @param allowedDomains NetBIOS domain names whose users may sign in (upper case); empty = any
     */
    public record Auth(Set<String> protocols, boolean kerberosOnly, String dataAdminSid, Set<String> allowedDomains,
                       Duration sessionIdle, Duration sessionAbsolute) {
    }

    public record Audit(String jdbcUrl, String user, Secret password, Secret hmacKey, Path spoolDir,
                        Optional<String> migratorUser, Optional<Secret> migratorPassword) {
    }

    /**
     * A server-side PostgreSQL target (ADR-0003: never chosen by the client).
     *
     * @param requiresDataAdmin true for the internal instance (5433)
     */
    public record DbProfile(String name, String displayName, String host, int port, String sslMode,
                            Optional<Path> sslRootCert, String maintenanceDb, boolean requiresDataAdmin,
                            Duration statementTimeout) {
    }

    /** Execution and session limits (ADR-0006). All are enforced on the server. */
    public record Limits(int maxRows, long maxResponseBytes, int maxCellBytes, long maxSqlBytes,
                         Duration cancelGrace, Duration idleSession, Duration idleInTransaction,
                         Duration idleInTransactionWarning, Duration maxTransactionAge, Duration heartbeatAbandon,
                         int sessionsPerUser, int sessionsGlobal, int failedOpensPerWindow, Duration failedOpenWindow) {
    }
}
