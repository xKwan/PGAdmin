# Admin Phase 7 Status: Result Export and CSV Import

> Updated 2026-10-20. Seventh delivery of the stakeholder's plan "Comprehensive PostgreSQL Administration Workspace" (its Phase 4, import and export). Built under decisions A4 and A5 (`docs/admin-phase-4-status.md` §10). Backup and restore (the plan's Phase 5) are **not built** (decision A6). Evidence: `docs/evidence/admin-phase-7/`. Workstation only; every test ran against throwaway clusters.

## 1. Scope

| Plan item | Delivered | Where |
|---|---|---|
| Export query results | **Export CSV** and **Export JSON** on a row result: the rows on screen (including pages loaded with *Load more rows*), as PostgreSQL's text. CSV keeps NULL (empty) and the empty string (`""`) apart, as `COPY … CSV` does | `features/export/ExportButtons.jsx`, `csv.js` |
| Import CSV into a table | **Import CSV…** in the Data tab (tables you may INSERT into): the file is read in the browser and becomes INSERT statements in a new editor tab, inside `BEGIN … COMMIT`; it runs only with **Run script** | `DataPanel.jsx`, `csv.js` |
| Backup and restore | **Not built** (A6): needs an ADR with the infrastructure owner | — |

## 2. Design

- **Export adds no new way to read data.** It saves what the browser already holds, bounded by the existing limits (1,000 rows per page, at most 10,000 per statement with *Load more rows*), so T29 is unchanged.
- **The export is recorded first.** `POST /api/db-sessions/{id}/export` with the format, row and column counts and the statement's execution ID writes `data_export` (fail-closed: if it can't be written, the file is not saved). The data itself is never sent back or recorded. The record is accountability, not a control: the rows are already on the user's screen.
- **Spreadsheet formula injection.** In CSV, a text value starting with `=`, `+`, `-`, `@`, tab or carriage return gets a leading `'`, so a spreadsheet shows it instead of running it. Numbers such as `-5` or `+1.5e3` are left alone. JSON is unchanged.
- **Import is reviewable SQL.** CSV (RFC 4180: quotes, doubled quotes, line breaks in quoted fields; a BOM is ignored) up to 1 MB and 5,000 rows, UTF-8 only, parsed in the browser. The header names the columns, which must exist in the table. An empty unquoted field is NULL, `""` is the empty string. Values become quoted literals; PostgreSQL converts them to the column types. 100 rows per INSERT, all in one transaction, at most 900 KB of SQL (below the server's 1 MiB limit). Nothing is sent to the server until the user runs the script; each statement then goes through the gate with its own audit intent. No `COPY`, no server-side files.

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/execution/ExecutionEngine.java`, `dbsession/StatementRunner.java`, `DbSessionServlets.java` | `POST …/export`: validation and the `data_export` record |
| `frontend/src/features/export/csv.js`, `ExportButtons.jsx` (new) | CSV and JSON writing, CSV parsing, INSERT script |
| `frontend/src/features/results/ResultView.jsx`, `app/Workspace.jsx`, `api/execution.js` | Export buttons; `recordExport` |
| `frontend/src/features/data/DataPanel.jsx` | **Import CSV…** |
| Tests | `ExportIT` (1: record without data, validation, ownership), `csv.test.js` (8), `export.test.jsx` (3), `data.test.jsx` (+2, import), E2E journey "admin Phase 7" |
| Docs | This file; `user-guide.md` §7d; `audit-policy.md` §2; `threat-model.md` T40; `requirements.md` FN-19 to FN-23 and §5; README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Export bounded and audited, without the data | **Met** | `ExportIT`, `export.test.jsx`, E2E |
| Import authorised on the server | **Met**: INSERT runs as the user's role through the gate | E2E |
| Values quoted safely | **Met** | `csv.test.js` |
| Malformed files refused with a reason | **Met** | `csv.test.js` |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted: `ExportIT`, `ArchitectureTest`; frontend export, data, results and Workspace tests, lint | **PASS** | console |
| E2E trial, 1 repetition (18 journeys) | **PASS** | console |
| Randomised loop, 5 consecutive runs (216 frontend, 84 backend unit, 175 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-7/loop-20261020-105113.json` |
| End-to-end, 5 repetitions (18 journeys each; the new one exports a result as CSV and checks the file byte for byte, including the formula prefix, then imports a CSV with a quoted comma, a NULL and an apostrophe through Run script, checks the rows in the database and removes them) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-7/e2e-20261020-105925.json` |
| Text files | **PASS** | console |


## 6. Security and audit implications

- New audit event `data_export` (before the file is saved; format, rows, columns, execution ID; no data). Fail-closed.
- T40: exported files leave the controlled environment; CSV formula injection. Bounded by the existing row limits, recorded, formula prefixes neutralised. Where the file goes afterwards is outside pgweb, as for anything on screen.

## 7. Known limitations

- Export covers the rows loaded in the browser, not the whole result of a large query (by design, T29).
- Values cut for display at 64 KiB are exported as shown.
- Import: comma-separated only, UTF-8 only, 1 MB and 5,000 rows at a time; a column name containing a comma must be quoted in the header (it is, by any CSV writer).

## 8. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. This completes the buildable part of the plan; backup and restore wait for an ADR (A6; draft ADR-0010 written 2026-10-21, Proposed, awaiting approval).
