# Admin Phase 2 Status: SQL Editor, Scripts, Paging and Explorer

> Updated 2026-10-20. Second phase of the stakeholder's plan "Comprehensive PostgreSQL Administration Workspace". Started after the Phase 1 report ("Check and continue") and four explicit decisions recorded in `docs/decision-log.md` (2026-10-19): EXE-03 "stop at first error", query history "this browser tab only", P9-3 "fetch more", release "continue; sign off later". Evidence: `docs/evidence/admin-phase-2/`. Workstation only: Windows 11, no domain, Edge, PostgreSQL 18.6, NTLM loopback. Reported on 2026-10-19; the stakeholder replied "Continue all phases and I will check later" (2026-10-20): the read-only parts of the remaining plan follow as admin Phase 3.

## 1. Scope against the plan

| Plan item (Phase 2) | Delivered | Where |
|---|---|---|
| Create, open, edit, save, save-as `.sql` files | Editor tabs; **Open file…** / **Save** / **Save as…** with Edge's File System Access API (save in place), upload/download where it is missing; UTF-8, at most 5 MB, NUL refused; files never reach the server | `EditorArea.jsx`, `sqlFiles.js`, `useEditorTabs.js` |
| Execute the selected statement or an explicitly selected script | **Run** / Ctrl+Enter unchanged (one statement). **Run script** (button only, no shortcut) runs the selection or the whole tab: `POST …/script` (EXE-03) | `ExecutionEngine.script`, `StatementGate.checkScript` |
| Result sets, affected rows, duration, structured PostgreSQL errors | As before; a script lists every statement with its own result, error (SQLSTATE, detail, hint, position mapped into the editor) and *Not run* for the rest | `ResultView.jsx` |
| Cancellation | Cancel stops the running statement, and a cancelled script starts nothing more (also between statements); a page fetch can be cancelled | `ExecutionEngine.cancel`, `timed` |
| Transaction state and explicit controls | As before (Phase 9); scripts and pages follow the session's transaction; nothing is committed or rolled back for the user | — |
| Warn before discarding unsaved changes | Closing a changed tab, **← Servers** with changed tabs, and closing the browser tab (`beforeunload`) ask first | `EditorArea.jsx`, `Workspace.jsx` |
| Multiple editor tabs and query history | Tabs survive switching database and reconnecting; History tab: last 50 runs in `sessionStorage` per user and server, Insert / Open in a new tab / filter / Clear | `HistoryPanel.jsx`, `queryHistory.js` |
| Explicit execution mode, warning when disabled | The existing badge; the editor is usable without a session or with execution off, Run and Run script disabled with the reason shown | `Workspace.jsx` |
| Don't run a whole script when one statement was meant | Run script is a separate button with no shortcut; Ctrl+Enter still runs one statement; `/execute` still refuses several | — |
| Explorer: databases, schemas, tables and partitioned tables, views, materialised views, columns, PK/FK/unique/check, indexes, sequences, functions, procedures, triggers, extensions | Added: connected-database header, partitioned tables and **partition** marker, triggers (disabled marked), extensions, other databases with **Switch** (the same safe switch as Phase 1). The rest existed (Phase 10) | `MetadataService`, `ObjectExplorer.jsx` |
| Refresh actions; empty, loading, permission-denied and error states | Existing Refresh, plus an automatic reload once a change to objects (DDL, GRANT/REVOKE) is committed or auto-committed | `Workspace.jsx` (`changedObjects`) |
| Large results not loaded entirely | Inside a transaction: first 1,000 rows, then **Load more rows** a page at a time from a server-side cursor, up to `limits.maxFetchedRows` (10,000) in all (P9-3). Outside: cut at 1,000 as before, with **Run again in a read-only transaction to load more** | `ExecutionEngine.fetch`, `ResultGrid.jsx` |

## 2. Design decisions within the approved scope

- **Scripts are checked whole before anything runs.** PostgreSQL's parser splits the text; each statement's own text then passes the unchanged single-statement gate (parser and pgJDBC both see exactly one statement, nothing unsupported). Only those texts are sent, one at a time, so pgJDBC never splits anything itself. A syntax error or unsupported statement anywhere refuses the whole script, with its position.
- **One audit intent per statement, fail-closed, written only after the previous statement ended.** If an intent can't be written, that statement isn't sent and the script stops (`audit_unavailable`); the statements before it have run and are reported. A `script_outcome` record (best effort) ties the script together.
- **A script stops if `standard_conforming_strings` changes**: statement boundaries depend on it, so the remaining boundaries can't be trusted (`settings_changed`).
- **One confirmation per script** for destructive statements (all reasons listed), the same safeguard as for one statement.
- **Cursors only inside a transaction.** pgJDBC streams rows only with its autocommit flag off. pgweb keeps that flag equal to PostgreSQL's own transaction state (off only while a transaction is open, on once it ended), so the driver never sends `BEGIN` or `COMMIT` of its own and auto-commit statements behave exactly as before, including `VACUUM` (tested). In auto-commit there is no cursor; the user can choose a read-only transaction. This is narrower than the option text approved ("in auto-commit the cursor is closed when the next statement runs"): keeping a cursor in auto-commit would need pgweb to open a transaction the user didn't ask for, which changes when statements commit. **Reported for confirmation**, see §8.
- **Each page is audited** (`exec_fetch`, fail-closed, linked to the statement; `exec_fetch_outcome` after) and runs under the statement timer and cancel.
- **History holds full text in the browser tab only** (approved). No server storage, no `localStorage`.

