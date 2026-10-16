package pgweb.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Base class for every JSON endpoint. Subclasses override {@link #get}, {@link #post}, {@link #put} or
 * {@link #delete} and return the response object (serialised as JSON) or throw {@link ApiException}.
 * <ul>
 *   <li>Request bodies must be {@code application/json} and at most {@link #maxBodyBytes()}; parsing is strict
 *       ({@link Json}).</li>
 *   <li>Unexpected exceptions become a 500 envelope; the details go only to the log, tagged with the request ID.</li>
 * </ul>
 */
public abstract class ApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ApiServlet.class);

    /** Status for responses that return a value; 200 unless the handler sets another. */
    public static final String STATUS_ATTRIBUTE = "pgweb.status";

    protected Object get(HttpServletRequest req) {
        throw ApiException.methodNotAllowed();
    }

    protected Object post(HttpServletRequest req) {
        throw ApiException.methodNotAllowed();
    }

    protected Object put(HttpServletRequest req) {
        throw ApiException.methodNotAllowed();
    }

    protected Object delete(HttpServletRequest req) {
        throw ApiException.methodNotAllowed();
    }

    /** Maximum accepted request body. */
    protected long maxBodyBytes() {
        return 64L * 1024;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            Object result = switch (req.getMethod()) {
                case "GET" -> get(req);
                case "POST" -> post(req);
                case "PUT" -> put(req);
                case "DELETE" -> delete(req);
                default -> throw ApiException.methodNotAllowed();
            };
            Object status = req.getAttribute(STATUS_ATTRIBUTE);
            resp.setStatus(status instanceof Integer s ? s : (result == null ? 204 : 200));
            Responses.json(resp);
            if (result != null) {
                resp.getOutputStream().write(Json.write(result).getBytes(StandardCharsets.UTF_8));
            }
        } catch (ApiException e) {
            ErrorEnvelope.write(resp, e.status(), e.code(), e.getMessage(), e.details());
        } catch (pgweb.audit.AuditUnavailableException e) {
            ErrorEnvelope.write(resp, 503, "AUDIT_UNAVAILABLE", "The audit database is unavailable; the operation was not performed");
        } catch (RuntimeException e) {
            LOG.error("unhandled error in {} {}", req.getMethod(), req.getRequestURI(), e);
            ErrorEnvelope.write(resp, 500, "INTERNAL", "Internal error");
        }
    }

    /** Reads and parses the JSON body: content type, size limit, strict parsing. */
    protected <T> T body(HttpServletRequest req, Class<T> type) {
        String ct = req.getContentType();
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw ApiException.unsupportedMediaType();
        }
        long declared = req.getContentLengthLong();
        if (declared > maxBodyBytes()) {
            throw ApiException.tooLarge("Request body exceeds " + maxBodyBytes() + " bytes");
        }
        try (InputStream in = new LimitedInputStream(req.getInputStream(), maxBodyBytes())) {
            return Json.read(in, type);
        } catch (IOException e) {
            throw ApiException.badRequest("BAD_JSON", "Could not read the request body");
        }
    }

    protected static void status(HttpServletRequest req, int status) {
        req.setAttribute(STATUS_ATTRIBUTE, status);
    }
}
