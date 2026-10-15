package pgweb.security;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * The authenticated user for one request, built on the server from the Waffle security context only (SEC-02).
 * Nothing in it comes from request parameters, headers or bodies.
 *
 * @param account     {@code DOMAIN\sAMAccountName}
 * @param sid         the user's SID
 * @param groupSids   SIDs of the groups in the user's Windows token (from the last Windows authentication)
 * @param dataAdmin   member of the configured DataAdmin group, evaluated on this request
 * @param mechanism   {@code Kerberos} or {@code NTLM}, from the token that authenticated the session
 * @param sessionHash keyed hash of the app session ID, for audit correlation (never the ID itself)
 */
public record UserContext(String account, String sid, Set<String> groupSids, boolean dataAdmin, String mechanism,
                          String sessionHash, Instant loginAt, String clientIp) {

    public static final String ATTRIBUTE = "pgweb.user";

    public String domain() {
        int i = account.indexOf('\\');
        return i < 0 ? "" : account.substring(0, i).toUpperCase(Locale.ROOT);
    }

    /** The account name without the domain, as typed at the Windows logon. */
    public String samAccountName() {
        int i = account.indexOf('\\');
        return i < 0 ? account : account.substring(i + 1);
    }

    /** The current user, or {@code null} on a public endpoint. */
    public static UserContext of(HttpServletRequest req) {
        return req.getAttribute(ATTRIBUTE) instanceof UserContext u ? u : null;
    }
}
