# Admin Phase 5 Status: Management Through Generated SQL

> Updated 2026-10-20. Fifth delivery of the stakeholder's plan "Comprehensive PostgreSQL Administration Workspace" (its Phases 3 and 6: schema, table, column, constraint, index, view, sequence, database and role management). Built under decisions A1, A3, A8 and A10 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-5/`. Workstation only; every test ran against throwaway clusters.

## 1. Scope

| Plan item | Delivered | Where |
|---|---|---|
| Create, alter and drop schemas, tables, columns, constraints, indexes, views, materialised views, sequences | **Manage** tab: 22 of its 35 actions (the other 13 are databases, roles and privileges, below). Create, rename, change owner, drop schema; create table with a column editor (types, NOT NULL, defaults, primary key, unlogged, comment); rename, comment and drop tables, views, materialised views and sequences; add, rename, retype, NOT NULL, default and drop columns; add primary key, unique, check and foreign-key constraints, drop constraints; create and drop indexes (methods, unique, concurrently); create views and materialised views, refresh; create and restart sequences | `features/manage/ManagePanel.jsx`, `sqlGen.js` |
| Generated SQL with a preview before it runs | The SQL is shown as you type; **Open in editor** puts it in a new editor tab. It runs only with Run or Run script | same |
| Create, rename, drop database | Create (owner, template, encoding), rename, change owner, drop: never offered for the connected database; drop without `FORCE`, so PostgreSQL refuses while anyone is connected | same |
| Role administration (plan Phase 6) | Create role (login, inherit, create databases, create roles, connection limit, member of, comment), change attributes, rename, grant and revoke membership (with admin option), drop; grant and revoke table privileges; grant schema usage. **No passwords, no superuser, replication or bypass-RLS** | same |

## 2. Design

- **One path to the database.** The Manage tab only produces text. Running it is the existing execution path: the single-statement gate (each statement of a script separately), the server's confirmation for DDL, DCL and drops (`ddl`, `privileges`, `drop`), the kill switch, the timeout, limits and the fail-closed audit intent before each statement. No new endpoint, no new privilege: PostgreSQL decides what the user's own role may do.
- **Quoting.** Every name goes through `quoteIdent` (quoted unless lower-case, simple and not a keyword; `"` doubled); every text value through `quoteLiteral` (`'` doubled; the `E''` form when the text contains a backslash, so it means the same with either `standard_conforming_strings` setting). Names longer than 63 bytes or containing NUL are refused before anything is generated.
- **What the user writes is SQL.** Types, defaults, check conditions, `USING` expressions and view queries are SQL by nature. They are passed through trimmed and without a trailing semicolon; type names must look like type names (letters, digits, `_ $ . " ( ) , [ ]` and spaces). The user sees and runs them as their own role, so this adds nothing they couldn't type.
- **Warnings.** Destructive actions are marked in the form ("pgweb asks you to confirm"); the confirmation itself is the server's, when the SQL runs.

## 3. Files changed

| File | Change |
|---|---|
| `frontend/src/features/manage/sqlGen.js` (new) | The generators, quoting and input checks |
| `frontend/src/features/manage/ManagePanel.jsx` (new) | The Manage tab: action list, forms, column editor, privileges, preview, Open in editor |
| `frontend/src/app/Workspace.jsx`, `styles.css` | The Manage tab next to Activity, Database and Roles |
| `scripts/test/Invoke-E2E.ps1` | The throwaway E2E role gets `CREATEDB CREATEROLE`, so database and role SQL can be run end to end |
| Tests | `sqlGen.test.js` (12), `manage.test.jsx` (5), E2E journey "admin Phase 5" |
| Docs | This file; `user-guide.md` §7b; `threat-model.md` T38; README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Authorised on the server | **Met**: the existing execution path; PostgreSQL's privileges of the user's own role | E2E (runs as the test role) |
| Generated SQL quotes identifiers and values safely | **Met** | `sqlGen.test.js` (hostile names and values, keywords, backslashes, NUL, 63-byte limit) |
| Destructive operations require confirmation | **Met**: the server's confirmation on run; the form marks them | E2E (every generated script confirmed) |
| Audited | **Met**: each statement's audit intent and outcome, as for typed SQL | unchanged path |
| Never the connected database | **Met** | `sqlGen.test.js`, `manage.test.jsx`, E2E |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Frontend targeted: `sqlGen.test.js`, `manage.test.jsx`, Workspace tests, lint | **PASS** | console |
| E2E trial, 1 repetition (16 journeys) before the gate | **PASS** | console (scratch tag, not kept) |
| Randomised loop, 5 consecutive runs (191 frontend, 84 backend unit, 173 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-5/loop-20261020-093336.json` |
| End-to-end, 5 repetitions (16 journeys each; the new one creates a table, adds a column, a primary key and an index, inserts, creates and drops a database, creates a role, grants and revokes, drops the role and the table, all through generated SQL with the server's confirmation) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-5/e2e-20261020-094133.json` |
| Text files | **PASS** | console |


## 6. Security and audit implications

No new endpoint, route, privilege or audit event. Threat T38 (generated SQL does something other than the form says) is covered by the quoting rules and their tests, and by showing the exact SQL before anything runs.

## 7. Known limitations

- No ALTER for views (use Create view with OR REPLACE), functions, triggers, policies, partitions, tablespaces or extensions; their definitions can be opened from the explorer and edited as SQL.
- Lists of column names are comma-separated; a column whose name contains a comma must be typed in the SQL editor.
- The forms don't read the catalog to suggest names; the explorer and the Roles tab show them.

## 8. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5.
