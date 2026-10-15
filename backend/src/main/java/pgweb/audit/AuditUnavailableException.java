package pgweb.audit;

/**
 * A required audit record could not be written and the fail mode is closed (AUD-02): the operation that needed it
 * must not happen. The API turns this into {@code 503 AUDIT_UNAVAILABLE}.
 */
public final class AuditUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
