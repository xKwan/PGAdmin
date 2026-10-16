# Threat Model (draft)

> **Phase:** 0. **Method:** STRIDE over the data flows below.
> **Status of every mitigation: Planned** (none is implemented or tested). A threat is closed only when its verifying test has run and passed (P11 acceptance), or when a named owner formally accepts the risk.

## 1. Scope and trust boundaries

```
[Browser] --TB1 (HTTPS, Negotiate, session cookie, CSRF)--> [pgweb service]
[pgweb service] --TB2 (pgJDBC, Kerberos delegated as the user)--> [Internal PG :5433]
[pgweb service] --TB3 (pgJDBC, Kerberos delegated as the user)--> [External PG :5432]
[pgweb service] --TB4 (pgweb_app, INSERT-only)--> [Audit DB]
[pgweb service] --TB5 (local filesystem)--> [config, logs, audit spool]
[Air-gap intake] --TB6 (media transfer)--> [build/CI toolchain and dependencies]
```

**Assets:** users' delegated Kerberos identity (during connect only); data in target databases; audit records; the app's own secrets (`pgweb_app` password, HMAC key, TLS key); session identifiers; build artifacts.

**Actors:** authenticated non-DataAdmin user; DataAdmin user; unauthenticated internal network user; malicious web page visited by an authenticated user; DBA or superuser on a PostgreSQL instance; compromised dependency; server administrator.

## 2. Threats

