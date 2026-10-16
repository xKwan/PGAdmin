# Phase 11 Status: Security Hardening, E2E and Security Suite

> Updated 2026-10-16. Evidence: `docs/evidence/phase-11/`. Workstation only: Edge 155, PostgreSQL 18.6, NTLM loopback.
> The **offline install rehearsal on a clean VM** can't be done here (no VM): it is a target-environment item. The
> local intake and install rehearsal of the release bundle are part of Phase 12, once the install scripts exist.

## Delivered

| Item | Where |
|---|---|
| E2E journeys in Edge: DataAdmin (both servers, internal profile with banner) and non-DataAdmin (external only; the internal API refused with 403 from inside the page); unauthenticated access (Negotiate challenge, no data, no server details); app-session expiry (silent re-sign-in and one reload); audit outage (execution refused, sign-in still allowed per the approved policy, recovery); an object named with markup; no requests to other origins (checked in every test) | `e2e/tests/journeys.spec.js`, `editor.spec.js` |
| E2E environment: a second instance without DataAdmin and with a 1-minute idle timeout; an internal profile; a markup-named table; superuser access to the throwaway cluster for the outage test | `scripts/test/Invoke-E2E.ps1` |
| Security regression suite: oversized bodies on every POST route; markup in errors comes back as JSON text; secret-leak scan of logs, audit rows, spool and every HTTP response after a session with failures and an audit outage | `SecurityRegressionIT` |
| Every API route refuses a declared body over its limit, also routes that ignore the body (finding P11-1) | `ApiServlet` |
| Silent re-sign-in after expiry: `403 SESSION_RENEWED` instead of a CSRF error; the page reloads once (finding P11-2) | `CsrfFilter`, `SessionFilter`, `api/client.js` |
| Least-privilege verification script for pgweb's roles and the audit database: 60+ checks, exit code on failure | `deploy/sql/verify-privileges.sql`, `PrivilegesIT` |
| Dependency vulnerability scan (connected staging side): npm audit for both lockfiles, OSV for the backend SBOM; documented exceptions file | `offline/Invoke-VulnerabilityScan.ps1`, `offline/vulnerability-exceptions.json` |
| Username with markup rendered as text | `App.test.jsx` |
| Threat model reviewed: every item mitigated and tested, accepted with an owner, or a named deployment/target item | `docs/threat-model.md` |

## Acceptance criteria (plan Phase 11)

| Criterion | Result |
|---|---|
| All E2E and security tests pass | E2E **PASS 5/5**, master seed `1437144826`; security suites in the loop, **PASS 5/5**, master seed `972318771` |
| Authorisation matrix, CSRF and cross-origin, XSS in results, errors, object names and usernames, oversized bodies, throttling, headers and CSP, secret leaks | **Tested**: `AuthorizationMatrixIT`, `CsrfIT`, RTL + E2E, `SecurityRegressionIT`, `DbSessionIT` (failed-open limit, caps), `AuthIT`/`StaticContentIT` (headers, CSP) |
| Every threat-model item mitigated or formally accepted | **Done**. Accepted with owner: T12, T28, T29, T31 (re-confirmation requested after P7-5). Deployment and target items: T02 (SPN, HTTPS), T24 (auditor grants), T27 (ACLs), T33 (delegation scope) |
| No unresolved High/Critical dependency findings, or approved documented exceptions | **Not yet met.** Maven runtime (20 components): 0 findings. npm, e2e: 0. npm, frontend: 1 High, `node-forge` (GHSA-86w9-cpqp-85rv) via the build-only `@sonar/scan`; not shipped; no fix upstream. Documented as a **proposed** exception; **the stakeholder's approval is required** (`offline/vulnerability-exceptions.json`). **Resolved in Phase 12 without an exception:** the npm scanner was replaced by the SonarScanner for Maven, so `node-forge` is no longer a dependency (finding P12-7). |
| Sonar: 0 vulnerabilities, 100% hotspots reviewed | **OK**: 0 vulnerabilities and 0 security hotspots in both projects (so none unreviewed); backend coverage 92.3%, frontend 96.2%; 0 blocker/high issues |
| Offline install rehearsal using only the docs | **Untested in target environment** (no clean VM). Local rehearsal in Phase 12. |

## Findings

| # | Finding | Impact |
|---|---|---|
| P11-1 | Endpoints that never read a body (logout, heartbeat, cancel, database list) accepted an 8 MiB upload. Nothing read it, but the server still received it. | Every route now refuses a declared body over its limit with 413 |
| P11-2 | **Expired app session in a real browser:** the request got `401 Negotiate`, Edge signed in again by itself and resent it with the old session's CSRF token, so the user saw "Request blocked: missing or invalid CSRF protection". A first fix (`401 SESSION_RENEWED`) failed in Edge: a 401 on the request that completed an NTLM handshake makes Chromium send it again. Found only by the E2E journey and the audit trail. | `403 SESSION_RENEWED` when the failing request is the one that just signed in. The client reloads once. Nothing runs, and cross-site requests still fail the origin check first. |
| P11-3 | Two E2E expectations were wrong: during an audit outage a page reload keeps working, and a fresh sign-in is allowed. The approved policy makes only execution fail-closed (`docs/audit-policy.md`). | Tests now follow the policy |
| P11-4 | The privilege script first checked table-level INSERT for the runtime role; the role has a column-level grant, so the check now also proves the hash-chain columns can't be supplied | Stronger check |

## Test evidence

| What | Result | File |
|---|---|---|
| Randomised loop: frontend lint + 99 Vitest tests; backend 79 unit + 135 integration tests (including the security suites) with coverage | **PASS 5/5**, master seed `972318771` | `docs/evidence/phase-11/loop-20261016-131710.json` |
| End-to-end in Edge, 5 repetitions (11 tests each) | **PASS 5/5**, master seed `1437144826`, about 87 s per repetition (one test waits out a 1-minute idle timeout) | `docs/evidence/phase-11/e2e-20261016-132542.json` |
| Dependency scan | **FAIL** pending the stakeholder's approval of one proposed exception (see above) | `docs/evidence/phase-11/vuln-scan-20261016-122153.json` |
| SonarQube "pgweb gate", both projects | **OK**: 0 vulnerabilities and 0 security hotspots in both projects (so none unreviewed); backend coverage 92.3%, frontend 96.2%; 0 blocker/high issues | `docs/evidence/phase-11/sonar-20261016-133138.json` |
