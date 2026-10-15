package spike.s1servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/** Reachable only through {@link DataAdminFilter}. */
final class AdminPingServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Responses.json(res, Json.object(Map.of("ok", true, "name", req.getUserPrincipal().getName())));
    }
}
