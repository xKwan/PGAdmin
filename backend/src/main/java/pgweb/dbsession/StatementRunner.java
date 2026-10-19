package pgweb.dbsession;

import pgweb.security.UserContext;

import java.util.Map;

/**
 * What the session endpoints need from the execution engine ({@code POST /api/db-sessions/{id}/execute},
 * {@code /script}, {@code /fetch}, {@code /cancel} and {@code GET /activity}), so the session package doesn't depend on the execution package.
 */
public interface StatementRunner {

    Map<String, Object> execute(UserContext user, String sessionId, String sql, boolean confirmed, String requestId);

    /** Runs a script: its statements one at a time, stopping at the first that doesn't succeed (EXE-03). */
    Map<String, Object> script(UserContext user, String sessionId, String sql, boolean confirmed, String requestId);

    /** The next page of the last statement's rows, from the cursor kept inside the transaction (P9-3). */
    Map<String, Object> fetch(UserContext user, String sessionId, String executionId, String requestId);

    /** Begin, commit or rollback (manual-transaction controls). */
    Map<String, Object> transaction(UserContext user, String sessionId, String action, String isolation, boolean readOnly, String requestId);

    /** Cancels a running statement and waits briefly for it to end (before the session is closed). */
    void stopIfRunning(UserContext user, String sessionId);

    Map<String, Object> cancel(UserContext user, String sessionId);

    /** Whether a statement is running and what it waits for (lock-wait indicator). */
    Map<String, Object> activity(UserContext user, String sessionId);
}
