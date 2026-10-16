# Requirements (MVP and deferred)

> **Phase:** 0. **Plan reference:** `docs/implementation-plan-v2.md` §10.
> Each requirement lists the phase that delivers it and the test that verifies it. **Status** is `Planned` until its verifying test has run and passed.
> Scope: decision D9 approved 2026-10-15 (plan §10.1).

## 1. MVP: security and access

| ID | Requirement | Phase | Verified by | Status |
|---|---|---|---|---|
| SEC-01 | Every protected page and API requires Waffle authentication; unauthenticated requests get 401 / the Negotiate flow | P4 | Route-inventory anonymous test; E2E unauthenticated journey | Tested (P4) |
| SEC-02 | Identity comes only from the server-side Waffle context; client-supplied usernames, roles and flags are rejected | P4 | Forged-field tests | Tested (P4) |
| SEC-03 | Internal PostgreSQL requires `DataAdmin`, checked server-side on every request | P4, P6 | Direct-API authorisation matrix | Tested (P4, P6) |
| SEC-04 | Internal and external sessions connect as the user via Kerberos constrained delegation (ADR-0002) and run as the mapped PostgreSQL role; `session_user` is verified | P6 | Mapping tests | Tested (P6; Kerberos in target env) |
| SEC-05 | External sessions: only databases the user has CONNECT on are offered; host and port come from server config only (ADR-0003) | P6 | Credential and override-rejection tests | Tested (P6) |
| SEC-06 | No shared or privileged account runs user SQL; superuser sessions refused (D14c) | P6 | Privilege and superuser tests | Tested (P6) |
| SEC-07 | DB sessions are isolated per user; foreign session IDs return 404 | P6 | Cross-user tests | Tested (P6) |
| SEC-08 | No user passwords are handled; delegated tokens are never stored; app secrets are never logged, persisted, returned or stored in the browser | P3, P6 | Secret-grep tests | Tested (P6) |
| SEC-09 | CSRF, CSP, clickjacking and security-header protections | P4 | Header and CSRF tests | Tested (P4) |
| SEC-10 | App-generated SQL is parameterised; identifiers are safely quoted | P3, P10 | Injection-payload tests; ArchUnit | Planned |
| SEC-11 | Failed DB-session opens are rate-limited per user and audited | P6 | Throttling test | Tested (P6) |

## 2. MVP: execution, limits and audit

| ID | Requirement | Phase | Verified by | Status |
|---|---|---|---|---|
| EXE-01 | SQL execution disabled (feature flag) until gate G4 | P6–P8 | Flag test; G4 sign-off | Tested (P7): disabled → 403 `EXECUTION_DISABLED`; enabled without a loadable parser → startup refused. G4 sign-off pending (after P8) |
| EXE-02 | Single-statement execution enforced by the verified parser gate plus a pgJDBC cross-check (ADR-0009); no hand-written splitter | P1, P7 | S2/S3 reports; P7 tests | Tested (P7): libpg_query gate + pgJDBC cross-check; corpus on the real server (both `standard_conforming_strings` settings); the server's statement log shows rejected text never arrived; NUL characters refused (P7-3) |
| EXE-03 | Multi-statement scripts only if the parser passes 100% of the corpus; otherwise disabled | P1, P7 | S2 report | Not offered in this release (P7): the parser passes the whole corpus, so scripts are allowed, but their partial-failure, transaction and audit rules are deferred. **Stakeholder to confirm** |
| EXE-04 | Distinct handling of row-returning, command, DDL, zero-row, error, timeout and cancelled outcomes | P7, P8 | §3.3 integration and RTL tests | Tested (P7) in the API: rows, command, DDL, zero rows, error (SQLSTATE, position), timeout, cancelled, indeterminate. UI in P8 |
| EXE-05 | Server-side row, response-size and cell limits; SQL never rewritten | P7 | Limit and heap tests | Tested (P7): row, byte and cell limits; SQL sent unchanged. Memory per statement is bounded by `maxRows + 1` rows, not by bytes (documented; streaming in P9) |
| EXE-06 | Server-side wall-clock timeout unaffected by user `SET` commands | P7 | Timeout-after-SET tests | Tested (P7): timeout holds after `SET statement_timeout = 0`, `SET lock_timeout = 0` and `RESET ALL` |
| EXE-07 | Cancellation reaches PostgreSQL; resource release measured; escalation and quarantine | P7 | Resource-release measurements | Tested (P7): cancel releases row locks; an ineffective cancel escalates to `pg_terminate_backend` and quarantines the session; without a companion the outcome is `unconfirmed` (P7-2) |
| EXE-08 | One in-flight statement per session; per-user and global session caps | P6, P7 | Concurrency-limit tests | Tested (P6 caps; P7: a second statement gets 409 `SESSION_BUSY`) |
| AUD-01 | Intent recorded before execution, outcome after; indeterminate and unknown outcomes recorded | P4, P7 | Audit tests | Tested (P7): intent committed before execution, linked outcome after, `indeterminate` recorded on connection loss |
| AUD-02 | Fail-closed when audit can't be written (D4) | P4, P7 | Audit-DB-down test | Tested (P4, P7): audit DB down → 503, nothing reaches PostgreSQL; an outcome that can't be written is spooled and replayed |
| AUD-03 | SQL text: normalised (scanner masking) + HMAC; no literals stored (D4) | P7 | Literal-absence tests | Tested (P7): comments and every literal removed by the scanner; no text with `standard_conforming_strings = off`; HMAC verified |
| AUD-04 | Tamper resistance: INSERT-only role, trigger, hash chain | P3, P4 | Tamper and chain tests | Tested (P3, P4) |

