# Spike S1 (partial): Report

**Result: PARTIAL PASS on a workgroup (non-domain) Windows 11 workstation.**

Waffle 3.7.0 works with **Spring Boot 4.1 / Spring Security 7** and with **Spring Boot 3.5 / Spring Security 6**, using identical application code:
- a real SSPI handshake authenticates the Windows account;
- SID and groups are available;
- DataAdmin is decided on the server from a group SID.

Two integration defects were found and fixed in the spike configuration (W3, W4).

**S1 stays open** for everything that needs a domain:
- Kerberos and the SPN;
- domain groups and the real DataAdmin source (D1);
- Edge on a domain-joined host;
- the existing application's Waffle setup (A1).

## Environment

| Item | Value |
|---|---|
| Machine | Windows 11 Pro 10.0.26200, **not domain-joined**, local account (member of Administrators; token is UAC-filtered for network logon) |
| Java / Maven | Temurin 21.0.12.1+1 / Maven 3.9.16, project-local, offline (`mvn -o`) |
| Line A | Spring Boot **4.1.1** + `waffle-spring-security7` **3.7.0** (built against Spring Security 7.1.1) |
| Line B | Spring Boot **3.5.16** + `waffle-spring-security6` **3.7.0** |
| Client | Waffle client-side SSPI (`WindowsSecurityContextImpl`) over one raw HTTP/1.1 connection |

## Loop history (2026-10-15)

| Run | Result | Cause | Fix |
|---|---|---|---|
| first `mvn -o test` | Build error | Surefire resolves `junit-platform-launcher` at test time; `dependency:go-offline` didn't fetch it (W6) | Declared the launcher as a test dependency |
| seed 11 (Boot 4) | FAIL: 11/12 | An authenticated non-DataAdmin user got **401** instead of 403 on `/admin/ping` | Investigated (W3) |
| seed 11 (Boot 4 and 3) | FAIL: 11/12 on both | Same on Spring Boot 3.5, so not version-specific | — |
| seed 12, error dispatch permitted | FAIL: 11/12 on both | The trace showed the 403 *was* produced. The container then forwarded to `/error`, Waffle ran again on that forward with the already-used NTLM token ("The token supplied to the function is invalid"), and replaced the 403 with a 401. | Waffle now runs only on the original `REQUEST` dispatch (`RequestDispatchOnly`), and the ERROR dispatch is permitted |
| seed 13 | PASS: 12/12 on both | — | — |
| `s1-20261015-091658.json` | **PASS 5/5** (24 tests per run, both lines) | Final code | Master seed `702227710` |
| `s1-20261015-091821.json` | **PASS 5/5** (24 tests per run, both lines) | Independent confirmation, same code | Master seed `1895763992` |
| `s1-20261015-092034.json` | **PASS 5/5** (24 tests per run, both lines) | Rerun after the runner stopped recording the Windows account name (no test change) | Master seed `890523904` |

## Findings

| # | Finding | Status | Plan impact |
|---|---|---|---|
| W1 | Waffle 3.7.0 publishes `waffle-spring-security7` (built against Spring Security 7.1.1) and Spring Boot 4 starters. The same configuration code compiles and passes on Boot 4.1.1/Security 7 and Boot 3.5.16/Security 6. | **Tested** (workgroup) | Resolves the "unknown Spring Security 7 support" part of A2. ADR-0001 can choose Boot 4.1.x. Boot 3.5 remains a tested fallback, e.g. to match the existing app (A1). |
| W2 | Negotiate and NTLM handshakes (2 legs) authenticate the current account. The server sees the right `DOMAIN\user`, the user SID, and group SIDs including BUILTIN\Users and Everyone. On a workgroup machine Negotiate falls back to **NTLM** (the first client token is NTLMSSP), so **Kerberos was not exercised**. | **Tested** (NTLM only) | Kerberos, the SPN and domain groups remain **Untested in target environment** |
| W3 | **Defect in the obvious wiring (both Spring lines):** when access is denied, the container forwards to `/error`. Waffle then runs again on that forward, re-processes the consumed NTLM token, fails, and returns **401** instead of 403. A browser would then renegotiate instead of showing "forbidden". | **Tested** | Required configuration: run Waffle on `REQUEST` dispatch only, and permit the `ERROR` dispatch. Add both to the Phase 4 security tests. |
| W4 | Waffle sets the `SecurityContext` for the current request only. With Spring Security 6/7 (explicit save), **no session is created**, so every request repeats the NTLM handshake. | **Observed** on both lines (recorded each run, not asserted) | The plan's application session (30 min idle / 8 h absolute, CSRF token, session-bound DB sessions) needs an explicit step: after Waffle authenticates, save the context to the session, change the session ID (fixation), and bind the session to the SID. That is Phase 4 work; it is not available out of the box. |
| W5 | Rejections hold on both lines: no `Basic` offered; random and malformed tokens (including fake NTLMSSP headers) give 401, never 200 or 5xx; a replayed final token fails; a handshake split across connections fails; 2–8 concurrent handshakes all succeed. | **Tested** | Supports threat-model spoofing mitigations |
| W6 | `mvn dependency:go-offline` doesn't capture artifacts that Surefire resolves at run time (`junit-platform-launcher`) | **Tested** | **S4/offline bundle rule:** declare the launcher explicitly, and verify every bundle with a full `mvn -o verify` before transfer |
| W7 | DataAdmin from a configured group SID: the configured group gives 200; a foreign SID gives **403**, even with spoofed `X-Roles`/`X-Data-Admin`/`X-Forwarded-User` headers. The decision uses only the token's group SIDs. | **Tested** (local groups) | The D1 source (AD group or the existing app's role store) is still Unresolved |

## Still open for S1

| Item | Needed |
|---|---|
| Kerberos via Negotiate, SPN `HTTP/<host>`, delegation (S1b) | A domain-joined host and a domain test account |
| DataAdmin from the real source (D1), domain group SIDs | A D1 decision and a test group |
| Edge integrated auth (intranet zone / `AuthServerAllowlist`) | A domain-joined desktop or runner (S6) |
| Compatibility with the existing app's Waffle setup (A1/C2) | Inventory §3 |
| Session persistence design (W4) | Phase 4 |
