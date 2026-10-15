package pgweb.testsupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import pgweb.security.PublicPaths;
import waffle.servlet.WindowsPrincipal;
import waffle.windows.auth.IWindowsAccount;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.IWindowsImpersonationContext;

import java.io.IOException;
import java.security.Principal;

/**
 * <b>Test scope only</b> (threat T26): stands in for Waffle so tests can act as several different Windows users.
 * The header {@code X-Mock-User: DOMAIN\name|S-1-...|S-1-group1,S-1-group2} names the identity; a real
 * {@link WindowsPrincipal} is built from it and kept in the session exactly as Waffle does, so the session filter,
 * authorisation and audit code run unchanged. Without the header (and without a session) the request is answered
 * with a Negotiate challenge, like Waffle. The production JAR doesn't contain this class, and the application
 * refuses to start with an authentication override in prod mode.
 */
public final class MockAuthFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;
    public static final String HEADER = "X-Mock-User";
    static final String SESSION_KEY = "pgweb.mockPrincipal";
    public static final String PRE_SESSION_HEADER = "X-Mock-Pre-Session";

    /** Header value for a user. */
    public static String header(String account, String sid, String... groupSids) {
        return account + "|" + sid + "|" + String.join(",", groupSids);
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (PublicPaths.isPublic(req)) {
            chain.doFilter(req, res);
            return;
        }
        String h = req.getHeader(HEADER);
        Principal principal = null;
        if (h != null) {
            String[] parts = h.split("\\|", -1);
            principal = new WindowsPrincipal(new FakeIdentity(parts[0], parts[1], parts.length > 2 && !parts[2].isEmpty() ? parts[2].split(",") : new String[0]));
            HttpSession s = req.getSession(true);
            s.setAttribute(SESSION_KEY, principal);
            // Tests use this to prove the session filter rotates the ID at sign-in (session fixation).
            res.setHeader(PRE_SESSION_HEADER, s.getId());
        } else {
            HttpSession s = req.getSession(false);
            if (s != null) {
                principal = (Principal) s.getAttribute(SESSION_KEY);
            }
        }
        if (principal == null) {
            res.setHeader("WWW-Authenticate", "Negotiate");
            res.sendError(401);
            return;
        }
        Principal p = principal;
        req.setAttribute(pgweb.security.AuthSchemeFilter.MECHANISM_ATTRIBUTE, "Kerberos");
        chain.doFilter(new HttpServletRequestWrapper(req) {
            @Override
            public Principal getUserPrincipal() {
                return p;
            }

            @Override
            public String getRemoteUser() {
                return p.getName();
            }
        }, res);
    }

    /** A Windows identity that exists only in tests. */
    static final class FakeIdentity implements IWindowsIdentity {
        private final String fqn;
        private final String sid;
        private final String[] groups;

        FakeIdentity(String fqn, String sid, String[] groups) {
            this.fqn = fqn;
            this.sid = sid;
            this.groups = groups.clone();
        }

        @Override
        public byte[] getSid() {
            return sid.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }

        @Override
        public String getSidString() {
            return sid;
        }

        @Override
        public String getFqn() {
            return fqn;
        }

        @Override
        public IWindowsAccount[] getGroups() {
            IWindowsAccount[] out = new IWindowsAccount[groups.length];
            for (int i = 0; i < groups.length; i++) {
                String g = groups[i];
                out[i] = new IWindowsAccount() {
                    @Override
                    public String getSidString() {
                        return g;
                    }

                    @Override
                    public String getFqn() {
                        return "TEST\\group-" + g;
                    }

                    @Override
                    public String getName() {
                        return "group-" + g;
                    }

                    @Override
                    public String getDomain() {
                        return "TEST";
                    }
                };
            }
            return out;
        }

        @Override
        public void dispose() {
            // nothing held
        }

        @Override
        public boolean isGuest() {
            return false;
        }

        @Override
        public IWindowsImpersonationContext impersonate() {
            throw new UnsupportedOperationException("a mock identity can't be impersonated");
        }
    }
}
