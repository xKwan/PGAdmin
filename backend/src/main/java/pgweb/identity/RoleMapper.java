package pgweb.identity;

import pgweb.security.UserContext;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps the authenticated Windows account to the PostgreSQL role it connects as (ADR-0002). Server-side only: the
 * client never names a role (SEC-04, T04). The server's {@code pg_ident.conf} must map the same Kerberos principal to
 * the same role, and pgweb checks {@code session_user} after connecting.
 * <ul>
 *   <li>An explicit override for {@code DOMAIN\account} wins.</li>
 *   <li>Otherwise the {@code sAMAccountName}, lower-cased unless configured to keep its case.</li>
 * </ul>
 */
public final class RoleMapper {

    /** Role names pgweb will use: plain identifiers only (they are also passed in the connection request). */
    public static final Pattern ROLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.$-]{0,62}$");

    private final boolean lowerCase;
    private final Map<String, String> overrides;

    /** @param overrides keys {@code DOMAIN\account} in upper case */
    public RoleMapper(boolean lowerCase, Map<String, String> overrides) {
        this.lowerCase = lowerCase;
        this.overrides = Map.copyOf(overrides);
    }

    /** The role for this user; throws if the account can't be mapped to an acceptable role name. */
    public String roleFor(UserContext user) {
        String override = overrides.get(user.account().toUpperCase(Locale.ROOT));
        String role = override != null ? override : (lowerCase ? user.samAccountName().toLowerCase(Locale.ROOT) : user.samAccountName());
        if (!ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("account cannot be mapped to a PostgreSQL role");
        }
        return role;
    }
}
