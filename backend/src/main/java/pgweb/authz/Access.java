package pgweb.authz;

/**
 * Who may call an endpoint. Every route declares one when it is registered; a route without a declaration is denied
 * ({@link AccessFilter}), so a new endpoint can't be exposed by forgetting a check. The authorisation-matrix test
 * iterates the same declarations.
 */
public enum Access {
    /** No Windows authentication (health and readiness only). */
    PUBLIC,
    /** Any authenticated, allowed domain user. */
    AUTHENTICATED,
    /** Members of the DataAdmin AD group only (internal PostgreSQL, F3). */
    DATA_ADMIN
}
