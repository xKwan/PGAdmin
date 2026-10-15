package pgweb.web;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Replaces Tomcat's HTML error page for errors that no servlet answered itself (unmapped paths, filters that send an
 * error, container-level failures). Writes the API error envelope; never a stack trace or the server version
 * (S1 finding W11).
 */
public final class JsonErrorReportValve extends ErrorReportValve {

    private static final Logger LOG = LoggerFactory.getLogger(JsonErrorReportValve.class);

    public JsonErrorReportValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable t) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        if (t != null) {
            Object rid = request.getAttribute(RequestIdFilter.ATTRIBUTE);
            if (rid != null) {
                MDC.put(RequestIdFilter.MDC_KEY, rid.toString());
            }
            try {
                LOG.error("unhandled error for {} {}", request.getMethod(), request.getRequestURI(), t);
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        }
        try {
            Object rid = request.getAttribute(RequestIdFilter.ATTRIBUTE);
            StringBuilder json = new StringBuilder(160);
            json.append("{\"error\":{\"code\":\"").append(ErrorEnvelope.codeFor(status))
                    .append("\",\"message\":\"").append(ErrorEnvelope.messageFor(status)).append('"');
            if (rid != null) {
                json.append(",\"requestId\":\"").append(rid).append('"');
            }
            json.append("}}");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.getOutputStream().write(json.toString().getBytes(StandardCharsets.UTF_8));
            response.finishResponse();
        } catch (IOException | IllegalStateException e) {
            // The client went away or the response is unusable; nothing more can be sent.
        }
    }
}
