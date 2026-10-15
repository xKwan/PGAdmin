# Phase 6 Status: Database Connections and Session Management

> Updated 2026-10-16. Evidence: `docs/evidence/phase-6/`. **No SQL execution yet** (`execution.enabled=false`; Phase 7).
> Workstation only, PostgreSQL 18.6. The production connection path was tested end to end with this machine's account (NTLM loopback). **Kerberos and constrained delegation to another host are Untested in target environment** (checklist K1–K4).

## How a connection is opened (ADR-0002)

1. The browser calls `POST /api/db-sessions/{internal|external}` with `{"database": "..."}`. The body can't name a host, port, role or user: such fields don't exist and are rejected as unknown.
2. The access filter checks the route: **internal requires DataAdmin**.
3. `FreshNegotiateFilter` answers `401 Negotiate` until the request carries its own Windows token, then completes the handshake with Waffle. The resulting identity must have the **same SID** as the app session's user (otherwise `403 IDENTITY_MISMATCH`, audited).
4. `SspiDelegatingConnector` impersonates that identity on the request thread **only while pgJDBC logs on with SSPI** (`gsslib=sspi`, no password), for the main connection and then the companion. It reverts in `finally`; the identity is disposed when the request ends.
5. Post-connect checks: `session_user` must equal the mapped role (lower-cased `sAMAccountName`, or an override), and the role must not be a superuser. Otherwise the connection is closed and refused (`ROLE_MISMATCH`, `SUPERUSER_REFUSED`).
6. The session gets a random 128-bit ID and is bound to the SID **and** the app session.

Listing databases (`POST /api/profiles/{profile}/databases`) works the same way, on a short-lived connection to the maintenance database. It returns databases where `has_database_privilege(session_user, …, 'CONNECT')` is true (owned, granted, or PUBLIC: ADR-0003).

## Delivered

| Item | Where |
|---|---|
| Role mapping, with overrides (`identity.roleCase`, `identity.roleOverrides`) | `pgweb.identity.RoleMapper`, config |
| Fresh Windows handshake per connection request; identity SID must match the session | `pgweb.security.FreshNegotiateFilter` |
| Production connector: impersonate, SSPI logon, revert; profile TLS settings; URL-encoded database names | `pgweb.dbsession.SspiDelegatingConnector` |
| Session manager: per-user and global caps, failed-open rate limit (5 per 5 min), ownership checks (other users and other app sessions get 404), DataAdmin re-checked on **every** use of an internal session, degraded session when the companion fails | `pgweb.dbsession.DbSessionManager` |
| Reaper: idle (15 min), idle in transaction (10 min), abandoned (no heartbeat for 120 s while in a transaction), maximum transaction age (60 min), lost connection | `DbSessionManager.reap()` (every 10 s) |
| App-session expiry cascade (logout, timeout, 8 h lifetime, invalidation) and graceful shutdown | `AppSessionListener`, `DbSessionManager.close()` |
| API: profiles, databases, open, list, get, close, heartbeat | `DbSessionServlets` |
| Audit: `db_connect_attempt`, `db_connect_result` (success/failure/refused with reason), `db_companion_failed`, `db_disconnect`, `session_reaped`, `session_lost`, `metadata_browse` (database list) | |
| Frontend: database chooser (no credentials anywhere), session bar (database, role, version, PID, "Limited" when degraded), heartbeat every 30 s, lost/ended handling, disconnect | `features/connections/` |

## Acceptance criteria (plan Phase 6, adapted to ADR-0002/0003)

