package spike.s1servlet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import waffle.servlet.WindowsPrincipal;

import java.io.IOException;
import java.security.Principal;

/** DataAdmin = the Windows identity is a member of the configured group SID (decided on the server only; D1). */
final class DataAdminFilter extends HttpFilter {

    private final String dataAdminSid;

    DataAdminFilter(String dataAdminSid) {
        this.dataAdminSid = dataAdminSid;
    }

    static boolean isDataAdmin(Principal p, String dataAdminSid) {
        return p instanceof WindowsPrincipal wp
                && wp.getGroups().values().stream().anyMatch(g -> dataAdminSid.equalsIgnoreCase(g.getSidString()));
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!isDataAdmin(req.getUserPrincipal(), dataAdminSid)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(req, res);
    }
}
