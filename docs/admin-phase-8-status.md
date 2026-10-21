# Admin Phase 8 Status: Schema Comparison

> Updated 2026-10-20. Eighth delivery of the administration plan, requested by the stakeholder on 2026-10-20 ("Schema comparison (A9): read-only. It compares two schemas or databases and generates the diff SQL into the editor, never running it"). Built under decision A13 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-8/`. Workstation only; every test ran against throwaway clusters.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Compare two schemas of one database | **Compare** tab: read a source schema, compare a target schema, list the differences | `features/compare/ComparePanel.jsx` |
| Compare schemas of two databases | The source is kept by the workspace while you switch database; compare in the other database | `app/Workspace.jsx` |
| Diff SQL into the editor, never run | **Open SQL in editor**: one transaction that makes the target like the source; runs only with Run script | `features/compare/schemaDiff.js` |
| Structure read | `GET /api/metadata/{session}/snapshot?schema=…`: tables, columns, constraints, indexes, triggers, views, materialised views, sequences, functions and procedures | `metadata/SchemaSnapshot.java` |

## 2. Design

- **Read as the user, read-only.** The snapshot is read on the session's companion connection as the user's own role, with the object browser's rules: constant queries, the schema name as a bound parameter, a timeout per query, one read at a time per session, a `metadata_browse` record (`object: snapshot`). It runs in a read-only transaction that is always rolled back.
- **Definitions that work in another schema.** The snapshot's transaction sets `search_path` to the compared schema alone (`set_config(…, true)`, local to the transaction). PostgreSQL then writes names in that schema without it, and qualifies everything else. Two places where PostgreSQL always qualifies are corrected: function and procedure headers (`CREATE OR REPLACE FUNCTION s.f(` becomes `f(`) and the table in index definitions (`ON s.t USING` becomes `ON t USING`, looked for only right after the index's own name). The generated script sets `SET LOCAL search_path TO <target>` inside its transaction, so the same text creates the objects in the target.
- **Left out of the snapshot:** objects that belong to an extension, and sequences of identity columns (they come with the column). Bounded: 2,000 relations, 2,000 routines, 8 million characters of definitions; above that, `SNAPSHOT_TOO_LARGE`.
- **The comparison runs in the browser** and writes, in order: drops (dependants first: triggers, constraints and indexes; views; columns and tables; routines and sequences), sequences, tables, column changes (add, type, default, NOT NULL, unlogged), functions and procedures, constraints, foreign keys last, views and materialised views, indexes, triggers. Changed constraints, indexes, triggers and materialised views are dropped and created again; changed views use `CREATE OR REPLACE VIEW`.
- **Drops are opt-in (A13).** Objects and columns that exist only in the target are listed; their DROP statements are written as comments unless **Drop what exists only in the target** is ticked.
- **Reported, not generated:** partitioned tables, partitions and foreign tables, and changes to identity or generated columns. **Not compared:** types and domains, comments, owners and privileges, row-level security policies, sequence ownership and current values. The script's header lists them.
- **Names in comments can't end the comment.** Every name or value written into an SQL comment has its control characters replaced by `?`; a commented DROP whose name contains a line break is replaced by a note.
- **Nothing runs from the tab.** The SQL is opened in a new editor tab; Run script sends it through the gate, the confirmation (DDL asks first), the kill switch and the audit, as the user's role, like typed SQL.

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/metadata/SchemaSnapshot.java` (new) | The snapshot |
| `backend/…/metadata/MetadataServlet.java`, `Application.java` | `GET …/snapshot` |
| `frontend/src/features/compare/schemaDiff.js`, `ComparePanel.jsx` (new) | Comparison, SQL, panel |
| `frontend/src/api/metadata.js`, `app/Workspace.jsx`, `styles.css` | `getSnapshot`; Compare tab; the source survives switching database |
| Tests | `AdminCatalogIT` (+2: snapshot content, unqualified definitions, search path restored, quoted schema, 404/400, ownership, audit; header and index rewriting), `ArchitectureTest` (constant-query rule covers `SchemaSnapshot`), `schemaDiff.test.js` (5, one randomised over names), `compare.test.jsx` (2), E2E journey "admin Phase 8" |
| Docs | This file; `user-guide.md` §7e; `threat-model.md` T41; `requirements.md` FN-24; `admin-phase-4-status.md` §10 (A9 superseded, A13); `decision-log.md`; README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Read-only | **Met**: snapshot in a read-only transaction, rolled back; nothing runs from the tab | `AdminCatalogIT`, E2E |
| Compares two schemas or databases | **Met** | E2E: two schemas in `e2e_db`, then a schema of `e2e_other` against a source read in `e2e_db` |
| Diff SQL in the editor, never run by the feature | **Met** | `compare.test.jsx`, E2E |
| Applying the SQL makes the target like the source | **Met** for what is compared | E2E: after Run script, comparing again leaves only the table that exists only in the target; the copy's trigger and function work |
| Destructive parts need a deliberate choice | **Met**: drops commented unless ticked; DDL confirmation when run | `schemaDiff.test.js`, E2E |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted: `AdminCatalogIT` (17), `ArchitectureTest` | **PASS** (after fixing two findings of the first runs: partitioned tables were dropped from the snapshot by their internal dependency on their own key columns; index definitions keep the table's schema) | console |
| Frontend targeted: compare and Workspace tests, lint | **PASS** | console |
| E2E trial, 1 repetition (19 journeys) | **PASS** | console |
| Randomised loop, 5 consecutive runs (223 frontend, 84 backend unit, 177 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-8/loop-20261020-234530.json` |
| End-to-end, 5 repetitions (19 journeys each) | **PASS**, 5 of 5 (the new journey builds two schemas, compares, runs the generated script, compares again, then compares with a schema in `e2e_other` after switching database, and removes everything) | `docs/evidence/admin-phase-8/e2e-20261020-235420.json` |

## 6. Security and audit implications

- No new way to change anything: the generated SQL goes through the existing path (A1). T41 covers SQL that does more than expected and names that could break out of a comment.
- New read: `GET …/snapshot`, audited as `metadata_browse` with `object: snapshot` and the schema name. It shows definitions the role can already read from the catalog.

## 7. Known limitations

- Views that depend on other new views are created in name order; if one needs another that comes later, the script stops there (roll back, reorder, run again).
- Dropping a constraint that a foreign key in another table depends on fails; the script stops and nothing changes.
- Function bodies are compared as text; a function whose return type changed can't be replaced with `CREATE OR REPLACE` (PostgreSQL refuses it; drop it first).

## 8. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. Decision A13 is open to the stakeholder's review.
