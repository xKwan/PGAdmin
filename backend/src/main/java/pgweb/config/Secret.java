package pgweb.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A resolved secret. {@link #toString()} never reveals it, so a secret that ends up in a log line, an exception
 * or a debugger view shows as {@code Secret[***]}.
 */
public final class Secret {

    private final byte[] value;

    public Secret(byte[] value) {
        this.value = value.clone();
    }

    public byte[] bytes() {
        return value.clone();
    }

    /** The secret as text; callers must not log it. */
    public String reveal() {
        return new String(value, StandardCharsets.UTF_8);
    }

    public int length() {
        return value.length;
    }

    @Override
    public String toString() {
        return "Secret[***]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Secret s && java.security.MessageDigest.isEqual(value, s.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }
}
