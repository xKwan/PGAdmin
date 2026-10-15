package spike.s7;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Sspi;
import com.sun.jna.platform.win32.SspiUtil;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.IWindowsImpersonationContext;
import waffle.windows.auth.IWindowsSecurityContext;
import waffle.windows.auth.impl.WindowsAuthProviderImpl;
import waffle.windows.auth.impl.WindowsSecurityContextImpl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7: can the backend open a PostgreSQL connection as the Waffle-authenticated user, with no password, by
 * impersonating that user only while pgJDBC performs an SSPI logon (ADR-0002)?
 *
 * <p>On this workstation the "user" is the account running the test, authenticated through a real server-side SSPI
 * handshake (the same API Waffle's filter uses). The anonymous-token tests prove that pgJDBC's SSPI logon really uses
 * the calling thread's impersonation token, so the positive result is not just the process identity leaking through.
 */
class DelegatedLogonTest {

    /** ImpersonateAnonymousToken is not mapped by jna-platform. */
    interface Adv extends StdCallLibrary {
        Adv INSTANCE = Native.load("Advapi32", Adv.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ImpersonateAnonymousToken(WinNT.HANDLE thread);
    }

    static SspiCluster pg;
    static String role;
    static String processUser;
    static Random rnd;

    @BeforeAll
    static void start() throws Exception {
        processUser = Advapi32Util.getUserName();
        role = processUser.toLowerCase(Locale.ROOT);
        rnd = new Random(Long.getLong("s7.seed", 0L));
        pg = SspiCluster.start(role);
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) {
            pg.close();
            record("cleanup", "cluster_gone", String.valueOf(pg.isGone()));
            assertTrue(pg.isGone(), "throwaway cluster removed and port free");
        }
    }

    @Test
    void processIdentityLogsOnWithSspi() throws Exception {
        try (Connection c = pg.sspi(role, "s7-process")) {
            assertEquals(role, sessionUser(c));
        }
        String log = pg.serverLog();
        assertTrue(log.contains("method=sspi"), "server log records an SSPI authentication");
        record("sspi", "process_identity", "session_user=" + role + ", method=sspi");
    }

    @Test
    void impersonatedWaffleIdentityLogsOn() throws Exception {
        IWindowsIdentity identity = serverSideHandshake();
        try {
            String inside;
            String sessionUser;
            IWindowsImpersonationContext ctx = identity.impersonate();
            try {
                inside = Advapi32Util.getUserName();
                try (Connection c = pg.sspi(role, "s7-impersonated")) {
                    sessionUser = sessionUser(c);
                }
            } finally {
                ctx.revertToSelf();
            }
            assertEquals(processUser, Advapi32Util.getUserName(), "identity restored after revert");
            assertEquals(role, sessionUser);
            record("sspi", "impersonated", "thread user while impersonating=" + inside + ", session_user=" + sessionUser);
        } finally {
            identity.dispose();
        }
    }

    @Test
    void connectionOutlivesImpersonationAndToken() throws Exception {
        IWindowsIdentity identity = serverSideHandshake();
        Connection c;
        IWindowsImpersonationContext ctx = identity.impersonate();
        try {
            c = pg.sspi(role, "s7-outlive");
        } finally {
            ctx.revertToSelf();
            identity.dispose();
        }
        try (c; Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT session_user, 1 + 1")) {
            rs.next();
            assertEquals(role, rs.getString(1));
            assertEquals(2, rs.getInt(2));
        }
        record("sspi", "outlives_token", "queries keep working after revert and dispose");
    }

    @Test
    void anonymousTokenCannotLogOn() throws Exception {
        // Positive control first, so a failure below can only come from the thread's identity.
        try (Connection c = pg.sspi(role, "s7-anon-control")) {
            assertEquals(role, sessionUser(c));
        }
        SQLException e = assertThrows(SQLException.class, () -> asAnonymous(() -> {
            pg.sspi(role, "s7-anon").close();
            return null;
        }));
        assertEquals(processUser, Advapi32Util.getUserName(), "identity restored after anonymous impersonation");
        record("sspi", "anonymous", "refused: " + e.getSQLState() + " " + firstLine(e.getMessage()));
    }

    /** Impersonation is per thread: threads acting as anonymous fail while others, at the same time, succeed. */
    @Test
    void impersonationIsPerThread() throws Exception {
        int threads = 4 + rnd.nextInt(5);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Boolean> anonymous = new ArrayList<>();
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                boolean anon = i == 0 || (i > 1 && rnd.nextBoolean());
                if (i == 1) {
                    anon = false;
                }
                anonymous.add(anon);
                final boolean a = anon;
                results.add(pool.submit(() -> {
                    Callable<Boolean> attempt = () -> {
                        try (Connection c = pg.sspi(role, "s7-thread")) {
                            return role.equals(sessionUser(c));
                        } catch (SQLException e) {
                            return false;
                        }
                    };
                    return a ? asAnonymous(attempt) : attempt.call();
                }));
            }
            for (int i = 0; i < threads; i++) {
                boolean ok = results.get(i).get(60, TimeUnit.SECONDS);
                assertEquals(!anonymous.get(i), ok, "thread " + i + " anonymous=" + anonymous.get(i));
            }
            record("sspi", "per_thread", threads + " threads, anonymous=" + anonymous + ": only non-anonymous ones connected");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------

    /** Client and server halves of a Negotiate handshake in-process; returns the server's view of the caller. */
    static IWindowsIdentity serverSideHandshake() {
        WindowsAuthProviderImpl provider = new WindowsAuthProviderImpl();
        String connectionId = "s7-" + rnd.nextLong();
        IWindowsSecurityContext client = WindowsSecurityContextImpl.getCurrent("Negotiate", "localhost");
        try {
            for (int leg = 0; leg < 6; leg++) {
                IWindowsSecurityContext server = provider.acceptSecurityToken(connectionId, client.getToken(), "Negotiate");
                if (!server.isContinue()) {
                    IWindowsIdentity id = server.getIdentity();
                    server.dispose();
                    return id;
                }
                client.initialize(client.getHandle(), new SspiUtil.ManagedSecBufferDesc(Sspi.SECBUFFER_TOKEN, server.getToken()), "localhost");
            }
            throw new IllegalStateException("handshake did not complete");
        } finally {
            client.dispose();
        }
    }

    static <T> T asAnonymous(Callable<T> body) throws Exception {
        if (!Adv.INSTANCE.ImpersonateAnonymousToken(Kernel32.INSTANCE.GetCurrentThread())) {
            throw new IllegalStateException("ImpersonateAnonymousToken failed: " + Kernel32.INSTANCE.GetLastError());
        }
        try {
            return body.call();
        } finally {
            Advapi32.INSTANCE.RevertToSelf();
        }
    }

    static String sessionUser(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT session_user")) {
            rs.next();
            return rs.getString(1);
        }
    }

    static String firstLine(String s) {
        int i = s == null ? -1 : s.indexOf('\n');
        return i < 0 ? String.valueOf(s) : s.substring(0, i);
    }

    static synchronized void record(String area, String key, String value) {
        Path f = Paths.get("target", "s7-findings.jsonl");
        String line = "{\"seed\":" + Long.getLong("s7.seed", 0L) + ",\"area\":\"" + area + "\",\"key\":\"" + key
                + "\",\"value\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}" + System.lineSeparator();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
