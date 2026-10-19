# Admin Phase 3 Status: Read-only Administration

> Updated 2026-10-20. Third delivery of the stakeholder's plan "Comprehensive PostgreSQL Administration Workspace", started on the instruction "Continue all phases and I will check later" (2026-10-20). Evidence: `docs/evidence/admin-phase-3/`. Workstation only: Windows 11, no domain, Edge, PostgreSQL 18.6, NTLM loopback.
>
> **Scope rule.** The plan's remaining items (its Phases 3 to 6) were sorted into those that fit the approved decisions and those that conflict with one. Only the first group is built here; it is all **read-only**: catalog and statistics views read as the user's own role, and text for the editor. Every item that would change something (management screens, data editing, create/drop database, import/export, backup/restore, role administration) conflicts with an approved decision and is **not built**; the proposed decisions are in §8. "Continue all phases" is not treated as approval of any of them (`docs/decision-log.md`).

## 1. Scope against the plan

| Plan item | Plan phase | Delivered | Where |
|---|---|---|---|
| Inspect database properties | 3 | **Database** tab: name, owner, your role and what it may do (create schemas, temporary tables), size, encoding, collation, tablespace, connection limit, server version and start time, comment; grants on the database | `AdminCatalog.database`, `AdminPanels.jsx` |
| Inspect and manage views and sequences | 3 | Inspect: **Open definition** for views, materialised views and sequences (and tables, functions, procedures) opens the SQL in a new editor tab. Manage: not built (§8) | `AdminCatalog.definition`, `ObjectExplorer.jsx` |
| Inspect roles, memberships, ownership and grants; effective permissions | 6 | **Roles** tab: attributes, member of / members with admin, inherit and set options, whether you are a member; filter; predefined `pg_*` roles on request. Explorer: a relation's **owner**, its **grants** and **your privileges** on it | `AdminCatalog.roles`, `MetadataService.relation` |
| Active sessions and running queries where authorised | 6 | **Activity** tab: client sessions with state, wait, duration and query; your own session and its catalog connection marked; other sessions' details only as PostgreSQL shows them to your role | `AdminCatalog.activity` |
| Locks and blocking relationships | 6 | **Activity** tab: "blocked by" on waiting sessions (from `pg_blocking_pids`, only for sessions waiting on a lock), and the locks of other sessions, waiting first | same |
| Database and table size information | 6 | **Database** tab: sizes of the databases you may connect to; the 100 largest tables you can use, with data, index and estimated rows | `AdminCatalog.sizes` |
| EXPLAIN viewer; EXPLAIN ANALYZE only with a clear warning | 6 | **Explain** and **Explain analyze** buttons; the plan as a tree with costs, estimates, actual times, conditions, buffers and badly estimated nodes; "Show as table". EXPLAIN ANALYZE asks for confirmation on the server (`explain_analyze`), typed or by button | `PlanView.jsx`, `StatementGate.destructiveReasons` |
| Search and filtering in object explorers | 6 | **Filter objects** box in the explorer | `ObjectExplorer.jsx` |
| Object definitions and SQL generation | 6 | Definitions as above (text only, never run). SQL generation for changes: not built (§8) | — |
| Monitoring queries bounded, no avoidable load | 6 | Read only when a tab is opened or Refresh is pressed, never polled; row caps (500 sessions, 1,000 locks, 500 databases, 100 tables, 2,000 roles, 5,000 memberships, each flagged when reached); 10-second statement timeout; one catalog read at a time per session | — |
| Multiple editor tabs; query history; keyboard shortcuts | 6 | Tabs and history were delivered in admin Phase 2. Keyboard customisation stays deferred | — |

## 2. Design decisions within the approved scope

- **Same path as the object browser.** Every view is a constant parameterised query in `AdminCatalog` (ArchUnit rule extended), on the session's companion connection, as the user's role, with the 10-second timeout, the per-session lock and a `metadata_browse` audit record (`details.object`: `database`, `activity`, `sizes`, `roles`, `definition`). No new route, access level or credential path.
- **PostgreSQL decides visibility.** Another user's session shows its PID, user, database and application; its state, query and times only when PostgreSQL shows them to the role (own sessions, or membership of `pg_read_all_stats`). The UI says "not visible to your role". Database sizes only where the role may connect. Table sizes only for tables the role can use.
- **No actions on other sessions.** No cancel or terminate buttons; ending another session is a change and needs its own decision (§8).
- **Definitions are text.** View, function and procedure definitions come from PostgreSQL's own `pg_get_*def`; a sequence's from `pg_sequence`; a table's is reconstructed from the catalog (columns, collation, defaults, identity, generated columns, constraints, other indexes, triggers, comment), with a header saying what is not included. Identifiers are quoted by PostgreSQL (`quote_ident`, `quote_literal`). A test drops a table with identity, generated, collated and constrained columns, runs its definition and checks the definition read back is identical. The definition opens in a new tab and is never run.
- **EXPLAIN goes through the execution gate.** The buttons only add `EXPLAIN (FORMAT JSON)` or `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` in front of the statement at the cursor and use Run: the gate, kill switch, limits, timeout and audit all apply; error positions still point into the editor. On the server, plain EXPLAIN needs no confirmation (it only plans, so `EXPLAIN DELETE FROM t` no longer asks), and EXPLAIN ANALYZE always asks (`explain_analyze`), plus the statement's own reasons (`no_where`, and `ddl` for `CREATE TABLE … AS`). An ANALYZE option that isn't clearly off counts as on.
- **The EXPLAIN visualiser was on the deferred list** (`requirements.md` §5). It is built because the stakeholder's plan asks for it explicitly ("Add an EXPLAIN viewer and EXPLAIN ANALYZE only with a clear warning") and adds no new path to the database. Recorded in the decision log so it can be reversed.

