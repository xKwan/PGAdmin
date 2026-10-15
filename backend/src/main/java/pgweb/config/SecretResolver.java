package pgweb.config;

import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves secret references in configuration (plan §7.6, threat T27). A secret value is never written literally
 * in production configuration; it is one of:
 * <ul>
 *   <li>{@code dpapi:<base64>} — Windows DPAPI-protected bytes (machine or service-account scope), decrypted with
 *       {@code CryptUnprotectData}. Produced by {@code deploy\windows\Protect-PgwebSecret.ps1}.</li>
 *   <li>{@code file:<path>} — the first line of an ACL-restricted file.</li>
 *   <li>{@code env:<NAME>} — an environment variable of the service process.</li>
 *   <li>{@code plain:<value>} — a literal; accepted only in {@link Mode#TEST}.</li>
 * </ul>
 */
public final class SecretResolver {

    private final Mode mode;
    private final Function<String, String> env;
    private final Function<byte[], byte[]> dpapi;

    public SecretResolver(Mode mode) {
        this(mode, System::getenv, Crypt32Util::cryptUnprotectData);
    }

    SecretResolver(Mode mode, Function<String, String> env, Function<byte[], byte[]> dpapi) {
        this.mode = mode;
        this.env = env;
        this.dpapi = dpapi;
    }

    /** Resolves {@code ref} for the setting {@code key}; the message never contains the secret itself. */
    public Secret resolve(String key, String ref) throws ConfigException {
        if (ref == null || ref.isBlank()) {
            throw new ConfigException(key + " is required (a secret reference: dpapi:, file:, env:" + (mode == Mode.TEST ? " or plain:" : "") + ")");
        }
        int colon = ref.indexOf(':');
        String scheme = colon < 0 ? "" : ref.substring(0, colon);
        String rest = colon < 0 ? "" : ref.substring(colon + 1);
        byte[] value = switch (scheme) {
            case "dpapi" -> unprotect(key, rest);
            case "file" -> readFile(key, rest);
            case "env" -> fromEnv(key, rest);
            case "plain" -> {
                if (mode != Mode.TEST) {
                    throw new ConfigException(key + ": plain: secrets are only allowed with pgweb.mode=test; use dpapi:, file: or env:");
                }
                yield rest.getBytes(StandardCharsets.UTF_8);
            }
            default -> throw new ConfigException(key + ": not a secret reference (expected dpapi:, file:, env:" + (mode == Mode.TEST ? " or plain:" : "") + ")");
        };
        if (value.length == 0) {
            throw new ConfigException(key + ": the secret is empty");
        }
        return new Secret(value);
    }

    private byte[] unprotect(String key, String base64) throws ConfigException {
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new ConfigException(key + ": dpapi: value is not valid base64");
        }
        try {
            return dpapi.apply(blob);
        } catch (RuntimeException e) {
            throw new ConfigException(key + ": DPAPI could not decrypt the value (wrong machine or account?)");
        }
    }

    private static byte[] readFile(String key, String path) throws ConfigException {
        try {
            String text = Files.readString(Path.of(path.trim()), StandardCharsets.UTF_8);
            int nl = text.indexOf('\n');
            String first = (nl < 0 ? text : text.substring(0, nl)).strip();
            return first.getBytes(StandardCharsets.UTF_8);
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new ConfigException(key + ": cannot read secret file " + path.trim());
        }
    }

    private byte[] fromEnv(String key, String name) throws ConfigException {
        String v = env.apply(name.trim());
        if (v == null) {
            throw new ConfigException(key + ": environment variable " + name.trim() + " is not set");
        }
        return v.getBytes(StandardCharsets.UTF_8);
    }

    /** Builds the environment lookup used in tests. */
    static Function<String, String> envOf(Map<String, String> vars) {
        return vars::get;
    }
}