## 3. Files changed and why

| File | Change |
|---|---|
| `backend/…/sqlparse/SqlParser.java` | Statements carry their offset in the text (UTF-16), for script positions |
| `backend/…/execution/StatementGate.java` | `checkScript`: whole-text limits, parser split, each statement through `check`; `TOO_MANY_STATEMENTS` |
| `backend/…/execution/ExecutionEngine.java` | One shared path for a statement, a script statement and a page (`runApproved`, `timed`); `script`, `fetch`, cursor lifecycle, autocommit alignment, script cancel and progress; shared response budget per script |
| `backend/…/dbsession/DbSession.java` | Slot for the open cursor, closed with the session |
| `backend/…/dbsession/StatementRunner.java`, `DbSessionServlets.java` | `POST …/script`, `POST …/fetch` (strict JSON bodies) |
| `backend/…/metadata/MetadataService.java`, `MetadataServlet.java` | Partitioned tables and partitions, triggers, `GET …/extensions` (constant parameterised queries, ArchUnit rule unchanged) |
| `backend/…/config/Config.java`, `ConfigLoader.java` | `limits.maxScriptStatements` (1,000), `limits.maxFetchedRows` (10,000, not below `maxRows`) |
| `frontend/src/api/execution.js`, `metadata.js` | `runScript`, `fetchMore`, `listExtensions`; messages |
| `frontend/src/features/editor/*` | Tabs, files, editor `onChange`, `script()`, `select()` |
| `frontend/src/features/history/*` | History storage and panel |
| `frontend/src/features/results/*` | Script results, progress, Load more, read-only re-run, `appendPage` |
| `frontend/src/features/explorer/ObjectExplorer.jsx` | Database header, partitioned tables, triggers, extensions, other databases, `refreshKey` |
| `frontend/src/features/connections/SessionBar.jsx` | `ref.switchTo(database)` for the explorer's Switch (same flow, same confirmations) |
| `frontend/src/app/Workspace.jsx`, `styles.css` | Wiring, results/history tabs, unsaved-change warnings, explorer refresh |
| Tests | `ScriptAndFetchIT` (14), `StatementGateTest` (+2), `MetadataIT` (+1), `ConfigLoaderTest` (+2 faults, defaults); `scripts.test.jsx` (14), `files.test.jsx` (11), `history.test.jsx` (6), `explorer-admin.test.jsx` (4), `Workspace.admin.test.jsx` (7); E2E journey (editor.spec.js) |
| Docs | This file; `sql-execution-behaviour.md` §1, §2a, §4a, §6; `audit-policy.md` §2; ADR-0006 implementation note; `requirements.md` (EXE-03, EXE-05, FN-13 to FN-15, deferred list); `user-guide.md` §4, §5, §7, §8, §9; `threat-model.md` T34, T35; `decision-log.md`; `dev-setup.md` limits; README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Users can open and save scripts without losing existing content | **Met** | `files.test.jsx` (open, save in place, save as, download, unsaved prompts, picker cancel, invalid files); `Workspace.admin.test.jsx` (leaving with changes) |
| SQL execution respects the execution mode and authorisation rules | **Met** | `ScriptAndFetchIT` (kill switch for script and fetch, another user's session 404, strict JSON), unchanged route access and ownership checks; RTL (disabled without a session or with execution off) |
| Results and errors are understandable and tied to the operation that produced them | **Met** | Per-statement script results with positions mapped to the editor; the source tab is named; `scripts.test.jsx`, E2E journey |
| The explorer reflects the connected database and refreshes after schema changes | **Met** | `explorer-admin.test.jsx`, `Workspace.admin.test.jsx` (refresh after auto-committed DDL and after COMMIT, not before) |
| Metadata without superuser privileges | **Met** | `MetadataIT` runs as ordinary roles; catalog functions only |
| No unauthorised objects or operations through alternate endpoints | **Met** | New endpoints use the same session ownership (404 for others), route access (DataAdmin for internal) and companion lock; `MetadataIT`, `ScriptAndFetchIT` |
| Large results not loaded entirely by the frontend | **Met** | Pages of `maxRows`, server cap `maxFetchedRows`; `ScriptAndFetchIT`, `scripts.test.jsx`, E2E (2,500 rows in three pages) |
| Existing login, authorisation and execution unchanged | **Met** | Full suites (5 consecutive loop runs) and E2E (13 journeys, 4 complete repetitions), §5 |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted: `StatementGateTest`, `ResultBuilderTest`, `ArchitectureTest`, `ScriptAndFetchIT`, `MetadataIT`, `ExecutionIT`, `ConcurrencyIT`, `DbSessionSwitchIT`, `DbSessionIT` | **PASS** (single runs during development) | console |
| Frontend: lint + 156 Vitest tests, coverage lines 99.4 %, branches 92.9 % | **PASS** (single run) | console |
| Randomised loop, 5 consecutive runs (156 frontend, 84 backend unit, 160 integration tests per run) | **PASS**, 5 of 5 consecutive | `docs/evidence/admin-phase-2/loop-20261020-004032.json` |
| End-to-end in Edge, 5 repetitions (13 journeys each) | **4 repetitions PASS, the 5th interrupted**: Claude Code ended the run because the workstation was low on memory (not a test failure; no journey had failed). Not restarted without the stakeholder's say-so, per the host's instruction. Earlier single repetitions on 2026-10-19 also passed | `docs/evidence/admin-phase-2/gate-console-20261020.log` |
| Text files | **PASS** (439 files, no control characters) | console |

The first attempt of the gate (2026-10-19) was also stopped for low memory before its first run finished; the second ran with `JAVA_TOOL_OPTIONS=-Xmx2g` to keep each JVM small.

Commands: `scripts\test\Invoke-TestLoop.ps1 -Tag admin-phase-2`; `scripts\test\Invoke-E2E.ps1 -Tag admin-phase-2 -Repeat 5`; `scripts\ci\Test-TextFiles.ps1`.

## 6. Security and audit implications

- **No new access level or credential path.** `/script` and `/fetch` live on the existing session route (same route access, ownership check, CSRF, strict JSON); `/metadata/{session}/extensions` on the existing metadata route.
- **The single-statement gate is unchanged** and still the only way text reaches PostgreSQL: a script is only a sequence of gate-approved statements. Statement classification remains metadata only.
- **Audit:** every statement of a script and every page has its own fail-closed intent; outcomes are best effort and spooled, as approved. New events: `script_outcome`, `exec_fetch`, `exec_fetch_outcome` (catalogue in `audit-policy.md`). No literals are stored (the normalised text rules are unchanged).
- **Browser-side data:** the history holds full SQL text, literals included, in `sessionStorage` (threat T34, decided). Files are read and written in the browser only (T35). No credentials or tokens are stored anywhere.
- **Resource bounds:** scripts up to `maxScriptStatements`, one response budget per script, each statement under its own timer; pages bounded by `maxRows` per response and `maxFetchedRows` per statement; an open cursor holds at most two pages in pgJDBC and lives only inside a transaction, which the idle-in-transaction rule (10 minutes) still ends.
- **T31 (in-process parser) is unchanged and still open**: scripts parse the same text once more per statement, under the same size limit and single parser thread.

## 7. Known limitations

- Run script runs on the server; the request stays open for the whole script (each statement has its own time limit, so a long script can take as long as their sum). Progress comes from the activity poll.
- Cursors exist only inside a transaction (§2). Load more is not offered for results inside a script.
- The history is per browser tab and is lost when it closes (as decided); there are no server-side saved scripts.
- File handling in browsers other than Edge/Chrome falls back to upload and download (no save in place). Edge is the supported browser.
- Kerberos, delegation and the non-DataAdmin view remain target-environment checks (K1–K6).

## 8. For the stakeholder

1. **Cursor in auto-commit (P9-3 detail).** Implemented: Load more only inside a transaction, with a one-click read-only transaction offered outside one (§2). Confirm this, or ask for an automatic read-only transaction for large results in auto-commit (that would hold locks until the user ends it).
2. **Phase 3 conflicts** (from `docs/admin-phase-1-status.md` §9, still open): management screens and generated DDL (deferred "DDL designers", G4); create/drop database with the user's own privileges only. They need decisions before they are built; the read-only parts of the plan follow as admin Phase 3.
3. **The fifth E2E repetition** can be rerun on request (`scripts\test\Invoke-E2E.ps1 -Tag admin-phase-2 -Repeat 5`) on a machine with more free memory.

## 9. Ready for review?

**Yes, for stakeholder review**, with one gap stated plainly: the loop passed 5 consecutive runs, the E2E suite 4 complete repetitions of 5 (the fifth was ended by the host for low memory, not by a failure). Committed and pushed so the work is not lost; the decisions in §8 remain open.
