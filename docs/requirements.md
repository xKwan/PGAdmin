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
| EXE-03 | Multi-statement scripts only if the parser passes 100% of the corpus; otherwise disabled | P1, P7, admin P2 | S2 report; `StatementGateTest`; `ScriptAndFetchIT`; RTL; E2E | Tested (admin P2). Decided 2026-10-19 "stop at first error": a separate Run script; the whole script is checked first (every statement through the single-statement gate) and nothing runs unless all pass; statements run one at a time, each with its own fail-closed audit intent and outcome; the first failure stops it; one confirmation for destructive statements; stops if `standard_conforming_strings` changes |
| EXE-04 | Distinct handling of row-returning, command, DDL, zero-row, error, timeout and cancelled outcomes | P7, P8 | §3.3 integration and RTL tests | Tested (P7 API; P8 UI): rows, command, DDL, zero rows, error (SQLSTATE, position), timeout, cancelled, indeterminate |
| EXE-05 | Server-side row, response-size and cell limits; SQL never rewritten | P7, admin P2 | Limit and heap tests; `ScriptAndFetchIT` | Tested (P7): row, byte and cell limits; SQL sent unchanged. Memory per statement is bounded by `maxRows + 1` rows, not by bytes (documented). Admin P2 (P9-3, decided 2026-10-19): inside a transaction the rest of a result is fetched page by page from a cursor, up to `maxFetchedRows`, each page audited (fail-closed); auto-commit behaviour unchanged |
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
| FN-02 | External connection: choose a database from those the user may CONNECT to (no password) | P6, admin P1 | RTL; integration; E2E | Tested (P6). Admin P1: the list loads when the workspace opens; the last database used is offered first (name only, in the browser) |
| FN-03 | Connection status and session info; multiple sessions; expired/lost handling | P6, admin P1 | RTL; integration; E2E | Tested (P6). Admin P1: server reachability, database and session state shown separately, from backend-confirmed facts only; switch database (new session first, old one closed only on success; open transaction never discarded without confirmation, enforced by the server); one-step reconnect. `DbSessionSwitchIT`, `connections.test.jsx`, E2E journey |
| FN-04 | SQL editor with PostgreSQL highlighting and practical editing features | P8 | RTL | Tested (P8): CodeMirror 6 with PostgreSQL highlighting, bracket matching, search, comment toggle, keyword completion; RTL and E2E (Edge) |
| FN-05 | Execute selection or current statement; cancel; running timer | P8 | RTL; E2E | Tested (P8): Ctrl+Enter runs the selection or the statement at the cursor; running timer; Cancel; RTL and E2E |
| FN-06 | Bounded, locally sortable, paginated results with NULL and type-aware display | P8 | RTL | Tested (P8): virtualised grid, type names, NULL vs '' vs 'NULL', truncation markers, local sort (labelled), pages of 200; RTL and E2E |
| FN-07 | Affected-row counts and command completion display | P8 | RTL; integration | Tested (P8): affected-row counts and "Completed."; RTL and E2E |
| FN-08 | Auto-commit and manual transactions; isolation-level choice; transaction state shown | P9 | Integration; RTL | Tested (P9): auto-commit and manual mode, Begin with isolation level and read-only (a convenience), Commit, Rollback; state as PostgreSQL reports it; API, RTL and E2E |
| FN-09 | Lock waits, deadlocks, serialization failures and aborted transactions reported clearly | P9 | Concurrency suite | Tested (P9): lock waits shown; 55P03, 40P01, 40001, 25P02, 25001, 23xxx explained; real-PostgreSQL concurrency suite (20 consecutive runs) |
| FN-10 | Results labelled as snapshots ("Retrieved at"); refresh re-runs the statement | P8 | RTL | Tested (P8): "Retrieved at" and Refresh (runs the same text again); RTL |
| FN-11 | Metadata browser for accessible databases, schemas and objects | P10 | Integration; RTL | Tested (P10): schemas → tables, views, materialised views, foreign tables, sequences, functions and procedures (signatures) → columns, indexes, constraints; filtered by privilege, "show everything" marks the rest; lazy, on the companion; insert quoted names; API, RTL and E2E |
| FN-12 | Usable at 1366×768 and 1920×1080 | P5, P11 | Playwright layout check | Tested (P8, E2E): no page-level horizontal scrolling at 1366×768 and 1920×1080 in the workspace; the full layout check is Phase 11 |
| FN-13 | Editor tabs; open, save and save-as local `.sql` files; unsaved changes confirmed before they are discarded; opening never runs anything | admin P2 | RTL (`files.test.jsx`, `Workspace.admin.test.jsx`) | Tested (admin P2): tabs survive switching database; File System Access API in Edge, upload/download otherwise; UTF-8 only, at most 5 MB; files never reach the server |
| FN-14 | Query history of the user's own statements and scripts | admin P2 | RTL; E2E | Tested (admin P2). Decided 2026-10-19 "this browser tab only": full text, last 50, `sessionStorage` per user and server, Clear history; nothing new on the server |
| FN-15 | Explorer: partitioned tables and partitions, triggers, extensions, other databases; refresh after committed object changes | admin P2 | `MetadataIT`; RTL | Tested (admin P2): same companion connection, parameterised catalog queries, ownership and DataAdmin checks, `metadata_browse` audit |
| FN-16 | Read-only administration views: database properties and grants; sessions, blocking and locks; database and table sizes; roles and memberships | admin P3 | `AdminCatalogIT`; RTL; E2E | Tested (admin P3): as the user's role on the companion, PostgreSQL's visibility rules, on request only, row caps, `metadata_browse` audit; nothing can be changed |
| FN-17 | Object definitions as SQL in a new editor tab (tables, views, materialised views, sequences, functions, procedures); a relation's owner, grants and the user's privileges; explorer name filter | admin P3 | `AdminCatalogIT` (table round trip); RTL; E2E | Tested (admin P3): never run; identifiers quoted by PostgreSQL |
| FN-19 | Cancel another session's query or end it, with confirmation, PostgreSQL's permission rule and audit; other roles' SQL text never shown | admin P4 | `AdminCatalogIT`; RTL; E2E | Tested (admin P4) |
| FN-20 | Management of schemas, tables, columns, constraints, indexes, views, sequences, databases, roles and privileges through generated SQL opened in the editor | admin P5 | `sqlGen.test.js`; RTL; E2E | Tested (admin P5) |
| FN-21 | Table data browser; row editing for tables with a primary key, reviewed, one transaction per save | admin P6 | `rowEdit.test.js`; `data.test.jsx`; `AdminCatalogIT`; E2E | Tested (admin P6) |
| FN-22 | Export of the rows on screen as CSV or JSON, recorded first | admin P7 | `ExportIT`; `csv.test.js`; RTL; E2E | Tested (admin P7) |
| FN-23 | CSV import as reviewable INSERT statements | admin P7 | `csv.test.js`; `data.test.jsx`; E2E | Tested (admin P7) |
| FN-28 | Dark mode: light, dark or the system setting, for the whole page and the editor, kept in the browser | admin P12 | `settings.test.jsx`; E2E | Tested (admin P12) |
| FN-27 | Saved queries on the server, per Windows account, never shared; off unless switched on | admin P11 | `SavedQueriesIT`; `saved.test.jsx`; E2E | Tested (admin P11) |
| FN-26 | Schema-aware completion in the editor: schemas, relations, columns (with aliases) and routines the role can use | admin P10 | `AdminCatalogIT`; `completion.test.js`; E2E | Tested (admin P10) |
| FN-25 | Read-only audit viewer for auditors (`pgweb_audit_reader`), reading as the signed-in user, each search recorded | admin P9 | `AuditViewerIT`; `audit.test.jsx`; E2E | Tested (admin P9) |
| FN-24 | Schema comparison of two schemas, in one database or two, with the SQL to make the target like the source opened in the editor (never run) | admin P8 | `AdminCatalogIT`; `schemaDiff.test.js`; `compare.test.jsx`; E2E | Tested (admin P8) |
| FN-18 | EXPLAIN viewer; EXPLAIN ANALYZE only after a warning | admin P3 | `StatementGateTest`; `AdminCatalogIT`; RTL; E2E | Tested (admin P3): plan tree from `EXPLAIN (FORMAT JSON)` through the normal execution path; `explain_analyze` confirmation on the server. Was on the deferred list as "EXPLAIN visualiser"; built because the stakeholder's plan asks for it (`docs/decision-log.md`, 2026-10-20) |

