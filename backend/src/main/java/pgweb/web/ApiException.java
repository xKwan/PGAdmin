package pgweb.web;

/**
 * An expected API failure with a status, a stable machine-readable code and a message that is safe to show to the
 * user (plan §3.8). Never put internal details, SQL, host names or stack traces in the message.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHENTICATED", "Authentication required");
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "Not found");
    }

    public static ApiException methodNotAllowed() {
        return new ApiException(405, "METHOD_NOT_ALLOWED", "Method not allowed");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException tooLarge(String message) {
        return new ApiException(413, "TOO_LARGE", message);
    }

    public static ApiException unsupportedMediaType() {
        return new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(429, "RATE_LIMITED", message);
    }

    public static ApiException unavailable(String code, String message) {
        return new ApiException(503, code, message);
    }
}
