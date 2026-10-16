package pgweb.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single error format of the API (plan §3.8): {@code {"error":{"code","message","requestId"}}}. No stack
 * trace, exception class, server version or SQL ever appears in it.
 */
public final class ErrorEnvelope {

    private ErrorEnvelope() {
    }

    public static Map<String, Object> body(String code, String message) {
        return body(code, message, Map.of());
    }

    /** {@code details} are extra fields; they never replace code, message or requestId. */
    public static Map<String, Object> body(String code, String message, Map<String, Object> details) {
        Map<String, Object> err = new LinkedHashMap<>(details);
        err.put("code", code);
        err.put("message", message);
        String rid = MDC.get(RequestIdFilter.MDC_KEY);
        if (rid != null) {
            err.put("requestId", rid);
        }
        return Map.of("error", err);
    }

    public static void write(HttpServletResponse resp, int status, String code, String message) throws IOException {
        write(resp, status, code, message, Map.of());
    }

    public static void write(HttpServletResponse resp, int status, String code, String message, Map<String, Object> details) throws IOException {
        if (resp.isCommitted()) {
            return;
        }
        resp.resetBuffer();
        resp.setStatus(status);
        Responses.json(resp);
        resp.getOutputStream().write(Json.write(body(code, message, details)).getBytes(StandardCharsets.UTF_8));
    }

    /** Default code for a status that an error page (not a servlet) produced. */
    public static String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 413 -> "TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "RATE_LIMITED";
            case 503 -> "UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL" : "ERROR";
        };
    }

    public static String messageFor(int status) {
        return switch (status) {
            case 400 -> "Bad request";
            case 401 -> "Authentication required";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 413 -> "Request too large";
            case 415 -> "Unsupported media type";
            case 429 -> "Too many requests";
            case 503 -> "Service unavailable";
            default -> status >= 500 ? "Internal error" : "Request failed";
        };
    }
}
