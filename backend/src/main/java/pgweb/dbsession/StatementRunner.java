package pgweb.dbsession;

import pgweb.security.UserContext;

import java.util.Map;

/**
 * What the session endpoints need from the execution engine ({@code POST /api/db-sessions/{id}/execute},
 * {@code /cancel} and {@code GET /activity}), so the session package doesn't depend on the execution package.
 */
public interface StatementRunner {

    Map<String, Object> execute(UserContext user, String sessionId, String sql, String requestId);

    Map<String, Object> cancel(UserContext user, String sessionId);

    /** Whether a statement is running and what it waits for (lock-wait indicator). */
    Map<String, Object> activity(UserContext user, String sessionId);
}
