package pgweb.testsupport;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Sspi;
import com.sun.jna.platform.win32.SspiUtil;
import waffle.windows.auth.IWindowsSecurityContext;
import waffle.windows.auth.impl.WindowsAccountImpl;
import waffle.windows.auth.impl.WindowsSecurityContextImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client side of Windows integrated authentication, using SSPI through Waffle with the credentials of the account
 * running the test (what Edge does for an intranet site). Runs the multi-leg handshake on one connection.
 */
public final class NegotiateClient {

    /** Result of a handshake: final response, every token sent, and the number of legs. */
    public record Result(RawHttp.Response response, List<byte[]> clientTokens, int legs) {
        /** "NTLM" when the first client token is a raw or SPNEGO-wrapped NTLM message, else "Kerberos/other". */
        public String mechanism() {
            byte[] first = clientTokens.get(0);
            String s = new String(first, java.nio.charset.StandardCharsets.ISO_8859_1);
            return s.contains("NTLMSSP") ? "NTLM" : "Kerberos/other";
        }
    }

    private NegotiateClient() {
    }

    public static String currentUserFqn() {
        return WindowsAccountImpl.getCurrentUsername();
    }

    public static String currentUserSid() {
        return Advapi32Util.getAccountByName(currentUserFqn()).sidString;
    }

    public static Result handshake(RawHttp http, String path, String scheme, Map<String, String> extraHeaders) throws IOException {
        IWindowsSecurityContext ctx = WindowsSecurityContextImpl.getCurrent(scheme, "localhost");
        List<byte[]> sent = new ArrayList<>();
        try {
            int legs = 0;
            byte[] token = ctx.getToken();
            while (true) {
                legs++;
                sent.add(token);
                Map<String, String> headers = new HashMap<>(extraHeaders);
                headers.put("Authorization", scheme + " " + Base64.getEncoder().encodeToString(token));
                RawHttp.Response r = http.get(path, headers);
                byte[] serverToken = serverToken(r, scheme);
                if (r.status() != 401 || serverToken == null || !ctx.isContinue()) {
                    return new Result(r, sent, legs);
                }
                ctx.initialize(ctx.getHandle(), new SspiUtil.ManagedSecBufferDesc(Sspi.SECBUFFER_TOKEN, serverToken), "localhost");
                token = ctx.getToken();
                if (legs > 5) {
                    throw new IllegalStateException("handshake did not finish");
                }
            }
        } finally {
            ctx.dispose();
        }
    }

    /** Server continuation token from {@code WWW-Authenticate: <scheme> <base64>}, or null. */
    public static byte[] serverToken(RawHttp.Response r, String scheme) {
        for (String v : r.header("WWW-Authenticate")) {
            if (v.regionMatches(true, 0, scheme + " ", 0, scheme.length() + 1)) {
                return Base64.getDecoder().decode(v.substring(scheme.length() + 1).trim());
            }
        }
        return null;
    }

    /** Starts a handshake and returns the context after the first leg (for split-connection tests). */
    public static IWindowsSecurityContext start(String scheme) {
        return WindowsSecurityContextImpl.getCurrent(scheme, "localhost");
    }

    public static byte[] continueWith(IWindowsSecurityContext ctx, byte[] serverToken) {
        ctx.initialize(ctx.getHandle(), new SspiUtil.ManagedSecBufferDesc(Sspi.SECBUFFER_TOKEN, serverToken), "localhost");
        return ctx.getToken();
    }
}
