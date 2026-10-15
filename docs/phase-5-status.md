# Phase 5 Status: Frontend Shell, Landing Page, Common Components

> Updated 2026-10-16. Evidence: `docs/evidence/phase-5/`.

## Delivered

| Item | Where |
|---|---|
| API client: same-origin `/api/` only; CSRF token kept in memory (never in browser storage); JSON bodies; the error envelope becomes `ApiError` with a kind (`unauthenticated`, `forbidden`, `audit-unavailable`, `network`, `error`); aborts pass through | `frontend/src/api/client.js` |
| **401 handling:** reload once to let the browser sign in again; if the reloaded page also gets 401 (for example `KERBEROS_REQUIRED`), show "Sign-in did not work" instead of looping | `App.jsx` (`makeUnauthenticatedHandler`) |
| Session hook (`GET /api/me` with TanStack Query), sign-out | `frontend/src/api/session.js` |
| Landing page: **Internal PostgreSQL** card only when `capabilities.internal`; **External PostgreSQL** card; "SQL switched off" notice while execution is disabled | `features/landing/LandingPage.jsx` |
| Workspace shell: object explorer, SQL editor and results panes with resizable splitters (mouse and keyboard, ARIA separator); filled by Phases 6–10 | `app/Workspace.jsx`, `components/common/Splitter.jsx` |
| Common components: StatusBadge, Spinner, EmptyState, ErrorPanel (code + request ID, no stack trace), ConfirmDialog (focus, Escape), ToastList | `components/common/index.jsx` |
| Full-page states: sign-in failed (Kerberos-specific advice), insufficient privileges (domain-specific advice), audit unavailable ("nothing was run") / service unavailable | `app/StatusPages.jsx` |
| Styles: system fonts only; nothing loaded from other origins | `styles.css` |
| **Backend:** the SPA is served from the JAR. `index.html` gets a fresh CSP nonce (`<meta name="csp-nonce">` plus the matching nonce-based CSP, no `unsafe-inline`), never cached. Hashed assets are immutable. Client-side views fall back to `index.html`. Unknown `/api/` paths keep the JSON 404. Traversal and odd paths are refused. Sign-in is required. | `backend/.../web/StaticContentServlet.java` |
| MSW handlers and seeded test helpers; axe-core accessibility checks | `src/mocks/server.js`, `src/test/` |

## Acceptance criteria (plan Phase 5)

| Criterion | Result | Test |
|---|---|---|
| All component states tested | **Tested** | `common.test.jsx`, `StatusPages.test.jsx` |
| Landing page for DataAdmin and non-DataAdmin | **Tested** | `App.test.jsx` |
| Client behaviour on 401, 403 and 503 (audit unavailable) | **Tested:** one reload then the "sign-in failed" page, the insufficient-privileges page, the audit-unavailable page with retry | `App.test.jsx`, `client.test.js` |
| Accessibility checks | **Tested:** axe-core finds no violations on the landing page, workspace, dialog and status pages. Colour contrast is checked in the real browser in Phase 11. | `a11y.js` uses |
| Lint ban on `dangerouslySetInnerHTML` | **Enforced** since Phase 2 (gate G3); hostile text renders as text | `common.test.jsx` |
| Coverage per §8.2 | **Met:** statements 96%, branches 91%, lines 99% | loop reports |
| No external URLs in the build output | **Tested:** `check-dist` passes in every build | `npm run build` |
| No horizontal overflow at 1366×768 and 1920×1080 | **Designed** (fluid grid, ellipsis on long names); jsdom does no layout, so the real check is the Playwright test in Phase 11 | P11 |
| The internal card is cosmetic only | **Tested:** the API refuses non-DataAdmin access regardless (Phase 4 matrix) | `AuthorizationMatrixIT` |

## Test evidence

| Check | Result | File |
|---|---|---|
| Randomised loop: frontend lint + 29 Vitest tests with coverage; backend 51 unit + 64 integration tests | **PASS 5/5**, master seed `959626999` | `docs/evidence/phase-5/loop-20261016-020423.json` |
| SonarQube "pgweb gate" | **OK** for both projects (after removing three duplicated literals in the new servlet). **Correction (Phase 6, finding P6-4):** the frontend half of this export was stale. No frontend analysis of this commit reached the server; the gate shown was that of Phase 3. Analysing this code in Phase 6 found two false positives in `Splitter.jsx` (suppressed inline, with the reason) and one real issue in `client.js` (fixed). The backend half is valid. | `docs/evidence/phase-5/sonar-20261016-020424.json` |
