# Phase 10 Status: Metadata Browser

> Updated 2026-10-16. Evidence: `docs/evidence/phase-10/`. Workstation only: PostgreSQL 18.6, Edge 155, NTLM loopback.

## Delivered

| Item | Where |
|---|---|
| `GET /api/metadata/{session}/schemas`, `/objects?schema=…`, `/relation?schema=…&name=…` (names only as query parameters; `all=true` for "show everything") | `pgweb.metadata.MetadataServlet` |
| Catalog reads on the session's **companion** connection, as the user's own role: schemas; tables, views, materialised views, foreign tables, sequences; functions, procedures, aggregates and window functions with signatures; a relation's columns (type, nullability, default, identity, generated), indexes and constraints | `pgweb.metadata.MetadataService` |
| Privilege filtering (`has_schema_privilege`, `has_table_privilege`/`has_any_column_privilege`, `has_sequence_privilege`, `has_function_privilege`); with "show everything", the rest is listed and marked `accessible: false`. System schemas only with "show everything"; TOAST and temporary schemas never | same |
| Every query is a constant with bound parameters; 10 s query timeout; names that can't exist (empty, NUL, over 63 bytes) are refused before anything is sent | same |
| One catalog read at a time per session; a busy companion gives `409 COMPANION_BUSY` after 3 s instead of queueing; no companion (degraded session) gives `409 METADATA_UNAVAILABLE`; the lock-wait poll skips while the browser holds the companion | `DbSession.companionLock`, `ExecutionEngine.activity` |
| Audit `metadata_browse` (object kind, schema) | `MetadataService` |
| ArchUnit: no plain `Statement` and no `createStatement` in `pgweb.metadata`; its SQL fields must be `static final` | `ArchitectureTest` |
| Strict coverage threshold (90/85) extended to `pgweb.metadata` | `backend/pom.xml` |
| Object explorer: lazy tree (schemas → kind groups → columns, indexes, constraints), "Loaded at" and Refresh, "Show everything in the catalog", "no access" markers, Insert puts a correctly quoted name at the editor's cursor, note that the tree shows committed objects only | `features/explorer/ObjectExplorer.jsx`, `api/metadata.js` (`quoteIdent`), `SqlEditor` `insert` |

## Acceptance criteria (plan Phase 10)

| Criterion | Result | Test |
|---|---|---|
| Objects without privileges are hidden, or marked with "show everything"; another role sees what its own privileges allow | **Tested** | `MetadataIT.schemasAreFiltered…`, `MetadataIT.objectsAreListed…` |
| Identifiers with quotes, spaces, Unicode and an injection payload (`a"; DROP TABLE shop.orders; --`) are listed and described verbatim; the named table still exists afterwards | **Tested** | `MetadataIT.hostileIdentifiers…` |
| Inserted names are quoted correctly (reserved words, case, spaces, embedded quotes); 200 random names round-trip | **Tested** | `explorer.test.jsx` |
| ArchUnit rule against string-built SQL | **Tested** (build fails otherwise) | `ArchitectureTest.theObjectBrowserUses…` |
| Busy companion connection → graceful | **Tested**: the catalog locked by a superuser, the second request gets `409 COMPANION_BUSY`, the first completes when the lock is released; degraded session → `METADATA_UNAVAILABLE` | `MetadataIT.aBusyOrMissingCompanion…` |
| Relation details: columns, defaults, generated columns, primary key, unique, check, foreign key, indexes | **Tested** | `MetadataIT.aRelationShows…` |
| Committed state only (uncommitted DDL in the main session isn't shown until commit) | **Tested** | `MetadataIT.theBrowserShowsCommittedStateOnly` |
| Foreign sessions → 404; audit `metadata_browse` | **Tested** | `MetadataIT.sessionsOfOthers…` |
| RTL tests for tree states (loading, error and retry, empty, expanded, no access, insert, refresh) | **Tested** | `explorer.test.jsx` |
| Browse and insert in a real browser, then run the statement | **Tested** in Edge | `e2e/editor.spec.js` |
| 0 Sonar injection findings; gates green | **OK**; 0 vulnerabilities, 0 hotspots to review (0 injection findings); backend coverage 92.2%, frontend 96.2%; 0 blocker/high issues (after closing a statement if setting its timeout fails, and a duplicated literal) | `docs/evidence/phase-10/sonar-20261016-121833.json` |

## Findings

| # | Finding | Impact |
|---|---|---|
| P10-1 | `Invoke-E2E.ps1` packaged whatever `frontend\dist` already held: the `with-spa` profile copies `dist` and doesn't build it. Earlier E2E runs were valid only because `dist` had been built by hand first. Phase 9's final E2E ran on a bundle built before the last Sonar fix (spacing in `TransactionBar`: `{' '}` between inputs and labels), so it differed from the commit by that cosmetic change only. | The runner now builds the SPA every time (unless `-SkipBuild`) |
| P10-2 | Chromium includes CSS `::before` content in a button's accessible name, so the tree's decorative arrow made the name "▸public" for screen readers. jsdom doesn't do this, so only the E2E test caught it. | The arrow is now in an `aria-hidden` element |
| P10-3 | A `serial` column creates a sequence the column's users may have no privilege on | Correctly listed as "no access" with "show everything" (test expectation fixed) |

## Test evidence

| What | Result | File |
|---|---|---|
| Randomised loop: frontend lint + 97 Vitest tests; backend 79 unit + 129 integration tests with coverage | **PASS 5/5**, master seed `154670499` | `docs/evidence/phase-10/loop-20261016-121539.json` |
| Earlier runs, before the Sonar fix | Loop 5/5 (`1450201442`), E2E 5/5 (`1839444753`) | `loop-20261016-114542.json`, `e2e-20261016-114730.json` |
| End-to-end in Edge, 5 repetitions (5 tests each, including browse-and-insert) | **PASS 5/5**, master seed `1703311792` | `docs/evidence/phase-10/e2e-20261016-121743.json` |
| SonarQube "pgweb gate", both projects; analyses cover exactly the current sources | **OK**; 0 vulnerabilities, 0 hotspots to review (0 injection findings); backend coverage 92.2%, frontend 96.2%; 0 blocker/high issues (after closing a statement if setting its timeout fails, and a duplicated literal) | `docs/evidence/phase-10/sonar-20261016-121833.json` |
