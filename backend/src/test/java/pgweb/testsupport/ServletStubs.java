package pgweb.testsupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal servlet request/response stand-ins (dynamic proxies) for unit-testing filters on paths an HTTP client
 * can't reach, such as a request without a principal behind a bypassed Waffle filter.
 */
public final class ServletStubs {

    private ServletStubs() {
    }

    /** A request with the given method, URI, headers and principal; attributes and a session are kept in maps. */
    public static HttpServletRequest request(String method, String uri, Map<String, String> headers, Principal principal, boolean secure) {
        Map<String, Object> attrs = new HashMap<>();
        Map<String, String> h = new LinkedHashMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v));
        HttpSession[] session = new HttpSession[1];
        return (HttpServletRequest) Proxy.newProxyInstance(ServletStubs.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getRequestURI" -> uri;
                    case "getContextPath" -> "";
                    case "getHeader" -> h.get(((String) a[0]).toLowerCase(java.util.Locale.ROOT));
                    case "getHeaders" -> {
                        String v = h.get(((String) a[0]).toLowerCase(java.util.Locale.ROOT));
                        yield v == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(v));
                    }
                    case "getHeaderNames" -> Collections.enumeration(List.copyOf(h.keySet()));
                    case "getUserPrincipal" -> principal;
                    case "isSecure" -> secure;
                    case "getRemoteAddr" -> "127.0.0.1";
                    case "getRemotePort" -> 50000;
                    case "removeAttribute" -> {
                        attrs.remove((String) a[0]);
                        yield null;
                    }
                    case "getAttribute" -> attrs.get((String) a[0]);
                    case "setAttribute" -> {
                        attrs.put((String) a[0], a[1]);
                        yield null;
                    }
                    case "getSession" -> {
                        if (session[0] == null && (a == null || a.length == 0 || (Boolean) a[0])) {
                            session[0] = session();
                        }
                        yield session[0];
                    }
                    case "getHttpServletMapping" -> null;
                    case "toString" -> "stub " + method + " " + uri;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    static HttpSession session() {
        Map<String, Object> attrs = new HashMap<>();
        return (HttpSession) Proxy.newProxyInstance(ServletStubs.class.getClassLoader(), new Class<?>[] {HttpSession.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getAttribute" -> attrs.get((String) a[0]);
                    case "setAttribute" -> {
                        attrs.put((String) a[0], a[1]);
                        yield null;
                    }
                    case "getId" -> "stub-session";
                    case "invalidate" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** A response that records status, headers and body. */
    public static final class Recorded {
        public int status = 200;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public final ByteArrayOutputStream body = new ByteArrayOutputStream();
        public final HttpServletResponse response;

        public Recorded() {
            ServletOutputStream out = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener l) {
                    // not used
                }

                @Override
                public void write(int b) {
                    body.write(b);
                }
            };
            response = (HttpServletResponse) Proxy.newProxyInstance(ServletStubs.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "setStatus" -> {
                            status = (Integer) a[0];
                            yield null;
                        }
                        case "setHeader" -> {
                            headers.put((String) a[0], (String) a[1]);
                            yield null;
                        }
                        case "addHeader" -> {
                            headers.merge((String) a[0], (String) a[1], (x, y) -> x + ", " + y);
                            yield null;
                        }
                        case "setContentType", "setCharacterEncoding", "resetBuffer" -> null;
                        case "isCommitted" -> false;
                        case "getOutputStream" -> out;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        public String text() {
            return body.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** A filter chain that records the request it was given. */
    public static class Chain implements FilterChain {
        public HttpServletRequest seen;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            seen = (HttpServletRequest) req;
        }
    }
}
