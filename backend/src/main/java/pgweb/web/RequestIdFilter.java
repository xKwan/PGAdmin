package pgweb.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Gives every request a random correlation ID: in the log context ({@code requestId}), the {@code X-Request-Id}
 * response header and error envelopes. A client-supplied ID is never trusted (log injection).
 */
public final class RequestIdFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = "pgweb.requestId";
    private static final SecureRandom RANDOM = new SecureRandom();

    static String newId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws IOException, ServletException {
        String id = newId();
        req.setAttribute(ATTRIBUTE, id);
        resp.setHeader("X-Request-Id", id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