## 3. Files changed and why

| File | Change |
|---|---|
| `backend/…/metadata/AdminCatalog.java` (new) | Database, activity and locks, sizes, roles and memberships, definitions |
| `backend/…/metadata/MetadataService.java` | A relation's owner, grants and your privileges; shared helpers for `AdminCatalog` |
| `backend/…/metadata/MetadataServlet.java`, `Application.java` | Five read-only endpoints on the existing metadata route |
| `backend/…/execution/StatementGate.java` | EXPLAIN without ANALYZE needs no confirmation; EXPLAIN ANALYZE gets `explain_analyze` |
| `frontend/src/api/metadata.js`, `execution.js` | Calls for the new views; the `explain_analyze` wording |
| `frontend/src/features/admin/AdminPanels.jsx` (new) | Activity, Database and Roles panels |
| `frontend/src/features/results/PlanView.jsx` (new), `ResultView.jsx` | Plan tree for EXPLAIN (FORMAT JSON) results |
| `frontend/src/features/explorer/ObjectExplorer.jsx` | Filter, Open definition, owner, grants and your privileges |
| `frontend/src/features/editor/SqlEditor.jsx` | `statement()` for Explain; positions before the start of the text clamp to it |
| `frontend/src/app/Workspace.jsx`, `styles.css` | Explain buttons, Activity / Database / Roles tabs, definitions in new tabs |
| Tests | `AdminCatalogIT` (9), `StatementGateTest` (EXPLAIN cases), `ArchitectureTest` (rule extended); `admin.test.jsx`, `plan.test.jsx`, `Workspace.phase3.test.jsx`, explorer tests; E2E journey |
| Docs | This file; `user-guide.md`; `audit-policy.md` §2; `sql-execution-behaviour.md`; `requirements.md`; `threat-model.md` T36; `decision-log.md`; README |

## 4. Acceptance criteria (the plan's criteria that apply to read-only views)

| Criterion | Result | Verified by |
|---|---|---|
| Every operation is authorised on the server, not by hidden controls | **Met** | Session ownership (404 for others) on every view, route access unchanged; `AdminCatalogIT` |
| Operations run as the intended effective PostgreSQL role | **Met** | Companion connection as the user's role; `currentRole`, `myPrivileges`, roles `current` checked per user in `AdminCatalogIT` |
| Generated SQL quotes identifiers safely | **Met** | Quoting by PostgreSQL; hostile names (`a"; DROP TABLE …`, `we"ird / sch`) in `AdminCatalogIT`; table round trip |
| Destructive operations require confirmation | **Met** for EXPLAIN ANALYZE | Server-side `explain_analyze`; `StatementGateTest`, `AdminCatalogIT`, RTL, E2E |
| Changes audited; errors without sensitive internals | **Met** | `metadata_browse` per view; catalog errors as SQLSTATE only |
| Monitoring bounded, no avoidable load | **Met** | On request only, row caps flagged, timeout |
| Tests cover permitted and denied operations | **Met** | See §5 |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted during development: `AdminCatalogIT` (9), `MetadataIT` (8), `StatementGateTest`, `ArchitectureTest`, `ConfigLoaderTest`; then full `mvn verify` with coverage | **PASS** | console |
| Frontend: lint + 172 Vitest tests (16 new), coverage lines 99.1 %, branches 91.1 % | **PASS** | console |
| Randomised loop, first attempt | **FAIL** at run 1: every test passed, but JaCoCo branch coverage of `pgweb.metadata` was 80 % (minimum 85 %). Fixed with more `AdminCatalogIT` cases (unlogged and sub-partitioned tables, by-default identity, comments, NO CYCLE, invalid requests, NOINHERIT) | `docs/evidence/admin-phase-3/loop-20261020-010954.json` |
| Randomised loop, 5 consecutive runs (172 frontend, 84 backend unit, 169 integration tests per run) | **PASS**, 5 of 5 consecutive | `docs/evidence/admin-phase-3/loop-20261020-013808.json` |
| End-to-end in Edge, first attempt (14 journeys) | **FAIL**: runs 1, 4, 5 passed; run 2 failed *an expired app session signs in again by itself*; run 3 failed *an audit outage refuses execution*. Neither journey touches Phase 3 code. Run 3 was a race in the test, exposed by admin Phase 2 (the editor exists before the session): the outage began while the session was still connecting, so Run was still disabled. **Fixed** in `journeys.spec.js` (wait until Run is enabled). Run 2's cause is **not known**: the trace was overwritten by later runs; it is a timing journey (65 s idle, then Windows sign-in again) and the workstation was under memory pressure. It had not failed in any earlier recorded run | `docs/evidence/admin-phase-3/e2e-20261020-014700.json` |
| End-to-end, second attempt with the fix | **Not completed**: Claude Code ended it for low memory before the first repetition finished. Not restarted without the stakeholder's say-so, per the host's instruction. A single repetition before the gate passed all 14 journeys | — |
| Text files | **PASS** | console |

