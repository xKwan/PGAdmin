package pgweb.savedqueries;

import jakarta.servlet.http.HttpServletRequest;
import pgweb.security.UserContext;
import pgweb.web.ApiException;
import pgweb.web.ApiServlet;

import java.util.Map;

/**
 * Saved queries of the signed-in account (admin Phase 11):
 * <pre>
 *   GET    /api/saved-queries            list (no text)
 *   GET    /api/saved-queries/{id}       one, with its text
 *   POST   /api/saved-queries            {name, text}: create
 *   PUT    /api/saved-queries/{id}       {name, text}: replace
 *   DELETE /api/saved-queries/{id}
 * </pre>
 * Another account's ID is "not found". Nothing here runs SQL.
 */
public final class SavedQueriesServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;
    private final transient SavedQueries store;

    /** Body of POST and PUT. */
    public record Body(String name, String text) {
    }

    public SavedQueriesServlet(SavedQueries store) {
        this.store = store;
    }

    /** Room for a query of the largest size, even if every character is escaped in JSON. */
    @Override
    protected long maxBodyBytes() {
        return 1024L * 1024;
    }

    private static UserContext user(HttpServletRequest req) {
        UserContext u = UserContext.of(req);
        if (u == null) {
            throw ApiException.unauthorized();
        }
        return u;
    }

    /** The ID in the path, or null for the collection itself. */
    private static String id(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.equals("/")) {
            return null;
        }
        String id = info.substring(1);
        if (id.contains("/")) {
            throw ApiException.notFound();
        }
        return id;
    }

    @Override
    protected Object get(HttpServletRequest req) {
        String id = id(req);
        return id == null ? store.list(user(req)) : store.get(user(req), id);
    }

    @Override
    protected Object post(HttpServletRequest req) {
        if (id(req) != null) {
            throw ApiException.notFound();
        }
        Body b = body(req, Body.class);
        Map<String, Object> saved = store.save(user(req), null, b.name(), b.text());
        status(req, 201);
        return saved;
    }

    @Override
    protected Object put(HttpServletRequest req) {
        String id = id(req);
        if (id == null) {
            throw ApiException.notFound();
        }
        Body b = body(req, Body.class);
        return store.save(user(req), id, b.name(), b.text());
    }

    @Override
    protected Object delete(HttpServletRequest req) {
        String id = id(req);
        if (id == null) {
            throw ApiException.notFound();
        }
        store.delete(user(req), id);
        return Map.of("deleted", id);
    }
}