| ID | STRIDE | Threat | Mitigation (plan ref) | Phase | Verifying test | Status |
|---|---|---|---|---|---|---|
| T01 | S | Client claims a different identity via request fields | Identity only from the Waffle security context; identity/role fields rejected (§2.4) | P4 | Forged-field authorisation tests | Tested (P4): forged headers, query and body fields ignored (`AuthorizationMatrixIT`) |
| T02 | S | NTLM relay or downgrade instead of Kerberos | SPN registered; HTTPS; no reverse proxy; Kerberos confirmed in S1 | P1, P12 | S1 report; deployment check | Planned / Unresolved (A3) |
| T03 | S, E | Non-DataAdmin user reaches internal PostgreSQL by calling the API directly | Server-side DataAdmin check on every internal request (§5) | P4, P6 | Direct-API authorisation matrix | Tested (P4) for the access framework; internal endpoints arrive in P6 under the same matrix |
| T04 | S | User connects to internal PostgreSQL as another user's role | Role name fixed by the server-side mapper; `session_user` checked after connecting (§2.4) | P6 | Mapping and `session_user` tests | Tested (P6): role fixed by the server-side mapper; session_user verified (`DbSessionIT`) |
| T05 | T, E | Cross-site request forges a state change using ambient Negotiate credentials | CSRF token, Origin/`Sec-Fetch-Site` check, `SameSite=Strict`, side-effect-free GET (§5) | P4 | CSRF and cross-origin tests | Tested (P4): Origin + synchronizer token; 8 forged variants refused and audited (`CsrfIT`) |
| T06 | I, E | One user uses another user's DB session | Random 128-bit IDs bound to SID and app session; 404 on mismatch (§4) | P6 | Cross-user session tests | Tested (P6): 128-bit IDs bound to SID and app session; 404 otherwise (`DbSessionIT`) |
| T07 | E | Shared login plus `SET ROLE` lets users `RESET ROLE` to higher privileges | Not used; users' own credentials only (§2.4, D3) | P6 | Design review; role tests | Tested (P6): users connect as their own role; no shared login |
| T08 | E | Superuser session bypasses all app controls (`COPY … PROGRAM`, audit tampering) | Superuser sessions refused by default (D14c) | P6 | Superuser refusal test | Tested (P6): superuser sessions refused (`DbSessionIT`) |
| T09 | I | SSRF or port scanning via user-supplied host/port; pointing "external" at 5433 | Host and port from server config only; request fields rejected (§2.5) | P6 | Override-rejection tests | Tested (P6): host/port fields rejected; server-side profiles only |
| T10 | I | Credentials leak through logs, errors, responses, browser storage or heap dumps | **No user passwords exist** (Kerberos, ADR-0002). Remaining secrets: the app's own audit-DB password and HMAC key, and Kerberos tokens (never stored; disposed after connect). Redaction; heap dumps disabled. | P3, P6 | Secret-grep across logs, responses and audit | Planned |
| T11 | S | The app is used to probe databases or hammer PostgreSQL with connection attempts | No passwords to guess (Kerberos). Failed opens rate-limited per user (5 per 5 min) and audited. | P6 | Rate-limit test | Tested (P6): failed opens rate-limited and audited |
| T12 | I | Data intercepted between app and PostgreSQL | TLS `verify-full` where supported (D2); Kerberos authentication itself never sends a password | P6 | TLS configuration test | Residual risk if no TLS; owner: stakeholder |
| T13 | T | XSS through result values, error messages, object names or usernames | React escaping; `dangerouslySetInnerHTML` banned; strict CSP (§5) | P5, P8, P11 | XSS payload tests | Tested (P8) for result values, column names and errors: rendered as text in RTL and in Edge (E2E), no script runs, no CSP violation. Object names and the metadata tree in P10, hardening review in P11 |
| T14 | T | SQL injection in app-generated metadata or audit SQL | Parameterised statements; quoted identifiers from catalog or allow-list; ArchUnit rule (§5) | P3, P10 | Injection-payload identifier tests; ArchUnit | Planned |
| T15 | D | Long-running or huge queries exhaust DB or app resources | Wall-clock timeout plus cancel and escalation; row, byte and cell limits; session caps (§3.4, §3.5) | P7 | Timeout, limit and heap tests | Tested (P7): wall-clock timeout unaffected by user `SET`; row, byte and cell limits. Residual: up to `maxRows + 1` rows buffered per statement |
| T16 | D | Cancellation fails and the query keeps running | Escalation via companion `pg_terminate_backend`; quarantine; DBA backstops (§3.5) | P7 | Resource-release measurements | Tested (P7, P9): cancel releases locks, also while blocked inside a transaction; escalation via the companion terminates the backend; quarantine; closing a session cancels first. Residual (P7-2): a degraded session's backend runs until its statement ends on Windows; DBA `statement_timeout` backstop recommended |
| T17 | D | Abandoned transactions hold locks | Idle-in-transaction timeout; heartbeat abandonment; max transaction age (§4) | P6, P9 | Reaper and abandonment tests | Tested (P6, P9): idle-in-transaction timeout with a warning first; heartbeats alone no longer keep an idle transaction alive (P9-1); maximum transaction age; app-process kill releases locks |
| T18 | D | Connection exhaustion on target instances | Per-user and global caps; per-role `CONNECTION LIMIT` | P6 | Cap tests | Tested (P6): per-user and global caps |
| T19 | T | Multi-statement text executes statements the user didn't intend, or misreports outcomes | Parser gate or server-enforced single statement; no hand-written splitter (§3.1, §3.2) | P1, P7 | S2 corpus; S3 server-log proof | Tested (P7): parser gate + pgJDBC cross-check, corpus against the real server, server-log proof that rejected text was never sent, NUL refused (P7-3) |
| T20 | R | User denies having executed a statement | Intent record written before execution; outcome after; hash chain (§6) | P4, P7 | Audit tests | Tested (P7): intent committed before the statement is sent; outcome linked by `intent_id` |
| T21 | R, T | Audit records altered or deleted | INSERT-only runtime role; UPDATE/DELETE trigger; hash chain; partition exports with manifests (§6) | P3, P4 | Tamper and chain-verification tests | Planned. Only tamper-*evident* if co-hosted with superusers (D4). |
| T22 | R | Execution happens without an audit record during an audit outage | Fail-closed (D4); spool for outcomes only | P4, P7 | Audit-DB-down test | Tested (P7): fail-closed refuses execution (nothing in the server log); outcomes spooled and replayed |
| T23 | I | Audit records expose secrets or personal data contained in SQL literals | Normalised text plus HMAC; no raw text by default (§6) | P7 | Literal-absence assertions | Tested (P7): literal and comment absence asserted; no text stored with `standard_conforming_strings = off` |
| T24 | I | Unauthorised reading of audit records | `pgweb_audit_reader` granted to named auditors only; spool ACLs | P3, P12 | Privilege-verification SQL | Planned |
| T25 | T | Malicious or vulnerable dependency enters through the air-gap intake | SBOM; vulnerability scan at intake; SHA-256 manifest; second-person check; lockfile integrity (§7.1) | P2 | `deps:verify`; intake record | Planned |
| T26 | E | Mock authentication provider enabled in production | Test/dev-only module, not packaged; startup refusal under `prod` | P4 | Startup-refusal test | Tested (P4): auth override refused in prod; mock provider in test scope only |
| T27 | I | App secrets (HMAC key, `pgweb_app` password, TLS key) exposed on the server | NTFS ACLs; DPAPI encryption; never in the repo | P3, P12 | Config review; secret-grep of repo | Planned |
| T28 | E | Revoked DataAdmin user keeps access | DataAdmin re-checked on every request; absolute session cap (8 h) | P4 | Session-cap test | Tested (P4): DataAdmin evaluated per request; 8 h absolute session lifetime. Revocation delay up to the session cap is accepted residual risk. |
| T29 | I | Authorised user reads all data their role permits (bulk exfiltration) | Out of app scope: data owners apply RLS and column grants; audit records the activity | — | — | Accepted risk (owner: stakeholder) |
| T30 | T | Clickjacking of the UI | `frame-ancestors 'none'` | P4 | Header test | Tested (P4): `frame-ancestors 'none'` and `X-Frame-Options: DENY` |
| T33 | E | Delegated Kerberos credentials misused: the service account could act toward PostgreSQL as any user who logs on | Constrained delegation limited to `POSTGRES/<host>` SPNs; impersonation only during connect and reverted in `finally`; token never stored; SID of the open handshake must match the session (ADR-0002) | P6 | Impersonation-scope and SID-mismatch tests | Tested (P6) on one machine: impersonation only during connect, SID must match; delegation scope is a target-environment item (K3) |
| T31 | D / I | A memory-safety bug in the native SQL parser (libpg_query DLL, which runs inside the backend's process on untrusted SQL text) crashes the backend or leaks process memory into its output. An upstream example was fixed in 18.1.0: GHSA-6ggm-xmc9-8ffg. | Pin a fixed release (≥ 18.1.0); track advisories; use parser output only for splitting, UX and audit (never authorisation); don't use `pg_query_normalize` (S2 N2/N3); input size limit before parsing. Option: run the parser out of process or as WASM in the JVM if the risk isn't accepted. | P1 (S2), P7 | S2 fuzzing (no crash in ~2,500 inputs per 5-run loop); P11 parser fuzz suite | Accepted risk (in-process), owner: stakeholder. Fuzz suite in every loop: 3,000 seeded inputs × both `standard_conforming_strings` settings (`StatementGateTest`). **P7-5:** an intermittent native fault was seen twice before all parser calls were moved to one dedicated thread; root cause unknown. **Re-confirmation requested** |

## 3. Residual risks requiring a named owner

Owners were named on 2026-10-15: the stakeholder owns all of them, to be reassigned within the organisation as needed (`docs/decision-log.md`).

| Risk | Owner | Status |
|---|---|---|
| T28 revocation delay up to the session cap (8 h) | Stakeholder | Accepted |
| T29 bulk reading within granted privileges | Stakeholder (with data owners / DBA) | Accepted |
| T21 tamper-evident only, if audit is ever co-hosted on 5433 | Stakeholder | Not applicable while the audit DB is a separate cluster (D4 as approved) |
| T10 passwords in the Java heap | — | No longer applies: no user passwords (ADR-0002) |
| T12 plaintext data if a PostgreSQL server lacks TLS | Stakeholder | Accepted if TLS is unavailable (A10) |
| T31 native parser in the backend's process (crash or memory leak on crafted SQL) | Stakeholder | Accepted: in-process, with size limit, pinned release and fuzz suite |
