package spike.s2.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code log_statement} entry. PostgreSQL writes:
 * <ul>
 *   <li>{@code statement: <text>} for the simple query protocol (one Query message, may hold several statements);</li>
 *   <li>{@code execute <stmt>: <text>} for an extended-protocol Execute (exactly one statement per Parse);</li>
 *   <li>{@code execute fetch from <stmt>/<portal>: <text>} when a suspended portal is continued.</li>
 * </ul>
 */
public record LoggedStatement(Kind kind, String text) {

    public enum Kind { SIMPLE, EXECUTE, FETCH }

    private static final Pattern EXECUTE = Pattern.compile("(?s)^execute ([^:]*): (.*)$");

    static LoggedStatement parse(String message) {
        if (message.startsWith("statement: ")) {
            return new LoggedStatement(Kind.SIMPLE, message.substring("statement: ".length()));
        }
        Matcher m = EXECUTE.matcher(message);
        if (m.matches()) {
            Kind k = m.group(1).startsWith("fetch from") ? Kind.FETCH : Kind.EXECUTE;
            return new LoggedStatement(k, m.group(2));
        }
        return null;
    }
}
