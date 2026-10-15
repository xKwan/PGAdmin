package spike.s1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import waffle.servlet.WindowsPrincipal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class WhoAmIController {

    private final String dataAdminSid;

    public WhoAmIController(@Value("${pgweb.data-admin-sid}") String dataAdminSid) {
        this.dataAdminSid = dataAdminSid;
    }

    /** What the server learned about the caller: account, SID, groups (names and SIDs), DataAdmin decision. */
    @GetMapping("/whoami")
    public Map<String, Object> whoami(Authentication authentication) {
        WindowsPrincipal p = (WindowsPrincipal) authentication.getPrincipal();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName());
        out.put("sid", p.getSidString());
        out.put("groupSids", p.getGroups().values().stream().map(g -> g.getSidString()).sorted().toList());
        out.put("groupNames", p.getGroups().values().stream().map(g -> g.getFqn()).sorted().toList());
        out.put("authorities", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
        out.put("dataAdmin", SecurityConfig.isDataAdmin(authentication, dataAdminSid));
        return out;
    }

    @GetMapping("/admin/ping")
    public Map<String, Object> adminPing(Authentication authentication) {
        return Map.of("ok", true, "name", authentication.getName(), "checks", List.of("dataAdminSid=" + dataAdminSid));
    }
}
