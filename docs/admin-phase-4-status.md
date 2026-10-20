# Admin Phase 4 Status: Session Signals and Activity Privacy

> Updated 2026-10-20. Fourth delivery of the stakeholder's plan "Comprehensive PostgreSQL Administration Workspace". Evidence: `docs/evidence/admin-phase-4/`. Workstation only: Windows 11, no domain, Edge, PostgreSQL 18.6, NTLM loopback. Every test ran against throwaway clusters started by the test scripts; nothing ran against any other PostgreSQL server.
>
> **Second delegation (2026-10-20, later).** The stakeholder asked for schema comparison, the deferred features of `requirements.md` §5 and a draft backup ADR, "Use your own recommendation for any new decision. Where unsure, take the more conservative option and add it to the 'Decisions made autonomously' list". Decisions from A13 on are taken under it.
>
> **Authority.** On 2026-10-20 the stakeholder wrote: "use your own recommendation for each one, including the proposed decisions in docs/admin-phase-3-status.md §8. Where you're unsure, pick the more conservative option", and set three decisions directly (hide other users' SQL text in Activity; keep the EXPLAIN visualiser; keep *Load more rows* inside transactions). It also asked that anything destructive or affecting other users asks for confirmation in the UI, checks privileges on the server and is audited. The decisions taken under that instruction are listed in §10; this file is the one list for all later admin phases.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Activity: hide other users' SQL text (stakeholder decision) | Query text only for sessions of your own PostgreSQL role. Other roles' sessions keep their state, waits and times where PostgreSQL shows them (`pg_read_all_stats`), with "another role's SQL is not shown". The text is not sent to the browser at all | `AdminCatalog.ACTIVITY`, `AdminPanels.jsx` |
| Cancel another session's query, or end the session (plan Phase 6; §10 A7) | **Cancel query** (active sessions) and **End session** on other visible client sessions in the Activity tab; a confirmation dialog first; the server checks privileges, requires `confirmed`, and writes the audit record before the signal | `SessionSignals`, `POST /api/metadata/{session}/signal`, `AdminPanels.jsx` |

## 2. Design

- **The server decides, the UI only asks.** `POST /api/metadata/{session}/signal` with `{pid, action: cancel|terminate, confirmed}`. In order:
  1. refused while SQL execution is switched off (`EXECUTION_DISABLED`, EXE-01): a signal is a change, so production (execution off) has none;
  2. the session must be the caller's (404 otherwise, as everywhere);
  3. pgweb's own session and its companion are refused (`OWN_SESSION`): use Cancel in the editor, or close the session;
  4. on the companion, as the user's role, one constant query reads the target. It must exist (404), be visible to the role (403 `NOT_PERMITTED`), and be a `client backend` (400 `NOT_CLIENT_SESSION`: background processes are never signalled). The role must have **PostgreSQL's own permission**: the privileges of the target's role or of `pg_signal_backend`, and a superuser's session only as a superuser (403 `NOT_PERMITTED`, audited as `refused`);
  5. without `confirmed`: 409 `CONFIRMATION_REQUIRED` with the reason (`cancel_query` or `terminate_session`) and the target's user and database; nothing is sent;
  6. `session_signal` is written **before** the signal and must be persisted (fail-closed, like an execution intent): no audit, no signal;
  7. `pg_cancel_backend` or `pg_terminate_backend` runs as the user's role. PostgreSQL checks the permission again (42501 becomes 403). `session_signal_outcome` records `success`, `not_sent` (the session had gone) or `error` with its SQLSTATE.
- **The dialog says what happens**: whose work it affects, that ending a session rolls back its open transaction, that PostgreSQL decides whether the role may do it, and that it is audited. The UI shows the buttons only on other sessions the role can see; that is convenience, not the control.
- **SQL text of other roles** is removed in the query itself (`CASE WHEN usename = current_user`), so it never leaves the database server for the browser. Sessions of your own role keep their text (the same role can read it in any client).

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/metadata/SessionSignals.java` (new) | Cancel and terminate with the checks above |
| `backend/…/metadata/AdminCatalog.java` | Activity query hides other roles' SQL; `queryHidden` |
| `backend/…/metadata/MetadataService.java` | `withCompanion` (lock without the browse audit), used by `onCompanion` and the signals |
| `backend/…/metadata/MetadataServlet.java`, `Application.java` | `POST …/signal` |
| `frontend/src/api/metadata.js`, `features/admin/AdminPanels.jsx` | `signalSession`; actions column, dialog, result line |
| Tests | `AdminCatalogIT` (+4: SQL text hidden from a `pg_read_all_stats` role; cancel and terminate with confirmation and audit; privileges, visibility and background processes; kill switch), `ArchitectureTest` (constant-query rule covers `SessionSignals`), `admin.test.jsx` (+2), E2E journey "admin Phase 4" |
| Docs | This file; `user-guide.md` §7a; `audit-policy.md` §2; `threat-model.md` T36, T37; `decision-log.md`; `admin-phase-3-status.md` §5, §8, §9 |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Authorised on the server, not by hidden controls | **Met** | Ownership, visibility, client-backend, PostgreSQL permission and kill-switch checks on the server; `AdminCatalogIT` |
| Runs as the user's own role | **Met** | The signal runs on the companion as the user's role; PostgreSQL's 42501 path |
| Destructive or other-user operations require confirmation in the UI | **Met** | Dialog (RTL, E2E); server 409 without `confirmed` (`AdminCatalogIT`) |
| Audited | **Met** | `session_signal` before (fail-closed), `session_signal_outcome` after, `refused` attempts; `AdminCatalogIT` |
| Other users' SQL not shown | **Met** | `AdminCatalogIT` (body never contains the other role's literal), RTL |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted: `AdminCatalogIT` (13, 4 new), `ArchitectureTest` | **PASS** | console |
| Frontend targeted: `admin.test.jsx` (7, 2 new), lint | **PASS** | console |
| Randomised loop, 5 consecutive runs (174 frontend, 84 backend unit, 173 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-4/loop-20261020-084051.json` |
| End-to-end, first attempt (15 journeys) | **FAIL**, all 5 repetitions, only the new journey: its check of the confirmation message matched two `role="status"` elements (the message and the panel's loading spinner), a strict-mode error in the test. The feature had worked (the message was shown). Trace, error context and server logs were saved before the next run; error context kept | `docs/evidence/admin-phase-4/e2e-20261020-085340.json`, `failure-e2e-20261020-085340/` |
| End-to-end after fixing the test (check by text) | **PASS**, 5 of 5 repetitions, 15 journeys each | `docs/evidence/admin-phase-4/e2e-20261020-090539.json` |
| Text files | **PASS** (458 files) | console |


## 6. Security and audit implications

- New capability that affects other users: ending a session loses its uncommitted work. Bounded by PostgreSQL's own rule, the execution switch, a confirmation and a fail-closed audit record. Threat T37.
- T36 is closed by the stakeholder's decision: no other role's SQL text is shown.
- New audit event types `session_signal` (before; details: action, target pid, user, database) and `session_signal_outcome` (after). No SQL text, no data.

## 7. Known limitations

- A role with `pg_signal_backend` can end any non-superuser session it can see, as in any client. Seeing them needs `pg_read_all_stats`.
- There is no bulk action ("end all idle sessions"): one session at a time, each confirmed.

## 8. Next

Delivered afterwards under the decisions in §10: admin Phase 5 (management through generated SQL, `admin-phase-5-status.md`), Phase 6 (table data browser and row editing, `admin-phase-6-status.md`), Phase 7 (result export and CSV import, `admin-phase-7-status.md`). Under the second delegation (2026-10-20): Phase 8 schema comparison (A13), Phase 9 audit viewer (A14), Phase 10 completion (A15), Phase 11 saved queries (A16), Phase 12 dark mode (A17), Phase 13 keyboard customisation (A18), Phase 14 two-person approval (A19). Not built: backup and restore (A6; draft ADR-0010, Proposed).

## 9. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. The decisions in §10 are open to the stakeholder's review; each can be reversed as stated.

## 10. Decisions made autonomously

Taken on the stakeholder's instruction of 2026-10-20 (see the authority note at the top). Each one picks the more conservative option where there was a choice. None of them changes an Accepted ADR. **None turns SQL execution on outside tests**: everything that changes something runs only where execution is enabled, so production stays as decided on 2026-10-17. Decisions the stakeholder made directly are in `decision-log.md`, not here.

| # | Decision | Reason | How to reverse |
|---|---|---|---|
| A1 | **Generated SQL is the only way management features change anything.** Management screens (schemas, tables, columns, constraints, indexes, views, sequences, databases, roles) generate SQL and open it in a new editor tab for review. It runs only when the user presses Run or Run script, through the same gate, confirmation, kill switch, limits and audit as typed SQL. There is no separate execution path (the proposed ADR in admin-phase-3-status §8) | No new route to the database; every existing control applies unchanged; the user always sees the exact SQL first | Remove the generator dialogs (frontend only); nothing on the server depends on them |
| A2 | **Table data: browse first, edit only with a primary key.** Browsing runs a generated `SELECT … LIMIT` through the gate. Editing is offered only for tables with a primary key whose columns are all readable, and only for the privileges the role has. Each save is one transaction: a dialog shows every statement first; the statements run as a script inside `BEGIN … COMMIT`, with a `ROLLBACK` if any fails. Refused while the session already has a transaction open | Rows without a key can't be addressed safely; one reviewed transaction per save keeps changes atomic and visible | Remove the editing controls; browsing stays |
| A3 | **Create, rename and drop database** only as generated SQL under the user's own role (A1); never offered for the connected database; drop and rename always go through the gate's confirmation | No privileged account exists (D14c); PostgreSQL enforces ownership or `CREATEDB` | Remove the dialogs |
| A4 | **Export only what the browser already holds.** CSV or JSON of the rows on screen, bounded by the existing paging limits (`maxFetchedRows`), so T29 is unchanged. A `data_export` audit record (statement HMAC, row and column counts, format; no data) must be written first, fail-closed. Text cells that start with `=`, `+`, `-`, `@`, tab or carriage return are prefixed with `'` in CSV (spreadsheet formula injection); numbers are never changed | No new bulk-read path; export stays as bounded as viewing; audit as for SQL | Remove the export buttons and the endpoint |
| A5 | **Import turns a CSV file into reviewable INSERT statements** in a new editor tab (at most 1 MB and 5,000 rows, parsed in the browser), run with Run script through the gate. No `COPY`, no server-side file handling. The script's statements are audited as any script | No new server path, no file storage; the user reviews what is sent | Remove the import dialog |
| A6 | **Backup and restore are not built.** | They need `pg_dump`/`pg_restore` launched on the app host with the user's delegated Kerberos identity, dump files at rest on the server (storage, retention, ACLs) and binaries outside the bundle. That needs an ADR with the infrastructure owner; restore is the most destructive operation in the plan. The conservative option is not to build it | Approve ADR-0010 (`docs/adr/0010-backup-and-restore.md`, drafted 2026-10-20, Proposed), then build |
| A7 | **Cancel and end other sessions: built (admin Phase 4)**, as a dedicated endpoint with PostgreSQL's own permission rule checked and enforced, client sessions only, never pgweb's own, the execution switch, a server-side confirmation and a fail-closed audit record | The plan asks for it; this is the narrowest form that still lets an owner stop their own runaway work | Remove the route and the buttons |
| A8 | **Role administration only as generated SQL** (A1) under the user's own `CREATEROLE`: create, alter attributes, grant and revoke membership, drop. **No password is ever generated** (Kerberos only, ADR-0002); superuser, replication and bypass-RLS attributes are not offered | No privileged account; no password workflow exists | Remove the dialogs |
| A9 | ~~Schema comparison is not built~~. **Superseded** by the stakeholder's request of 2026-10-20 (second delegation) and decision A13 | Optional in the plan; no request at the time | — |
| A10 | **The UI asks before every change it generates**, even where the profile's server-side `confirmDestructive` is off: data saves, signals, and the generator dialogs say what will run | The stakeholder asked for confirmation in the UI for anything destructive or affecting others | Remove the dialogs |
| A11 | **Signals follow the execution switch** (off in production) and are audited fail-closed | They change other users' work, like SQL | Decide otherwise in an ADR |
| A12 | **Your own role's sessions keep their SQL text in Activity** | The same PostgreSQL role can read it in any client; hiding it would only hinder finding your own runaway query | Change the `CASE` in `AdminCatalog.ACTIVITY` |
| A13 | **Schema comparison (admin Phase 8) is read-only and writes SQL for the editor.** A snapshot endpoint reads one schema as the user's role (read-only transaction, search path local to it, at most 2,000 relations, 2,000 routines and 8 million characters of definitions); the browser compares two snapshots and writes one transaction (`SET LOCAL search_path` to the target). **Objects and columns only in the target are not dropped unless the user ticks the option**; otherwise their DROP statements are comments. Partitioned tables, partitions, foreign tables and changes to identity or generated columns are reported, not generated; types, domains, comments, owners, privileges and RLS policies are not compared | No new path to the database (A1); dropping is the destructive part, so it is opt-in; guessing SQL for objects whose structure isn't fully read would be riskier than asking the user to write it | Remove the Compare tab and `GET …/snapshot`; nothing else depends on them |
| A14 | **The audit viewer (admin Phase 9) reads as the signed-in user and is off by default.** It connects to the audit database with the user's delegated identity, so only `pgweb_audit_reader` members read anything (ADR-0005 point 7 unchanged; addendum added); `pgweb_app` stays INSERT-only. Read only: no export, no purge, no edits. Every search is recorded first (`audit_view`, fail-closed). Bounded to 31 days and 200 rows per page; `auditViewer.enabled=false` in the production template | Giving pgweb's own account read access would create a second way to read the whole trail; reading as the user keeps PostgreSQL in charge. Off by default because the audit database must allow auditors' Kerberos logins first | Set `auditViewer.enabled=false` (default), or remove the route and the start-page card |
| A15 | **Completion offers only what the role can use, read lazily.** A new read, `GET …/completion?schema=…` (companion, as the user's role, constant queries, `metadata_browse`), returns one schema's usable relations, their usable columns (`has_column_privilege`) and executable routines, plus the session's search path; bounded (2,000 relations, 20,000 columns, 2,000 routines). The browser reads a schema only when the editor first needs it, keeps it for the session, and reads again after objects change. Aliases are found with a simple pattern over the text (`FROM`, `JOIN`, `UPDATE`, `INTO`, `TABLE`), not a parser | Offering names the role can't use would only invite errors; reading everything up front would load large catalogs and add audit records for nothing | Remove the completion source from the editor (keywords stay) and the endpoint |
| A16 | **Saved queries are files on the app server, per account, never shared, and off by default.** One JSON file per Windows account (named by a SHA-256 of the SID) under `server.workDir\saved-queries`, written atomically, with the work folder's ACL (service account only). At most 200 queries of 100,000 characters; names unique per account. Saving and deleting are audited first (fail-closed) with the ID, lengths and the statement HMAC, never the text. `savedQueries.enabled=false` in the production template | The text is SQL that may contain literals, at rest outside PostgreSQL (T43); no new database or schema is needed, and nothing is shared, so no access model beyond "your own" has to be decided. Off by default so the operator decides whether text at rest on the app server is acceptable | Set `savedQueries.enabled=false` and delete the `saved-queries` folder; or remove the route and the UI |
| A17 | **Display preferences live in the browser (local storage), not on the server.** One key, `pgweb.preferences.v1`, holds the colour theme (and, from Phase 13, the editor's keys). "Same as Windows" is the default. Every read and write tolerates blocked storage; the page says when a choice can't be kept. Colours are tokens on `:root`, with a dark set for `data-theme="dark"` and for the system's dark setting | A per-browser convenience; keeping it on the server would add a store and an endpoint for no security benefit. Nothing in it is sensitive | Remove the Settings button; the light colours stay |
| A18 | **Only the editor's pgweb commands can be rebound** (Run, Run script, Explain, Explain analyze, comment lines), kept with the other display preferences in the browser. Keys the browser and basic editing need are reserved; a key can't serve two commands; invalid stored values fall back to the defaults. Run script gets Ctrl+Shift+Enter by default (new); Explain has no default key. Keyboard commands obey the same "can run" state as the buttons | Rebinding CodeMirror's own editing keys or browser keys could leave users unable to edit or to leave the page; the commands people asked about are the pgweb ones | Remove the shortcuts section; the defaults stay |
| A19 | **Two-person approval is off unless configured, held in memory, and bound tightly.** A profile lists reasons (`profile.<name>.approvalReasons`: drop, truncate, ddl, privileges, procedural, no_where), which needs `auth.approverSid` (the approvers' AD group) and `confirmDestructive=true`. An approval is for exactly one text (HMAC), one requester, app session and database session, one use, within 15 minutes of the decision; requests expire after 30 minutes; at most 5 open per account and 500 in all. The requester can never decide, even as an approver. Requests (with the text the approver must read) live in memory only and end with a restart. Requesting, deciding and withdrawing are audited first (fail-closed) without the text; the run's intent carries the approval ID. Nothing is configured in the production template | The text must be readable by the approver but shouldn't be stored; binding to one session and one use stops an approval from being reused for something else; off by default because the approver group and the reasons are the organisation's decision | Remove `approvalReasons` from the profiles (the feature is then inert); or remove the route and the checks |
