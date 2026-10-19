package pgweb.metadata;

import jakarta.servlet.http.HttpServletRequest;
import pgweb.security.UserContext;
import pgweb.web.ApiException;
import pgweb.web.ApiServlet;

/**
 * Object browser endpoints (plan Phase 10). Names travel only as query parameters (never in the path, never in SQL):
 * <pre>
 *   GET /api/metadata/{session}/schemas[?all=true]
 *   GET /api/metadata/{session}/objects?schema=…[&amp;all=true]
 *   GET /api/metadata/{session}/relation?schema=…&amp;name=…
 *   GET /api/metadata/{session}/extensions
 *   GET /api/metadata/{session}/database | activity | sizes        (admin Phase 3, read-only)
 *   GET /api/metadata/{session}/roles[?all=true]                     (all: include predefined pg_* roles)
 *   GET /api/metadata/{session}/definition?kind=…&amp;schema=…&amp;name=…[&amp;arguments=…]
 * </pre>
 * The session must belong to the caller (404 otherwise); internal sessions re-check DataAdmin on every call.
 */
public final class MetadataServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;
    private final transient MetadataService metadata;
    private final transient AdminCatalog admin;

    public MetadataServlet(MetadataService metadata, AdminCatalog admin) {
        this.metadata = metadata;
        this.admin = admin;
    }

    @Override
    protected Object get(HttpServletRequest req) {
        UserContext user = UserContext.of(req);
        if (user == null) {
            throw ApiException.unauthorized();
        }
        String info = req.getPathInfo();
        String[] p = info == null ? new String[0] : info.substring(1).split("/", -1);
        if (p.length != 2) {
            throw ApiException.notFound();
        }
        boolean all = "true".equals(req.getParameter("all"));
        return switch (p[1]) {
            case "schemas" -> metadata.schemas(user, p[0], all);
            case "objects" -> metadata.objects(user, p[0], req.getParameter("schema"), all);
            case "relation" -> metadata.relation(user, p[0], req.getParameter("schema"), req.getParameter("name"));
            case "extensions" -> metadata.extensions(user, p[0]);
            case "database" -> admin.database(user, p[0]);
            case "activity" -> admin.activity(user, p[0]);
            case "sizes" -> admin.sizes(user, p[0]);
            case "roles" -> admin.roles(user, p[0], all);
            case "definition" -> admin.definition(user, p[0], req.getParameter("kind"), req.getParameter("schema"),
                    req.getParameter("name"), req.getParameter("arguments"));
            default -> throw ApiException.notFound();
        };
    }
}
