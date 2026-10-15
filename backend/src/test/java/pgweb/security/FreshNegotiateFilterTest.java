package pgweb.security;

import org.junit.jupiter.api.Test;
import pgweb.audit.AuditService;
import pgweb.audit.AuditSpool;
import pgweb.config.Secret;
import pgweb.testsupport.MutableClock;
import pgweb.testsupport.ServletStubs;
import pgweb.web.Json;
import waffle.windows.auth.IWindowsAccount;
import waffle.windows.auth.IWindowsAuthProvider;
import waffle.windows.auth.IWindowsComputer;
import waffle.windows.auth.IWindowsDomain;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.IWindowsImpersonationContext;
import waffle.windows.auth.IWindowsSecurityContext;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The fresh-handshake filter with a scripted Windows auth provider (cases one local account can't produce). */
class FreshNegotiateFilterTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tmp;

    static final String SID = "S-1-5-21-1-2-3-1001";

    static UserContext user() {
        return new UserContext("CORP\\alice", SID, Set.of(), false, "Kerberos", "h", Instant.EPOCH, "127.0.0.1");
    }

    static AuditService audit() throws Exception {
        // An audit DB on a closed port: records fall back to the spool, as during an outage.
        pgweb.config.Config.Audit cfg = new pgweb.config.Config.Audit("jdbc:postgresql://127.0.0.1:9/none", "pgweb_app",
                new Secret("x".getBytes(StandardCharsets.UTF_8)), new Secret("k".repeat(32).getBytes(StandardCharsets.UTF_8)),
                java.nio.file.Path.of("spool"), java.util.Optional.empty(), java.util.Optional.empty(), false);
        return new AuditService(new pgweb.audit.AuditDatabase(cfg), new AuditSpool(Files.createTempDirectory(tmp, "spool"), Json.mapper(), new MutableClock(Instant.EPOCH)),
                AuditService.FailMode.OPEN, new Secret("k".repeat(32).getBytes(StandardCharsets.UTF_8)), Json.mapper(), false);
    }

    /** An identity with a SID; records disposal. */
    static IWindowsIdentity identity(String sid, AtomicBoolean disposed) {
        return (IWindowsIdentity) Proxy.newProxyInstance(FreshNegotiateFilterTest.class.getClassLoader(), new Class<?>[] {IWindowsIdentity.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getSidString" -> sid;
                    case "dispose" -> {
                        disposed.set(true);
                        yield null;
                    }
                    case "getGroups" -> new IWindowsAccount[0];
                    case "impersonate" -> (IWindowsImpersonationContext) () -> { };
                    default -> null;
                });
    }

    static IWindowsSecurityContext context(boolean cont, IWindowsIdentity id, AtomicInteger disposals) {
        return (IWindowsSecurityContext) Proxy.newProxyInstance(FreshNegotiateFilterTest.class.getClassLoader(), new Class<?>[] {IWindowsSecurityContext.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isContinue" -> cont;
                    case "getToken" -> new byte[] {1, 2, 3};
                    case "getIdentity" -> id;
                    case "dispose" -> {
                        disposals.incrementAndGet();
                        yield null;
                    }
                    default -> null;
                });
    }

    static IWindowsAuthProvider provider(IWindowsSecurityContext ctx, RuntimeException fail) {
        return new IWindowsAuthProvider() {
            @Override
            public IWindowsIdentity logonUser(String u, String p) {
                return null;
            }

            @Override
            public IWindowsIdentity logonDomainUser(String u, String d, String p) {
                return null;
            }

            @Override
            public IWindowsIdentity logonDomainUserEx(String u, String d, String p, int t, int pr) {
                return null;
            }

            @Override
            public IWindowsAccount lookupAccount(String n) {
                return null;
            }

            @Override
            public IWindowsComputer getCurrentComputer() {
                return null;
            }

            @Override
            public IWindowsDomain[] getDomains() {
                return new IWindowsDomain[0];
            }

            @Override
            public IWindowsSecurityContext acceptSecurityToken(String connectionId, byte[] token, String pkg) {
                if (fail != null) {
                    throw fail;
                }
                return ctx;
            }

            @Override
            public void resetSecurityToken(String connectionId) {
                // not used
            }
        };
    }

    static String token() {
        return "Negotiate " + Base64.getEncoder().encodeToString(new byte[] {9, 9, 9});
    }

    static jakarta.servlet.http.HttpServletRequest post(Map<String, String> headers, boolean withUser) {
        var req = ServletStubs.request("POST", "/api/db-sessions/external", headers, null, false);
        if (withUser) {
            req.setAttribute(UserContext.ATTRIBUTE, user());
        }
        return req;
    }

    @Test
    void theMatchingIdentityIsHandedToTheRequestAndDisposedAfterwards() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean();
        AtomicInteger ctxDisposed = new AtomicInteger();
        IWindowsIdentity id = identity(SID, disposed);
        FreshNegotiateFilter f = new FreshNegotiateFilter(provider(context(false, id, ctxDisposed), null), Set.of("Negotiate"), audit());
        ServletStubs.Chain chain = new ServletStubs.Chain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                super.doFilter(rq, rs);
                assertThat(rq.getAttribute(FreshNegotiateFilter.IDENTITY_ATTRIBUTE)).isSameAs(id);
            }
        };
        var req = post(Map.of("Authorization", token()), true);
        f.doFilter(req, new ServletStubs.Recorded().response, chain);
        assertThat(chain.seen).isNotNull();
        assertThat(disposed).isTrue();
        assertThat(ctxDisposed.get()).isEqualTo(1);
        assertThat(req.getAttribute(FreshNegotiateFilter.IDENTITY_ATTRIBUTE)).isNull();
    }

    @Test
    void anotherUsersIdentityIsRefused() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean();
        FreshNegotiateFilter f = new FreshNegotiateFilter(provider(context(false, identity("S-1-5-21-9-9-9-9", disposed), new AtomicInteger()), null),
                Set.of("Negotiate"), audit());
        ServletStubs.Recorded res = new ServletStubs.Recorded();
        ServletStubs.Chain chain = new ServletStubs.Chain();
        f.doFilter(post(Map.of("Authorization", token()), true), res.response, chain);
        assertThat(chain.seen).isNull();
        assertThat(res.status).isEqualTo(403);
        assertThat(res.text()).contains("IDENTITY_MISMATCH");
        assertThat(disposed).isTrue();
    }

    @Test
    void continuationLegsAreChallengedWithTheServerTokenAndTheContextIsKept() throws Exception {
        AtomicInteger ctxDisposed = new AtomicInteger();
        FreshNegotiateFilter f = new FreshNegotiateFilter(provider(context(true, null, ctxDisposed), null), Set.of("Negotiate", "NTLM"), audit());
        ServletStubs.Recorded res = new ServletStubs.Recorded();
        f.doFilter(post(Map.of("Authorization", "NTLM " + Base64.getEncoder().encodeToString(new byte[] {1})), true), res.response, new ServletStubs.Chain());
        assertThat(res.status).isEqualTo(401);
        assertThat(res.headers.get("WWW-Authenticate")).isEqualTo("NTLM " + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        assertThat(ctxDisposed.get()).isZero();
    }

    @Test
    void missingMalformedOrRejectedTokensGetAChallenge() throws Exception {
        FreshNegotiateFilter ok = new FreshNegotiateFilter(provider(context(false, identity(SID, new AtomicBoolean()), new AtomicInteger()), null),
                Set.of("Negotiate"), audit());
        FreshNegotiateFilter failing = new FreshNegotiateFilter(provider(null, new IllegalStateException("bad token")), Set.of("Negotiate"), audit());
        for (var c : new Object[][] {
                {ok, Map.of(), true}, {ok, Map.of("Authorization", "Negotiate"), true}, {ok, Map.of("Authorization", "Basic abc"), true},
                {ok, Map.of("Authorization", "Negotiate !!!"), true}, {ok, Map.of("Authorization", token()), false},
                {failing, Map.of("Authorization", token()), true}}) {
            ServletStubs.Recorded res = new ServletStubs.Recorded();
            ServletStubs.Chain chain = new ServletStubs.Chain();
            @SuppressWarnings("unchecked")
            Map<String, String> h = (Map<String, String>) c[1];
            ((FreshNegotiateFilter) c[0]).doFilter(post(h, (Boolean) c[2]), res.response, chain);
            assertThat(chain.seen).as(h.toString()).isNull();
            assertThat(res.status).isEqualTo(401);
            assertThat(res.text()).contains("DELEGATION_HANDSHAKE");
        }
    }

    @Test
    void otherMethodsPassThrough() throws Exception {
        FreshNegotiateFilter f = new FreshNegotiateFilter(provider(null, new IllegalStateException()), Set.of("Negotiate"), audit());
        ServletStubs.Chain chain = new ServletStubs.Chain();
        f.doFilter(ServletStubs.request("GET", "/api/profiles/external/databases", Map.of(), null, false), new ServletStubs.Recorded().response, chain);
        assertThat(chain.seen).isNotNull();
    }
}
