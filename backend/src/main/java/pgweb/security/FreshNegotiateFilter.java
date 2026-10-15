package pgweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.web.ErrorEnvelope;
import waffle.windows.auth.IWindowsAuthProvider;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.IWindowsSecurityContext;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Requests that open a database connection carry their own Windows handshake (ADR-0002): the server answers
 * {@code 401 Negotiate} until the browser sends a token, completes the handshake with Waffle's SSPI provider, checks
 * that the identity is the session's user (same SID), and hands the identity to the request for impersonation while
 * connecting. The identity is disposed when the request ends; nothing is kept. Runs after the access filter, so users
 * without permission are refused before any handshake.
 */
public final class FreshNegotiateFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;
    public static final String IDENTITY_ATTRIBUTE = "pgweb.delegatedIdentity";

    private final transient IWindowsAuthProvider provider;
    private final Set<String> protocols;
    private final transient AuditService audit;

    public FreshNegotiateFilter(IWindowsAuthProvider provider, Set<String> protocols, AuditService audit) {
        this.provider = provider;
        this.protocols = Set.copyOf(protocols);
        this.audit = audit;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!"POST".equals(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        UserContext user = UserContext.of(req);
        String auth = req.getHeader("Authorization");
        String[] parts = auth == null ? new String[0] : auth.strip().split("\\s+", 2);
        String scheme = parts.length == 2 ? parts[0] : "";
        if (user == null || parts.length != 2 || protocols.stream().noneMatch(p -> p.equalsIgnoreCase(scheme))) {
            challenge(res, null, null);
            return;
        }
        byte[] token;
        try {
            token = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            challenge(res, null, null);
            return;
        }
        String connectionId = "fresh:" + req.getRemoteAddr() + ":" + req.getRemotePort();
        IWindowsSecurityContext ctx;
        try {
            ctx = provider.acceptSecurityToken(connectionId, token, scheme.toLowerCase(Locale.ROOT).equals("ntlm") ? "NTLM" : "Negotiate");
        } catch (RuntimeException e) {
            challenge(res, null, null);
            return;
        }
        if (ctx.isContinue()) {
            // Multi-leg (NTLM): Waffle keeps this context for the next leg on the same connection; don't dispose it.
            challenge(res, scheme, ctx.getToken());
            return;
        }
        IWindowsIdentity identity = null;
        try {
            identity = ctx.getIdentity();
            if (!identity.getSidString().equalsIgnoreCase(user.sid())) {
                audit.record(AuditEvent.of("authz_denied").user(user).request(req).detail("reason", "delegation_identity_mismatch"));
                ErrorEnvelope.write(res, 403, "IDENTITY_MISMATCH", "The Windows identity of this request is not the signed-in user");
                return;
            }
            req.setAttribute(IDENTITY_ATTRIBUTE, identity);
            chain.doFilter(req, res);
        } finally {
            req.removeAttribute(IDENTITY_ATTRIBUTE);
            if (identity != null) {
                identity.dispose();
            }
            ctx.dispose();
        }
    }

    private void challenge(HttpServletResponse res, String scheme, byte[] continuation) throws IOException {
        if (continuation != null) {
            res.setHeader("WWW-Authenticate", scheme + " " + Base64.getEncoder().encodeToString(continuation));
        } else {
            for (String p : protocols) {
                res.addHeader("WWW-Authenticate", p);
            }
        }
        ErrorEnvelope.write(res, 401, "DELEGATION_HANDSHAKE", "Windows sign-in is needed to open the database connection");
    }
}