## 4. MVP: quality and operations

| ID | Requirement | Phase | Verified by | Status |
|---|---|---|---|---|
| QA-01 | Coverage thresholds per plan §8.2, enforced by the build | P2+ | CI | Tested (workstation): JaCoCo and Vitest thresholds fail the build; every phase's loop ran with them |
| QA-02 | SonarQube gates per plan §8.3 on both projects | P2+ | CI | Tested on the project-local SonarQube Community Build; the organisation's SonarQube is target item C4 |
| QA-03 | Real-PostgreSQL concurrency suite, nightly ×20 without flakes | P9 | Nightly CI | Tested (workstation): 20/20 consecutive (P9); the nightly GitLab schedule is target item C1 |
| OPS-01 | Offline dependency intake with SBOM, scan and manifest | P2 | `deps:verify`; intake record | Tested (workstation): intake exercise (P2), vulnerability scan (P11, P12: 0 findings, no exceptions). Clean network-disabled VM: target item C5 |
| OPS-02 | GitLab pipeline on Windows runners; no remote includes, Docker or SaaS | P2 | Pipeline run | Tested locally (`Invoke-LocalPipeline.ps1` runs the same job scripts offline); a real GitLab Windows runner is target item C1 |
| OPS-03 | Windows service deployment, rollback and runbooks; offline install rehearsal | P11, P12 | Rehearsals | Tested (workstation, P12): install → upgrade → tampered bundle → rollback → uninstall, audit preserved and chain verified, graceful shutdown closes sessions; pgweb ran as a process (no elevation here). Service mode, gMSA, Kerberos and a clean VM: target items D1–D5, C5 |

## 5. Deferred (post-MVP; each needs separate approval)

Two-person approval for high-risk statements; keyboard customisation; backup and restore (decision A6: needs an ADR). Inline data editing, result export and DDL generation were delivered in admin Phases 5–7 (FN-19 to FN-23) under the stakeholder's delegation of 2026-10-20.

The stakeholder's administration-workspace plan of 2026-10-19 (`docs/decision-log.md`) schedules most of these, plus database/schema management, data import, SQL file handling, backup and restore, and role administration, in phases after admin Phase 1. They stay deferred here until each phase is started; the conflicts with approved decisions that must be settled first are listed in `docs/admin-phase-1-status.md`. Admin Phase 2 (2026-10-19) delivered "fetch more", editor tabs, local SQL files and a browser-tab query history (FN-13 to FN-15, EXE-03). Admin Phase 3 (2026-10-20) delivered the read-only parts of the remaining plan, including the EXPLAIN visualiser (FN-16 to FN-18); everything that changes something is listed with a proposed decision in `docs/admin-phase-3-status.md` §8.

## 6. Operational scaling (only when justified by measured need)

Multiple backend instances with sticky routing; distributed session coordination; app hot-standby; metadata connection pooling; SIEM forwarding; Windows containers with gMSA; Linux containers (requires replacing Waffle, with approval).