## 3. MVP: functionality

| ID | Requirement | Phase | Verified by | Status |
|---|---|---|---|---|
| FN-01 | Landing page offering internal (DataAdmin only) and external options | P5 | RTL; E2E | Tested (P5; E2E in P11) |
| FN-02 | External connection: choose a database from those the user may CONNECT to (no password) | P6 | RTL; integration | Tested (P6) |
| FN-03 | Connection status and session info; multiple sessions; expired/lost handling | P6 | RTL; integration | Tested (P6) |
| FN-04 | SQL editor with PostgreSQL highlighting and practical editing features | P8 | RTL | Planned |
| FN-05 | Execute selection or current statement; cancel; running timer | P8 | RTL; E2E | Planned |
| FN-06 | Bounded, locally sortable, paginated results with NULL and type-aware display | P8 | RTL | Planned |
| FN-07 | Affected-row counts and command completion display | P8 | RTL; integration | Planned |
| FN-08 | Auto-commit and manual transactions; isolation-level choice; transaction state shown | P9 | Integration; RTL | Planned |
| FN-09 | Lock waits, deadlocks, serialization failures and aborted transactions reported clearly | P9 | Concurrency suite | Planned |
| FN-10 | Results labelled as snapshots ("Retrieved at"); refresh re-runs the statement | P8 | RTL | Planned |
| FN-11 | Metadata browser for accessible databases, schemas and objects | P10 | Integration; RTL | Planned |
| FN-12 | Usable at 1366×768 and 1920×1080 | P5, P11 | Playwright layout check | Planned |

## 4. MVP: quality and operations

| ID | Requirement | Phase | Verified by | Status |
|---|---|---|---|---|
| QA-01 | Coverage thresholds per plan §8.2, enforced by the build | P2+ | CI | Planned |
| QA-02 | SonarQube gates per plan §8.3 on both projects | P2+ | CI | Planned |
| QA-03 | Real-PostgreSQL concurrency suite, nightly ×20 without flakes | P9 | Nightly CI | Planned |
| OPS-01 | Offline dependency intake with SBOM, scan and manifest | P2 | `deps:verify`; intake record | Planned |
| OPS-02 | GitLab pipeline on Windows runners; no remote includes, Docker or SaaS | P2 | Pipeline run | Planned |
| OPS-03 | Windows service deployment, rollback and runbooks; offline install rehearsal | P11, P12 | Rehearsals | Planned |

## 5. Deferred (post-MVP; each needs separate approval)

Inline data editing; result export; server-side "fetch more"; multiple editor tabs; query history and saved queries; schema-aware autocomplete; EXPLAIN visualiser; DDL designers; audit viewer UI; two-person approval for high-risk statements; dark mode and keyboard customisation.

## 6. Operational scaling (only when justified by measured need)

Multiple backend instances with sticky routing; distributed session coordination; app hot-standby; metadata connection pooling; SIEM forwarding; Windows containers with gMSA; Linux containers (requires replacing Waffle, with approval).
