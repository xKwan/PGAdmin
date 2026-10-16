package pgweb.dbsession;

import jakarta.servlet.http.HttpServletRequest;
import pgweb.config.Config;
import pgweb.security.FreshNegotiateFilter;
import pgweb.security.UserContext;
import pgweb.web.ApiException;
import pgweb.web.ApiServlet;
import pgweb.web.RequestIdFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB-session endpoints (plan Phase 6). Request bodies are strict JSON: a client can't name a host, port, role or user
 * (the fields don't exist, so they are rejected as unknown: T09, SEC-05).
 * <pre>
 *   GET    /api/profiles                          servers this user may use
 *   POST   /api/profiles/{profile}/databases      databases the user may connect to (fresh Windows handshake)
 *   POST   /api/db-sessions/{profile}             open: {"database": "..."}  (fresh Windows handshake)
 *   GET    /api/db-sessions                       own sessions
 *   GET    /api/db-sessions/{id}                  one session
 *   DELETE /api/db-sessions/{id}                  close
 *   POST   /api/db-sessions/{id}/heartbeat        keep alive while the page is open
 *   POST   /api/db-sessions/{id}/execute          run one statement: {"sql": "..."}  (plan Phase 7)
 *   POST   /api/db-sessions/{id}/cancel           cancel the running statement
 *   GET    /api/db-sessions/{id}/activity         is a statement running, and is it waiting for a lock (Phase 8)
 * </pre>
 */
public final class DbSessionServlets {

    private DbSessionServlets() {
    }

    /** Body of an open request. */
    public record OpenRequest(String database) {
    }

    /** Body of an execute request. */
    public record ExecuteRequest(String sql) {
    }

    static UserContext user(HttpServletRequest req) {
        UserContext u = UserContext.of(req);
        if (u == null) {
            throw ApiException.unauthorized();
        }
        return u;
    }

    static Object credential(HttpServletRequest req) {
        return req.getAttribute(FreshNegotiateFilter.IDENTITY_ATTRIBUTE);
    }

    static String requestId(HttpServletRequest req) {
        Object r = req.getAttribute(RequestIdFilter.ATTRIBUTE);
        return r == null ? null : r.toString();
    }

    /** GET /api/profiles */
    public static final class Profiles extends ApiServlet {
        private static final long serialVersionUID = 1L;
        private final transient Config config;

        public Profiles(Config config) {
            this.config = config;
        }

        @Override
        protected Object get(HttpServletRequest req) {
            UserContext u = user(req);
            List<Map<String, Object>> out = config.profiles().values().stream()
                    .filter(p -> !p.requiresDataAdmin() || u.dataAdmin())
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .map(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", p.name());
                        m.put("displayName", p.displayName());
                        m.put("requiresDataAdmin", p.requiresDataAdmin());
                        return m;
                    }).toList();
            return Map.of("profiles", out);
        }
    }

    /** POST /api/profiles/{profile}/databases, for one fixed profile (so each route has a static access level). */
    public static final class Databases extends ApiServlet {
        private static final long serialVersionUID = 1L;
        private final transient DbSessionManager manager;
        private final String profile;

        public Databases(DbSessionManager manager, String profile) {
            this.manager = manager;
            this.profile = profile;
        }

        @Override
        protected Object post(HttpServletRequest req) {
            if (!"/databases".equals(req.getPathInfo())) {
                throw ApiException.notFound();
            }
            return Map.of("databases", manager.databases(user(req), profile, credential(req)));
        }
    }

    /** POST /api/db-sessions/{profile}, for one fixed profile. */
    public static final class Open extends ApiServlet {
        private static final long serialVersionUID = 1L;
        private final transient DbSessionManager manager;
        private final String profile;

        public Open(DbSessionManager manager, String profile) {
            this.manager = manager;
            this.profile = profile;
        }

        @Override
        protected Object post(HttpServletRequest req) {
            OpenRequest body = body(req, OpenRequest.class);
            DbSession s = manager.open(user(req), profile, body.database(), credential(req), requestId(req));
            status(req, 201);
            return s.view();
        }
    }

    /** GET/DELETE /api/db-sessions[/{id}], POST /api/db-sessions/{id}/heartbeat */
    public static final class Sessions extends ApiServlet {
        private static final long serialVersionUID = 1L;
        private final transient DbSessionManager manager;
        private final transient StatementRunner runner;
        private final long maxBody;

        public Sessions(DbSessionManager manager, StatementRunner runner, long maxSqlBytes) {
            this.manager = manager;
            this.runner = runner;
            // JSON may escape every byte of the text (at most 6 characters each); the gate checks the real size.
            this.maxBody = maxSqlBytes * 6 + 1024;
        }

        @Override
        protected long maxBodyBytes() {
            return maxBody;
        }

        private static String[] path(HttpServletRequest req) {
            String info = req.getPathInfo();
            if (info == null || info.equals("/")) {
                return new String[0];
            }
            return info.substring(1).split("/", -1);
        }

        @Override
        protected Object get(HttpServletRequest req) {
            String[] p = path(req);
            if (p.length == 0) {
                return Map.of("sessions", manager.list(user(req)).stream().map(DbSession::view).toList());
            }
            if (p.length == 1) {
                return manager.get(user(req), p[0]).view();
            }
            if (p.length == 2 && p[1].equals("activity")) {
                return runner.activity(user(req), p[0]);
            }
            throw ApiException.notFound();
        }

        @Override
        protected Object post(HttpServletRequest req) {
            String[] p = path(req);
            if (p.length == 2 && p[1].equals("heartbeat")) {
                DbSession s = manager.heartbeat(user(req), p[0]);
                return Map.of("state", s.state().name(), "transaction", s.transactionState());
            }
            if (p.length == 2 && p[1].equals("execute")) {
                ExecuteRequest body = body(req, ExecuteRequest.class);
                return runner.execute(user(req), p[0], body.sql(), requestId(req));
            }
            if (p.length == 2 && p[1].equals("cancel")) {
                return runner.cancel(user(req), p[0]);
            }
            throw ApiException.notFound();
        }

        @Override
        protected Object delete(HttpServletRequest req) {
            String[] p = path(req);
            if (p.length != 1) {
                throw ApiException.notFound();
            }
            manager.close(user(req), p[0]);
            return null;
        }
    }
}
