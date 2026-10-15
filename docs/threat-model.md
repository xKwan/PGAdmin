# Threat Model (draft)

> **Phase:** 0. **Method:** STRIDE over the data flows below.
> **Status of every mitigation: Planned** (none is implemented or tested). A threat is closed only when its verifying test has run and passed (P11 acceptance), or when a named owner formally accepts the risk.

## 1. Scope and trust boundaries

```
[Browser] --TB1 (HTTPS, Negotiate, session cookie, CSRF)--> [pgweb service]
[pgweb service] --TB2 (pgJDBC, user's own credentials)--> [Internal PG :5433]
[pgweb service] --TB3 (pgJDBC, user's own credentials)--> [External PG :5432]
[pgweb service] --TB4 (pgweb_app, INSERT-only)--> [Audit DB]
[pgweb service] --TB5 (local filesystem)--> [config, logs, audit spool]
[Air-gap intake] --TB6 (media transfer)--> [build/CI toolchain and dependencies]
```

**Assets:** user PostgreSQL credentials; data in target databases; audit records; the app's own secrets (`pgweb_app` password, HMAC key, TLS key); session identifiers; build artifacts.

**Actors:** authenticated non-DataAdmin user; DataAdmin user; unauthenticated internal network user; malicious web page visited by an authenticated user; DBA or superuser on a PostgreSQL instance; compromised dependency; server administrator.

## 2. Threats

