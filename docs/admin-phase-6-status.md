# Admin Phase 6 Status: Table Data Browser and Row Editing

> Updated 2026-10-20. Sixth delivery of the stakeholder's plan "Comprehensive PostgreSQL Administration Workspace" (its Phase 3 table data browser and inline editing). Built under decisions A2 and A10 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-6/`. Workstation only; every test ran against throwaway clusters.

## 1. Scope

| Plan item | Delivered | Where |
|---|---|---|
| Table data browser | **Data** button on tables, partitioned tables, views, materialised views and foreign tables in the explorer; the **Data** tab shows 100 rows at a time, by primary key (or the first column), with Previous and Next, Reload and **Open query in editor** | `features/data/DataPanel.jsx`, `ObjectExplorer.jsx`, `Workspace.jsx` |
| Inline data editing | **Edit**, **Delete** and **Add row**, only for a table with a primary key and only for the privileges your role has (UPDATE, DELETE, INSERT). Generated and `GENERATED ALWAYS` identity columns are read-only; NULL and Default are explicit choices. **Review and save** shows every statement; one save is one transaction | same, `rowEdit.js` |

## 2. Design

- **Reading** is a generated `SELECT <every column> FROM <table> ORDER BY <key> LIMIT 101 OFFSET n` sent through the normal execution path: gate, limits, timeout, audit, as the user's own role (no new endpoint). The 101st row only says whether there is a next page.
- **Rows are addressed by their primary key** only. The relation description now includes the key's columns in key order (`MetadataService.PRIMARY_KEY`, a constant query). Tables without a key can be read but not changed: a row can't be addressed safely.
- **No lost updates.** Each UPDATE also requires that the columns it changes still hold the text that was loaded (`col::text IS NOT DISTINCT FROM '…'`). If someone changed the row meanwhile, the UPDATE affects 0 rows.
- **One save, one transaction, all or nothing.** The statements are shown in a confirmation dialog. On Save, `BEGIN` and the statements run as one script (each statement through the gate with its own audit intent); then pgweb checks that every UPDATE and DELETE affected exactly one row and commits, otherwise it rolls back and says which row. On an error, or if the answer is lost, it rolls back too. Saving is refused while the session already has a transaction open, so the rollback can only undo the save itself.
- **Values** are PostgreSQL's own text, sent back as quoted literals (`'…'`, `E'…'` with a backslash) and converted by PostgreSQL to the column's type, so nothing is lost to JavaScript numbers or dates. Rows with a value cut for display (64 KiB) can't be edited.

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/metadata/MetadataService.java` | `primaryKey` in the relation description |
| `frontend/src/features/data/rowEdit.js`, `DataPanel.jsx` (new) | Statements for a save; the Data tab |
| `frontend/src/features/explorer/ObjectExplorer.jsx`, `app/Workspace.jsx`, `styles.css` | **Data** button; the Data tab |
| Tests | `AdminCatalogIT` (primary key in key order), `rowEdit.test.js` (7), `data.test.jsx` (6), E2E journey "admin Phase 6" |
| Docs | This file; `user-guide.md` §7c; `threat-model.md` T39; README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Authorised on the server | **Met**: the execution path, the user's own role; the buttons follow `myPrivileges`, PostgreSQL enforces | `data.test.jsx`, E2E |
| Values and names quoted safely | **Met** | `rowEdit.test.js` (hostile values, backslashes, composite keys) |
| Changes need confirmation in the UI | **Met**: the dialog with every statement | `data.test.jsx`, E2E |
| All or nothing; no lost updates | **Met** | `data.test.jsx` (0 rows → rollback; error → rollback; failed request → rollback); E2E |
| Audited | **Met**: every statement's intent and outcome, as for typed SQL | unchanged path |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted: `AdminCatalogIT` (14), `MetadataIT` (8), `ArchitectureTest`; frontend data, explorer and Workspace tests, lint | **PASS** | console |
| E2E trial 1 (1 repetition) | **FAIL** in the new journey only: `Edit row 1` also matched `Edit row 10` … `Edit row 19` (Playwright names match substrings), a strict-mode error in the test. Fixed with `exact: true`. Trace kept outside the repository | console |
| E2E trial 2 | **PASS**, 17 journeys | console |
| Randomised loop, 5 consecutive runs (204 frontend, 84 backend unit, 174 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-6/loop-20261020-101228.json` |
| End-to-end, 5 repetitions (17 journeys each; the new one edits a row, adds one with a quote and a backslash, deletes it and restores the first in one save, then checks the database) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-6/e2e-20261020-102358.json` |
| Text files | **PASS** | console |


## 6. Security and audit implications

No new endpoint or audit event. T39: a save changes rows the user didn't intend, or overwrites someone else's change; covered by key-only addressing, the original-value check, the review dialog, and the all-or-nothing transaction.

## 7. Known limitations

- 100 rows per page, by offset: on a large table later pages get slower, and rows inserted meanwhile shift the pages.
- No filter or sort in the grid; **Open query in editor** gives the SELECT to adapt.
- Values are edited as text in a form, one row at a time; no in-cell editing, no editor for JSON or arrays.
- A table without a primary key can't be edited here (a unique index alone isn't used).

## 9. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5.
