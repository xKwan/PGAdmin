package pgweb.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * {@code GET /api/health}: liveness (the process answers). {@code GET /api/ready}: readiness (the audit DB is
 * reachable, so audited operations can run). Neither reveals versions or internals.
 */
public final class HealthServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    private final transient BooleanSupplier ready;

    /** @param ready null for the liveness endpoint; the readiness check otherwise */
    public HealthServlet(BooleanSupplier ready) {
        this.ready = ready;
    }

    @Override
    protected Object get(HttpServletRequest req) {
        if (ready == null) {
            return Map.of("status", "UP");
        }
        if (!ready.getAsBoolean()) {
            throw ApiException.unavailable("AUDIT_UNAVAILABLE", "The audit database is not reachable");
        }
        return Map.of("status", "READY");
    }
}
