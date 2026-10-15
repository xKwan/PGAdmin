package pgweb.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in text before it reaches a log (threat T10/T27, SEC-08). Applied to every log message and stack
 * trace by {@link RedactingMessageConverter} and {@link RedactingThrowableConverter}. It is a safety net: code must
 * still never log secrets on purpose.
 */
public final class Redactor {

    static final String MASK = "***";

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final String SECRET_KEYS = "password|passwd|pwd|secret|token|hmackey|keystorepassword|apikey|sonar\\.token|credential";

    private static final List<Rule> RULES = List.of(
            // HTTP authorization headers: keep the scheme, drop the token.
            new Rule(Pattern.compile("(?i)\\b((?:proxy-)?authorization\\s*[:=]\\s*\"?)(negotiate|ntlm|basic|bearer|kerberos)\\s+[A-Za-z0-9+/=._~-]+"),
                    "$1$2 " + MASK),
            new Rule(Pattern.compile("(?i)\\b(www-authenticate\\s*[:=]\\s*\"?)(negotiate|ntlm)\\s+[A-Za-z0-9+/=]+"), "$1$2 " + MASK),
            // JSON: "password": "value"
            new Rule(Pattern.compile("(?i)(\"(?:" + SECRET_KEYS + ")\"\\s*:\\s*)\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\""),"$1\"" + MASK + "\""),
            // key=value and key: value (properties, JDBC URL parameters, command lines)
            new Rule(Pattern.compile("(?i)\\b([A-Za-z0-9_.-]*(?:" + SECRET_KEYS + "))(\\s*[=:]\\s*)(?!\\*\\*\\*)([^\\s&;,\"']+)"), "$1$2" + MASK),
            // Secret references are not secrets, but DPAPI blobs are long and useless in logs.
            new Rule(Pattern.compile("dpapi:[A-Za-z0-9+/=]{16,}"), "dpapi:" + MASK));

    private Redactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(out);
            if (m.find()) {
                out = m.replaceAll(r.replacement);
            }
        }
        return out;
    }
}
