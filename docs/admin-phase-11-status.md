# Admin Phase 11 Status: Saved Queries on the Server

> Updated 2026-10-21. Eleventh delivery: the third deferred feature the stakeholder asked for on 2026-10-20 ("saved queries stored on the server"). Built under decision A16 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-11/`. Workstation only; every test ran against throwaway clusters.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Save a tab's text on the server | **Save to server…** in the editor toolbar: a name, then saved for your Windows account; a tab opened from a saved query replaces it unless **Save as a new query** is ticked | `features/saved/SavedPanel.jsx` (`SaveDialog`), `app/Workspace.jsx` |
| List, open, delete | **Saved** tab next to History; Open puts the text in a new editor tab (nothing runs); Delete asks first | `SavedPanel` |
| Server | `GET/POST /api/saved-queries`, `GET/PUT/DELETE /api/saved-queries/{id}` | `savedqueries/SavedQueries.java`, `SavedQueriesServlet.java` |
| Configuration | `savedQueries.enabled` (default `false`; `false` in the production template) | `ConfigLoader`, template, `dev-setup.md` |

## 2. Design

- **Your own only.** Queries belong to the Windows account (SID). There is no sharing; another account's IDs are "not found".
- **Files on the app server**, one JSON file per account under `server.workDir\saved-queries`, named by a SHA-256 of the SID (no account names on disk), written to a temporary file and moved into place, with the work folder's ACL (service account only). No database, schema or migration is needed.
- **Bounded**: 200 queries per account, 100,000 characters per query, names of 1 to 100 characters on one line, unique per account (case-insensitive); requests up to 1 MiB.
- **Audited first, without the text**: `saved_query_saved` (ID, whether new, name and text lengths, the statement HMAC) and `saved_query_deleted`, fail-closed.
- **Nothing runs**: saving and opening only move text between the editor and the server.
- **Off by default** (A16): the text may contain literal values and is at rest outside PostgreSQL (T43), so the operator decides.

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/savedqueries/SavedQueries.java`, `SavedQueriesServlet.java` (new) | Store and endpoints |
| `backend/…/config/Config.java`, `ConfigLoader.java`, `web/MeServlet.java`, `Application.java`; `deploy/windows/pgweb.properties.template` | Setting, capability, route |
| `frontend/src/api/savedQueries.js`, `features/saved/SavedPanel.jsx` (new); `app/Workspace.jsx`, `app/App.jsx`, `features/editor/useEditorTabs.js` | Toolbar button, dialog, Saved tab, tabs linked to a saved query |
| `scripts/test/Invoke-E2E.ps1` | Saved queries switched on for the E2E instance |
| Tests | `SavedQueriesIT` (3), `saved.test.jsx` (2), E2E journey "admin Phase 11" |
| Docs | This file; `user-guide.md` §4 "Saved queries"; `audit-policy.md` §2; `threat-model.md` T43; `requirements.md` FN-27 and §5; `dev-setup.md`; `admin-phase-4-status.md` §10 (A16); README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Stored on the server, per account | **Met** | `SavedQueriesIT` (one hashed file per account), E2E |
| Another account can't read, change or delete | **Met** | `SavedQueriesIT` |
| Bounded and validated | **Met** | `SavedQueriesIT` (names, sizes, IDs, 200 limit, no temporary files left) |
| Audited without the text | **Met** | `SavedQueriesIT` |
| Nothing runs | **Met** | `saved.test.jsx`, E2E |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted, first run | **FAIL**, two findings: a 100,001-character text was refused by the general 64 KiB body limit (413) before validation, so the servlet now allows 1 MiB; an encoded `../` in the ID is refused by the server itself (400), which the test now accepts | console |
| Backend targeted after the fixes: `SavedQueriesIT`, `AuthorizationMatrixIT`, `ArchitectureTest`, `ConfigLoaderTest`, `ConfigTemplateTest` | **PASS** | console |
| Frontend targeted, lint | **PASS** (after mounting the save dialog only while open, instead of resetting its state in an effect) | console |
| E2E trial, 1 repetition (22 journeys) | **PASS** | console |
| Randomised loop, first attempt | **FAIL** in run 1 (seed 21150865), test only: `SavedQueriesIT` expected exactly one file in the folder, but in that random order another test's account had saved first. Now it checks for this account's own hashed file | `docs/evidence/admin-phase-11/failed-loop-20261021-013025.json` |
| Randomised loop, 5 consecutive runs (235 frontend, 85 backend unit, 187 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-11/loop-20261021-015027.json` |
| End-to-end, 5 repetitions (22 journeys each) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-11/e2e-20261021-015956.json` |

## 6. Security and audit implications

- **SQL text at rest (T43):** the only feature that stores SQL text on the app server. Files are under `<workDir>/saved-queries/`, which inherits the work folder's ACL (service account only). Off by default (`savedQueries.enabled=false`, also in the production template).
- **Isolation between accounts:** each account's file is named by the SHA-256 of its SID; there is no way to name another account's file, and another account's query ID is "not found".
- **No path traversal:** file names come only from the hash; query IDs must be UUIDs and are never used in a path; an encoded `../` is refused before it reaches pgweb.
- **Audit before action:** saving and deleting are recorded first (`saved_query_saved`, `saved_query_deleted`; fail-closed) with the ID, lengths and the statement HMAC, never the text. Saving runs nothing: a saved query runs only when opened and run like any other text.

## 7. Known limitations

- No sharing between users, no folders or search.
- Saved queries live on one app server: they are part of the server's `work` folder for backup and are not migrated anywhere else.

## 8. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. Decision A16 is open to the stakeholder's review.