Commands: `scripts\test\Invoke-TestLoop.ps1 -Tag admin-phase-3`; `scripts\test\Invoke-E2E.ps1 -Tag admin-phase-3 -Repeat 5`; `scripts\ci\Test-TextFiles.ps1`.

## 6. Security and audit implications

- **Nothing new can change data.** The new endpoints are GETs on the existing metadata route with constant queries; EXPLAIN goes through the unchanged execution path.
- **Information shown is what the role could read anyway** with any PostgreSQL client: PostgreSQL's catalog and statistics views apply their own rules. One difference worth noting: with execution switched off (production today), these views still work, as the object browser does. A role in `pg_read_all_stats` (or a superuser, which pgweb refuses) would see other users' query text, which can contain literal values (threat T36).
- **Audit:** `metadata_browse` with the object name only; no query text, sizes or role names are recorded.
- **Load:** each view is a handful of catalog queries under a 10-second timeout; `pg_database_size` and `pg_total_relation_size` read file sizes and are the most expensive; they run only when the Database tab is opened or refreshed.

## 7. Known limitations

- A table's definition is a reconstruction, not `pg_dump`: ownership, privileges, storage parameters, statistics targets, policies, column comments and identity options are not included (the header says so). Foreign tables, aggregates and window functions have no definition here.
- The activity view is a snapshot; it is not refreshed automatically.
- Locks show relation names only in the connected database.
- Kerberos, delegation and the non-DataAdmin view remain target-environment checks (K1–K6).

## 8. Not built: needs a decision first

| Plan item | Conflict | Proposed decision |
|---|---|---|
| Phase 3 management screens (schemas, tables, columns, constraints, indexes, views, sequences) and generated DDL with a preview | "DDL designers" deferred (D9); generated SQL is execution, so EXE-01 and gate G4 apply | ADR: generated DDL is shown in the editor and run only through the same gate, confirmation, kill switch and audit as typed SQL (no separate execution path); G4 sign-off covers it |
| Phase 3 table data browser and inline editing | "Inline data editing" deferred; tables without keys can't be edited safely | Same ADR; browsing as generated `SELECT … LIMIT` through the gate first, editing only for tables with a primary key, one transaction per save |
| Phase 3 create, rename, drop database | Needs the user's own `CREATEDB` or ownership; no privileged account (SEC-06, D14c) | Confirm: only with the user's own role, through the gate, never on the connected database |
| Phase 4 result export, CSV import | Result export deferred; bulk reading accepted as T29; `COPY … FROM STDIN` unsupported | ADR: limits, audit events (`data_export`, `data_import`), streaming, and whether T29 still holds |
| Phase 5 backup and restore | No ADR; would launch `pg_dump`/`pg_restore` on the app host with the delegated identity; binaries not in the bundle; dump files are data at rest | ADR before any work: process identity, executable intake, storage, retention, ACLs, audit |
| Phase 6 role administration, passwords | No PostgreSQL passwords exist (Kerberos only, ADR-0002); no privileged account (D14c) | Role changes only as SQL under the user's own `CREATEROLE`, through the gate; no password workflows |
| Phase 6 cancel or terminate other sessions | A change to someone else's work; not covered by any decision | Decide whether to offer `pg_cancel_backend` / `pg_terminate_backend` (PostgreSQL limits them to the same role or `pg_signal_backend`), with confirmation and audit |
| Phase 6 schema comparison | Optional in the plan | Not planned |

Also still open from earlier: the P9-3 detail (paging only inside a transaction), T31, G4 and MVP sign-off, P12-3.

## 9. Ready for review?

**For review, but the delivery gate is not complete.** The loop passed 5 consecutive runs; the E2E gate has not passed 5 repetitions: the first attempt had two failures outside Phase 3's code (one fixed test race, one unexplained timing failure), and the rerun was ended by the host for low memory. Committed and pushed so the work is not lost. Next step, on request: rerun `Invoke-E2E.ps1 -Tag admin-phase-3 -Repeat 5` on a machine with more free memory; if *an expired app session signs in again by itself* fails again, investigate it from its trace before anything else.

No further phase was started: everything left in the plan needs one of the decisions in §8.
