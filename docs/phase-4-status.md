# Phase 4 Status: Sign-in, Authorisation, Sessions, CSRF, Audit Service

> Updated 2026-10-16. Evidence: `docs/evidence/phase-4/`.
> Workstation only: Windows 11 Pro, not domain-joined. Real sign-in was tested with Waffle and NTLM, using the account running the tests. **Kerberos, domain groups and the SPN are Untested in target environment** (`docs/target-environment-checklist.md` K1–K6).

## Request pipeline

Every request passes these filters, in order (`pgweb.Application`). Each one runs on the `REQUEST` dispatch only.

| # | Filter | What it enforces |
|---|---|---|
| 1 | `RequestIdFilter` | A fresh 16-hex request ID in logs, the `X-Request-Id` header and error envelopes; a client-supplied ID is ignored |
| 2 | `SecurityHeadersFilter` | CSP (`default-src 'none'` for the API; a nonce-based CSP for the SPA from Phase 5), `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer`, COOP/CORP, `Permissions-Policy`, HSTS under TLS |
| 3 | `AuthSchemeFilter` | Hides `Basic`/`Bearer`/other schemes (Waffle answers them with 500: W8). In Kerberos-only mode (production) it refuses NTLM, raw or inside SPNEGO, with `401 KERBEROS_REQUIRED`. Records the mechanism. |
| 4 | `WaffleAuthenticationFilter` | Waffle `NegotiateSecurityFilter`: Negotiate (plus NTLM in test mode), no guest, no impersonation. `/api/health` and `/api/ready` bypass it by **exact** path. |
| 5 | `SessionFilter` | **Fail-closed:** no `WindowsPrincipal` → 401. **Session fixation:** the ID is changed at sign-in and the session is bound to the SID; a different SID in a bound session invalidates it. **Absolute lifetime** 8 h (idle 30 min is the container's). **Allowed domains.** Builds `UserContext`, with DataAdmin evaluated **on every request** from the token's group SIDs. Audits `app_login`, `app_logout` (expiry) and `authz_denied` (domain). |
| 6 | `CsrfFilter` | Unsafe methods need an own-origin `Origin` (or `Sec-Fetch-Site: same-origin`) **and** the session's `X-CSRF-Token`, compared in constant time. Refusals are audited. |
| 7 | `AccessFilter` | Each route's declared `Access` (`PUBLIC`, `AUTHENTICATED`, `DATA_ADMIN`). An undeclared route, or `PUBLIC` outside the two public paths, is **denied**. Refusals are audited. |

## Endpoints added

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/me` | Authenticated | Account, SID, DataAdmin, mechanism, capabilities (`internal` only for DataAdmin), session times, CSRF token |
| `POST /api/logout` | Authenticated | Ends the app session (DB sessions follow in Phase 6); audited |

## Audit service

- `require()` is for records that must exist before an operation, such as SQL execution intents (Phase 7). With `audit.failMode=closed` (the default, D4), a failed write refuses the operation with `503 AUDIT_UNAVAILABLE`. With `open`, the record is spooled and the operation continues.
- `record()` is best effort (login, logout, denials, outcomes). It never blocks the user: a failed write goes to the spool.
- **Spool:** `audit-spool.jsonl` in `audit.spoolDir`, hash-chained line by line, so edits, removals and reordering are detected. A background job replays it every 30 s once the DB is back, in one transaction, and keeps the replayed file. Every failure is logged as `audit_failure` (for monitoring, P12).
- **Session correlation:** the session ID is stored as an HMAC (`app_session_hash`), never in clear. SQL text will be stored as an HMAC (`sqlHmac`, AUD-03).

## Acceptance criteria (plan Phase 4)

| Criterion | Result | Test |
|---|---|---|
| 100% of non-public endpoints reject anonymous requests | **Tested:** a matrix generated from the endpoint inventory (every route × anonymous/user/DataAdmin × GET/POST) | `AuthorizationMatrixIT` |
| Non-DataAdmin gets 403 on every DataAdmin endpoint via the direct API | **Tested** with a test DataAdmin route; the internal-PostgreSQL endpoints arrive in Phase 6 and are covered by the same matrix | `AuthorizationMatrixIT` |
| Forged identity fields are ignored | **Tested:** `X-Roles`, `X-Data-Admin`, `X-Forwarded-User`, query and body fields | `AuthorizationMatrixIT.forgedIdentityFieldsAreIgnored` |
| Session fixation; expiry | **Tested:** the pre-sign-in ID is replaced and refused; a session reused under another SID is invalidated; the 8 h lifetime is enforced and audited | `AuthorizationMatrixIT`, `AuthIT` |
| CSRF missing or invalid → 403; cross-origin rejected | **Tested:** 8 forged variants plus PUT/DELETE/PATCH; none reached the handler; all audited | `CsrfIT` |
| Header assertions | **Tested** on 200, 401 and 404 responses; HSTS only under TLS | `AuthIT`, `FilterUnitTest` |
| Audit DB down → `AUDIT_UNAVAILABLE` on an audited operation | **Tested** with a test-only audited endpoint: 503, and the operation is not performed | `AuditServiceIT` |
| Spool replay | **Tested:** records spooled during the outage, replayed, chain intact; every field survives the round trip | `AuditServiceIT` |
| Chain verification detects an edit or delete | **Tested** (Phase 3) | `AuditSchemaIT` |
| Mock auth refused under prod | **Tested:** `Application.start` throws with an auth override under `prod`; the mock provider exists only in test scope | `AuthIT.mockAuthenticationIsRefusedInProdMode` |
| Audit records include the §6 fields for login, logout and denial | **Tested:** identity, SID, DataAdmin, session hash, request ID, client IP, reason | `AuthIT`, `AuthorizationMatrixIT`, `AuditServiceIT` |
| Real Windows sign-in | **Tested (NTLM, local account):** SID, groups, DataAdmin true/false by configured SID, `HttpOnly` + `SameSite=Strict` cookie, W8 schemes give 401, Kerberos-only mode refuses NTLM. **Untested in target environment:** Kerberos. | `AuthIT` |
| Security-critical coverage ≥ 90/85 (`security`, `authz`, `audit`) | **Met** (JaCoCo check in every loop run) | loop reports |
| Sonar: 0 vulnerabilities, hotspots reviewed | **Gate OK** | `sonar-*.json` |

## Test evidence

| Check | Result | File |
|---|---|---|
| Randomised loop: frontend lint + Vitest; backend 51 unit + 58 integration tests, JaCoCo thresholds (`security`, `authz`, `audit` ≥ 90/85) | **PASS 5/5**, master seed `964826653` | `docs/evidence/phase-4/loop-20261016-014524.json` |
| SonarQube "pgweb gate" | **OK** for both projects; backend coverage 91%; 0 blocker/high issues | `docs/evidence/phase-4/sonar-20261016-014546.json` |

## Findings

| # | Finding | Impact |
|---|---|---|
| P4-1 | The first connection attempt after an audit DB outage can still fail: Hikari's connection attempt was already in flight while the DB was refusing. | The scheduled replay simply retries; the tests wait for it instead of assuming the first attempt succeeds |
| P4-2 | Waffle keeps its principal in the HTTP session and reuses any existing session | Session ID rotation and SID binding in `SessionFilter` are required, not optional (covers the S1 W4/W10 findings) |
| P4-3 | Sonar found 10 maintainability issues (duplicated JSON field names, a field/constant name clash) on the first analysis | Fixed; nothing suppressed |
