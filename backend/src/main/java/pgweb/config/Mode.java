package pgweb.config;

/**
 * Operating mode. {@link #PROD} applies every production rule: TLS on, Kerberos-only Negotiate, no literal secrets,
 * DNS host names for PostgreSQL. {@link #TEST} relaxes them for the workstation and CI, which have no domain.
 */
public enum Mode {
    PROD,
    TEST
}
