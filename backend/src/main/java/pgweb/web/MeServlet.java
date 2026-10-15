package pgweb.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import pgweb.config.Config;
import pgweb.security.SessionFilter;
import pgweb.security.UserContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/me}: who the server thinks you are and what you may do (plan Phase 4). The SPA uses
 * {@code capabilities} only to decide what to show; every action is authorised again on the server.
 */
public final class MeServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    private final transient Config config;

    public MeServlet(Config config) {
        this.config = config;
    }

    @Override
    protected Object get(HttpServletRequest req) {
        UserContext u = UserContext.of(req);
        if (u == null) {
            throw ApiException.unauthorized();
        }
        HttpSession session = req.getSession(false);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("account", u.account());
        user.put("sid", u.sid());
        user.put("dataAdmin", u.dataAdmin());
        user.put("authMechanism", u.mechanism());
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("internal", u.dataAdmin() && config.profiles().containsKey("internal"));
        caps.put("external", config.profiles().containsKey("external"));
        caps.put("executionEnabled", config.executionEnabled());
        Map<String, Object> sess = new LinkedHashMap<>();
        sess.put("loginAt", u.loginAt().toString());
        sess.put("expiresAt", u.loginAt().plus(config.auth().sessionAbsolute()).toString());
        sess.put("idleTimeoutSeconds", config.auth().sessionIdle().toSeconds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        out.put("capabilities", caps);
        out.put("session", sess);
        out.put("csrfToken", session == null ? null : session.getAttribute(SessionFilter.CSRF_TOKEN));
        return out;
    }
}
