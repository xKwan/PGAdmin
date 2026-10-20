# Admin Phase 10 Status: Schema-Aware Completion

> Updated 2026-10-21. Tenth delivery: the second deferred feature the stakeholder asked for on 2026-10-20 ("schema-aware autocomplete"). Built under decision A15 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-10/`. Workstation only; every test ran against throwaway clusters.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Names in the editor's completion | Schemas; tables and views on the search path; columns of the tables the statement names, also after an alias (`o.`) or a table (`items.`); after `schema.` its relations, functions and procedures; keywords as before | `features/editor/completion.js`, `SqlEditor.jsx` |
| Names read from the catalog | `GET /api/metadata/{session}/completion?schema=…` | `MetadataService.completion` |

## 2. Design

- **Only what the role can use**: relations with a table, column or sequence privilege, columns with a column privilege, routines with EXECUTE (`has_*_privilege`), read on the companion as the user's own role with the object browser's rules (constant queries, bound schema name, timeout, `metadata_browse` with `object: completion`).
- **Lazily, once per schema.** The browser asks for a schema only when the editor first needs it (the search path's schemas when you type a bare name, a named schema after `schema.`), keeps it for the session, and reads again after committed DDL changed objects or when the session changes. A schema that failed to load is tried again next time.
- **The search path** is the session's default (`current_schemas(false)` on the companion); a `SET search_path` in the editor's session isn't seen.
- **Aliases** come from a simple pattern over the text (`FROM`, `JOIN`, `UPDATE`, `INTO`, `TABLE` followed by a name and an optional alias), not a parser: it can miss unusual forms, and then simply offers less.
- Names are inserted quoted where needed (`"Order Lines"`). Bounded: 2,000 relations, 20,000 columns, 2,000 routines per schema (`truncated` when reached).

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/metadata/MetadataService.java`, `MetadataServlet.java` | `completion` |
| `frontend/src/features/editor/completion.js` (new), `SqlEditor.jsx`, `EditorArea.jsx`, `app/Workspace.jsx`, `api/metadata.js` | Completion source and catalog cache |
| Tests | `AdminCatalogIT` (+1), `completion.test.js` (6), `connections.test.jsx` (an older test now counts distinct sessions: completion may also read schemas while typing), E2E journey "admin Phase 10" |
| Docs | This file; `user-guide.md` §4 "Completion"; `requirements.md` FN-26 and §5; `admin-phase-4-status.md` §10 (A15); README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Completes schemas, tables and columns | **Met** | `completion.test.js`, E2E (a table after `public.`, a column of the named table, columns after an alias) |
| Only usable objects offered | **Met** | `AdminCatalogIT` (a table without privilege isn't offered) |
| Read-only, audited, owner-only | **Met** | `AdminCatalogIT` |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted: `AdminCatalogIT`, `ArchitectureTest` | **PASS** | console |
| Frontend targeted: editor and Workspace tests, lint | **PASS** (after replacing a ref read during render, flagged by the linter) | console |
| E2E trial, first attempt | **FAIL**, test only: the journey counted rows named `item 2…`, and another journey had left one more such row. Completion itself worked (the page snapshot showed the completed statement and its result). Changed to an exact, unique row | console |
| E2E trial after the fix (21 journeys) | **PASS** | console |
| Randomised loop, first attempt | **FAIL** in run 1 (seed 1167938841): an older Workspace test counted calls for schema names; completion now also reads them while typing. Changed to distinct sessions | `docs/evidence/admin-phase-10/failed-loop-20261021-004942.json` |
| Randomised loop, 5 consecutive runs (233 frontend, 85 backend unit, 184 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-10/loop-20261021-011009.json` |
| End-to-end, 5 repetitions (21 journeys each) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-10/e2e-20261021-011929.json` |

## 6. Security and audit implications

- No new information: the names are those the role can already read in the catalog (fewer, as unusable objects are left out). Each schema read is a `metadata_browse` record.

## 7. Known limitations

- Aliases in subqueries, CTE names and columns of functions' results aren't offered.

## 9. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. Decision A15 is open to the stakeholder's review.
