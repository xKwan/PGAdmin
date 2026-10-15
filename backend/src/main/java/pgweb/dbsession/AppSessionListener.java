package pgweb.dbsession;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import pgweb.audit.AuditService;

/**
 * When an app session ends (logout, idle timeout, absolute lifetime, invalidation), its DB sessions end too
 * (plan Phase 6: app-session expiry cascade).
 */
public final class AppSessionListener implements HttpSessionListener {

    private final DbSessionManager manager;
    private final AuditService audit;

    public AppSessionListener(DbSessionManager manager, AuditService audit) {
        this.manager = manager;
        this.audit = audit;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        manager.closeAllFor(audit.sessionHash(se.getSession().getId()), "app_session_ended");
    }
}
