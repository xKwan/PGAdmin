package spike.s1servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spike only: creates a session without authentication, standing in for any code path that might create one before
 * login. Used to check that authentication never adopts a planted session ID.
 */
final class AnonSessionServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) {
        req.getSession(true);
        res.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
