package spike.s1servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import waffle.servlet.WindowsPrincipal;
import waffle.windows.auth.WindowsAccount;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the server learned about the caller: account, SID, groups (names and SIDs), DataAdmin decision. */
final class WhoAmIServlet extends HttpServlet {

    private final String dataAdminSid;

    WhoAmIServlet(String dataAdminSid) {
        this.dataAdminSid = dataAdminSid;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        WindowsPrincipal p = (WindowsPrincipal) req.getUserPrincipal();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName());
        out.put("sid", p.getSidString());
        out.put("groupSids", p.getGroups().values().stream().map(WindowsAccount::getSidString).sorted().toList());
        out.put("groupNames", p.getGroups().values().stream().map(WindowsAccount::getFqn).sorted().toList());
        out.put("dataAdmin", DataAdminFilter.isDataAdmin(p, dataAdminSid));
        Responses.json(res, Json.object(out));
    }
}
