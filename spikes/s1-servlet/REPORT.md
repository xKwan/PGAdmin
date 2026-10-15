# Spike S1 (servlet variant, partial): Report

**Result: PARTIAL PASS on a workgroup (non-domain) Windows 11 workstation.**

Waffle 3.7.0's servlet filter (`waffle-jna-jakarta`) on **embedded Tomcat 11.0.24, with no Spring**:
- a real SSPI handshake authenticates the Windows account;
- SID and groups are available;
- DataAdmin is decided on the server from a group SID.

The controls that Spring Security would have provided, and that this project now owns, are working in the spike:
- the scheme filter;
- fail-closed sessions with ID rotation;
- cookie flags;
- error pages without server details.

One Waffle defect was found and mitigated (W8).

**S1 stays open** for everything that needs a domain:
- Kerberos and the SPN;
- domain groups and the real DataAdmin source (D1);
- the existing application's Waffle setup (A1).

## Environment

| Item | Value |
|---|---|
| Machine | Windows 11 Pro 10.0.26200, **not domain-joined**, local account |
| Java / Maven | Temurin 21.0.12.1+1 / Maven 3.9.16, project-local, offline (`mvn -o`) |
| Server | `tomcat-embed-core` 11.0.24 + `waffle-jna-jakarta` 3.7.0 |
| Client | Waffle client-side SSPI (`WindowsSecurityContextImpl`) over one raw HTTP/1.1 connection |

## Loop history (2026-10-15)

| Result file | Result | Notes |
|---|---|---|
| `s1s-20261015-200316.json` | **PASS 5/5** (14 tests per run) | Master seed `1445700817` |
| `s1s-20261015-200356.json` | **PASS 5/5** (14 tests per run) | Independent confirmation, same code. Master seed `775144927` |
| `s1s-20261015-201600.json` | **PASS 5/5** (14 tests per run) | Rerun after the runner script was rewritten (the session that made the change ended before it was recorded; no source change). Master seed `1237000057` |
| `s1s-20261015-201837.json` | **PASS 5/5** (14 tests per run) | After `garbageTokensAreRejected` was changed to always send `Basic` and `Bearer` first, so W8 is checked on every run (before, the schemes were random). Master seed `504577374` |

Earlier failing iterations while the spike was written were not recorded in a result file, and are not listed here.

## Findings

| # | Finding | Status | Plan impact |
|---|---|---|---|
| W2s | Negotiate and NTLM handshakes (2 legs) authenticate the current account, with the right SID and groups. On a workgroup machine Negotiate falls back to **NTLM**, so **Kerberos was not exercised**. | **Tested** (NTLM only) | Kerberos, SPN and domain groups remain **Untested in target environment** |
| W5s | Rejections hold: no `Basic` in the challenge; random and malformed tokens give 401, never 200 or 5xx; a replayed final token fails; a handshake split across connections fails; parallel handshakes all succeed. | **Tested** | Supports threat-model spoofing mitigations |
| W7s | DataAdmin from a configured group SID: a group the user is in gives 200, a random foreign SID gives **403**. | **Tested** (local groups) | The D1 source is still Unresolved |
| W8 | **Waffle defect:** an `Authorization: Basic …` or `Bearer …` header makes `NegotiateSecurityFilter` throw a `RuntimeException` in `SecurityFilterProviderCollection`, giving **HTTP 500**. | **Tested** (mitigated) | `AuthorizationSchemeFilter`, placed before Waffle, hides any other scheme so the request gets a normal 401 challenge. The garbage-token test sends `Basic` and `Bearer` on every run. Keep this filter and the test in Phase 4. |
| W9 | Waffle's `excludePatterns` are matched against the **full request URL** (`scheme://host/path`), not the servlet path. An unanchored or path-only pattern can match more, or less, than intended. | **Observed** (configuration written to match; no dedicated test) | The application should not need exclusions. Where one is unavoidable, anchor it as `^https?://[^/]+/prefix/…` and test it. |
| W10 | Session handling with no framework: after the first authentication, `AuthenticatedSessionFilter` calls `changeSessionId()` and stores the SID. A planted pre-authentication session ID then gives 401, and the new ID gives 200. The cookie is `HttpOnly` and `SameSite=Strict`. | **Tested** | Covers the Spring variant's W4 without Spring. `Secure` waits for TLS (P11/P12). |
| W11 | With `ErrorReportValve` set to show neither the report nor server info, a 404 body contains no server version and no stack trace, and there is no `Server` header. | **Tested** (404 only) | Production uses the same valve settings. Phase 4 adds the same check for 401, 403 and 500. |
| W12 | Fail-closed: `AuthenticatedSessionFilter` refuses any request without a `WindowsPrincipal`, because Waffle skips authentication when it believes it is not on Windows. | **Designed**; the bypass was not induced in a test | Phase 4: add a test that forces the bypass path |

## Still open for S1

| Item | Needed |
|---|---|
| Kerberos via Negotiate, SPN `HTTP/<host>`, delegation (S1b) | A domain-joined host and a domain test account |
| DataAdmin from the real source (D1), domain group SIDs | A D1 decision and a test group |
| Compatibility with the existing app's Waffle setup (A1/C2) | Inventory §3 |
| Test that induces the fail-closed path (W12) | Phase 4 |
| Servlet container packaging (embedded JAR vs WAR on Tomcat service) | ADR-0001 sub-decision, still Proposed |