| Criterion | Result | Test |
|---|---|---|
| Sessions connect as the mapped role; a role in the request is rejected | **Tested** | `DbSessionIT` |
| Production path: Windows sign-in, fresh handshake, impersonation, SSPI logon, no password | **Tested (NTLM loopback, one account)**; the server logs `method=sspi` | `DelegatedSessionIT` |
| Wrong identity in the handshake is refused | **Tested** with a scripted provider (one account can't produce another) | `FreshNegotiateFilterTest` |
| Non-DataAdmin direct API → 403 | **Tested:** open, list, and use of an existing session after the group is removed from the token | `DbSessionIT`, `AuthorizationMatrixIT` |
| Superuser refused | **Tested** for sessions and database listing | `DbSessionIT` |
| No CONNECT privilege → generic message (no host, role or database revealed) | **Tested** | `DbSessionIT` |
| Failed opens are rate-limited | **Tested** for opening and listing | `DbSessionIT`, `DbSessionEdgeIT` |
| Host and port can't be overridden | **Tested:** 8 field names rejected as unknown | `DbSessionIT` |
| Another user (or another app session of the same user) gets 404 for every operation | **Tested**, including 20 random ID guesses | `DbSessionIT` |
| Caps enforced | **Tested:** per user (409) and global (503) | `DbSessionIT`, `DbSessionEdgeIT` |
| Idle, idle-in-transaction, abandonment and transaction-age reaping remove the backends from `pg_stat_activity` | **Tested** with a controllable clock | `DbSessionIT`, `DbSessionEdgeIT` |
| App-session end closes DB sessions | **Tested** (logout; shutdown) | `DbSessionIT`, `DbSessionEdgeIT` |
| Cluster restart → `SESSION_LOST`, no reuse | **Tested** | `DbSessionEdgeIT` |
| Companion unavailable → degraded, visible and audited | **Tested** | `DbSessionIT`, `connections.test.jsx` |
| No credentials anywhere | **Tested:** there are none to leak; views contain no credential fields; the UI has no password field | `DbSessionEdgeIT`, `connections.test.jsx` |
| `dbsession` coverage ≥ 90/85 | **Met** (JaCoCo check) | loop reports |
| Frontend RTL for every connection state | **Tested** | `connections.test.jsx` |

## Findings

| # | Finding | Impact |
|---|---|---|
| P6-1 | For multi-leg handshakes (NTLM), Waffle keeps the server security context between legs. Disposing it after sending the challenge breaks the next leg ("No credentials are available in the security package"). | The filter keeps the context while the handshake continues, and disposes it only when complete or failed |
| P6-2 | Stopping Tomcat ends its app sessions first, so DB sessions are closed by the session listener (`app_session_ended`) before the manager's own shutdown sweep | Both paths are audited; either way no session survives a shutdown |
| P6-3 | `401` with `DELEGATION_HANDSHAKE` reaching JavaScript means the browser couldn't do Windows sign-in for the connection (not an expired app session) | The client shows it as an error instead of reloading the page |
| P6-4 | **Evidence correction.** The frontend Sonar scans of Phases 4 and 5 never reached the server, so their exports showed the Phase 3 frontend gate. On the first real scan of this code, the gate failed: one high reliability issue (`client.js`, a 401 handler declared with no parameter but called with the error; fixed) and two false positives on the ARIA window splitter (`Splitter.jsx`, a focusable `separator` is an interactive widget; suppressed inline with the reason). | `Export-SonarGate.ps1` now records each project's analysis date and revision, and refuses to export when the analysis is older than the newest source file. Phase 4 and 5 status files carry a correction note. |
| P6-5 | The randomised loop found two test defects. **CsrfIT** built its "wrong token" by replacing the first character with `x`, which was the real token whenever that already started with `x` (1 in 64). In **connections.test.jsx**, the random database names could contain a tab, which accessible-name matching collapses. | Both fixed in the tests; no product change |

## Test evidence

| What | Result | File |
|---|---|---|
| Randomised loop: frontend lint + 38 Vitest tests with coverage; backend 60 unit + 89 integration tests (real PostgreSQL 18.6, real SSPI) | **PASS 5/5**, master seed `1052189553` | `docs/evidence/phase-6/loop-20261016-061407.json` |
| Earlier loops | FAIL (temp folders left by two unit tests), FAIL (P6-5), then PASS 5/5 (master seed `1467699300`) before the P6-4 fixes changed the code | `loop-20261016-023920.json`, `loop-20261016-024556.json`, `loop-20261016-025911.json` |
| SonarQube "pgweb gate", both projects, analyses newer than every source file | **OK** for both projects; backend coverage 92%, frontend 95.9%; 0 blocker/high issues | `docs/evidence/phase-6/sonar-20261016-061728.json` |