| ID | STRIDE | Threat | Mitigation (plan ref) | Phase | Verifying test | Status |
|---|---|---|---|---|---|---|
| T01 | S | Client claims a different identity via request fields | Identity only from the Waffle security context; identity/role fields rejected (§2.4) | P4 | Forged-field authorisation tests | Planned |
| T02 | S | NTLM relay or downgrade instead of Kerberos | SPN registered; HTTPS; no reverse proxy; Kerberos confirmed in S1 | P1, P12 | S1 report; deployment check | Planned / Unresolved (A3) |
| T03 | S, E | Non-DataAdmin user reaches internal PostgreSQL by calling the API directly | Server-side DataAdmin check on every internal request (§5) | P4, P6 | Direct-API authorisation matrix | Planned |
| T04 | S | User connects to internal PostgreSQL as another user's role | Role name fixed by the server-side mapper; `session_user` checked after connecting (§2.4) | P6 | Mapping and `session_user` tests | Planned |
| T05 | T, E | Cross-site request forges a state change using ambient Negotiate credentials | CSRF token, Origin/`Sec-Fetch-Site` check, `SameSite=Strict`, side-effect-free GET (§5) | P4 | CSRF and cross-origin tests | Planned |
| T06 | I, E | One user uses another user's DB session | Random 128-bit IDs bound to SID and app session; 404 on mismatch (§4) | P6 | Cross-user session tests | Planned |
| T07 | E | Shared login plus `SET ROLE` lets users `RESET ROLE` to higher privileges | Not used; users' own credentials only (§2.4, D3) | P6 | Design review; role tests | Planned |
| T08 | E | Superuser session bypasses all app controls (`COPY … PROGRAM`, audit tampering) | Superuser sessions refused by default (D14c) | P6 | Superuser refusal test | Planned / Unresolved (D14c) |
| T09 | I | SSRF or port scanning via user-supplied host/port; pointing "external" at 5433 | Host and port from server config only; request fields rejected (§2.5) | P6 | Override-rejection tests | Planned |
| T10 | I | Credentials leak through logs, errors, responses, browser storage or heap dumps | Redaction; no persistence; form clearing; heap dumps disabled (§2.5) | P3, P6 | Secret-grep across logs, responses and audit | Planned (heap clearing best-effort) |
| T11 | S | The app is used to brute-force PostgreSQL passwords | Per-user failed-login throttling; audit of attempts (§2.5) | P6 | Throttling test | Planned |
| T12 | I | Credentials or data intercepted between app and PostgreSQL | TLS `verify-full` where supported (D2) | P6 | TLS configuration test | Unresolved (A10) |
| T13 | T | XSS through result values, error messages, object names or usernames | React escaping; `dangerouslySetInnerHTML` banned; strict CSP (§5) | P5, P8, P11 | XSS payload tests | Planned |
| T14 | T | SQL injection in app-generated metadata or audit SQL | Parameterised statements; quoted identifiers from catalog or allow-list; ArchUnit rule (§5) | P3, P10 | Injection-payload identifier tests; ArchUnit | Planned |
| T15 | D | Long-running or huge queries exhaust DB or app resources | Wall-clock timeout plus cancel and escalation; row, byte and cell limits; session caps (§3.4, §3.5) | P7 | Timeout, limit and heap tests | Planned |
| T16 | D | Cancellation fails and the query keeps running | Escalation via companion `pg_terminate_backend`; quarantine; DBA backstops (§3.5) | P7 | Resource-release measurements | Planned. Same-role cancel/terminate releasing locks **Tested** on Windows PG 18.6 (S5 report F3, F4); pgJDBC path still Assumed. |
| T17 | D | Abandoned transactions hold locks | Idle-in-transaction timeout; heartbeat abandonment; max transaction age (§4) | P6, P9 | Reaper and abandonment tests | Planned |
| T18 | D | Connection exhaustion on target instances | Per-user and global caps; per-role `CONNECTION LIMIT` | P6 | Cap tests | Planned / Unresolved (A11) |
| T19 | T | Multi-statement text executes statements the user didn't intend, or misreports outcomes | Parser gate or server-enforced single statement; no hand-written splitter (§3.1, §3.2) | P1, P7 | S2 corpus; S3 server-log proof | Planned / Assumed (A6, A7) |
| T20 | R | User denies having executed a statement | Intent record written before execution; outcome after; hash chain (§6) | P4, P7 | Audit tests | Planned |
| T21 | R, T | Audit records altered or deleted | INSERT-only runtime role; UPDATE/DELETE trigger; hash chain; partition exports with manifests (§6) | P3, P4 | Tamper and chain-verification tests | Planned. Only tamper-*evident* if co-hosted with superusers (D4). |
| T22 | R | Execution happens without an audit record during an audit outage | Fail-closed (D4); spool for outcomes only | P4, P7 | Audit-DB-down test | Unresolved (D4) |
| T23 | I | Audit records expose secrets or personal data contained in SQL literals | Normalised text plus HMAC; no raw text by default (§6) | P7 | Literal-absence assertions | Planned / Assumed (A6 comment stripping) |
| T24 | I | Unauthorised reading of audit records | `pgweb_audit_reader` granted to named auditors only; spool ACLs | P3, P12 | Privilege-verification SQL | Planned / Unresolved (D13) |
| T25 | T | Malicious or vulnerable dependency enters through the air-gap intake | SBOM; vulnerability scan at intake; SHA-256 manifest; second-person check; lockfile integrity (§7.1) | P2 | `deps:verify`; intake record | Planned |
| T26 | E | Mock authentication provider enabled in production | Test/dev-only module, not packaged; startup refusal under `prod` | P4 | Startup-refusal test | Planned |
| T27 | I | App secrets (HMAC key, `pgweb_app` password, TLS key) exposed on the server | NTFS ACLs; DPAPI encryption; never in the repo | P3, P12 | Config review; secret-grep of repo | Planned |
| T28 | E | Revoked DataAdmin user keeps access | DataAdmin re-checked on every request; absolute session cap (8 h) | P4 | Session-cap test | Planned. Revocation delay up to the session cap is accepted residual risk (D1/D10). |
| T29 | I | Authorised user reads all data their role permits (bulk exfiltration) | Out of app scope: data owners apply RLS and column grants; audit records the activity | — | — | Accepted risk (owner to be named) |
| T30 | T | Clickjacking of the UI | `frame-ancestors 'none'` | P4 | Header test | Planned |
| T31 | D / I | A memory-safety bug in the native SQL parser (libpg_query DLL, which runs inside the backend's process on untrusted SQL text) crashes the backend or leaks process memory into its output. An upstream example was fixed in 18.1.0: GHSA-6ggm-xmc9-8ffg. | Pin a fixed release (≥ 18.1.0); track advisories; use parser output only for splitting, UX and audit (never authorisation); don't use `pg_query_normalize` (S2 N2/N3); input size limit before parsing. Option: run the parser out of process or as WASM in the JVM if the risk isn't accepted. | P1 (S2), P7 | S2 fuzzing (no crash in ~2,500 inputs per 5-run loop); P11 parser fuzz suite | Open: needs a risk decision |

## 3. Residual risks requiring a named owner

| Risk | Proposed owner | Status |
|---|---|---|
| T28 revocation delay up to the session cap | Security owner | Unresolved |
| T29 bulk reading within granted privileges | Data owners / DBA | Unresolved |
| T21 tamper-evident only, if audit is co-hosted on 5433 | Security owner | Unresolved (D4) |
| T10 best-effort clearing of passwords from the Java heap | Security owner | Unresolved |
| T12 plaintext data if the external server lacks TLS | Security owner / DBA | Unresolved (A10) |
| T31 native parser in the backend's process (crash or memory leak on crafted SQL) | Security owner | Unresolved (S2 report) |
