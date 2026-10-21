# Admin Phase 12 Status: Dark Mode

> Updated 2026-10-21. Twelfth delivery: the fourth deferred feature the stakeholder asked for on 2026-10-20 ("dark mode"). Built under decision A17 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-12/`. Workstation only.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Colour themes | **Settings** (top bar): Same as Windows (default), Light, Dark; applied at once and before the first paint on the next visit | `features/settings/SettingsDialog.jsx`, `preferences.js`, `usePreferences.js`, `main.jsx`, `app/App.jsx` |
| Whole page | Every colour is a token on `:root`; a dark set applies with `data-theme="dark"`, or with the system's dark setting when nothing is chosen; `color-scheme` so form controls follow | `styles.css` |
| Editor | Syntax colours and the editor's own surfaces (gutter, selection, active line, completion list, search panel) use the same tokens | `features/editor/editorTheme.js` |

## 2. Design

- **Kept in this browser** (A17): one local-storage key, `pgweb.preferences.v1`. Reads and writes tolerate blocked or broken storage; the dialog says when a choice can't be kept. Nothing is sent to the server.
- **No hard-coded colours remain** in `styles.css` outside the token definitions (checked when the change was made).
- The editor's highlight style replaces CodeMirror's default light one; its colours are CSS variables, so switching theme needs no editor rebuild.

## 3. Files changed

| File | Change |
|---|---|
| `frontend/src/styles.css` | Tokens, the dark set, colours replaced by tokens |
| `frontend/src/features/settings/*` (new), `features/editor/editorTheme.js` (new), `SqlEditor.jsx`, `main.jsx`, `app/App.jsx` | Settings, preferences, editor theme |
| Tests | `settings.test.jsx` (5), E2E journey "admin Phase 12" |
| Docs | This file; `user-guide.md` §2a; `requirements.md` FN-28 and §5; `admin-phase-4-status.md` §10 (A17); README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Dark mode for the page and the editor | **Met** | E2E (the page's background token and the editor's computed background change) |
| Kept after reloading | **Met** | E2E |
| Follows the system when not chosen | **Met** | `settings.test.jsx` (no attribute), E2E |
| Works without storage | **Met** | `settings.test.jsx` |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Frontend, all tests, first run | **FAIL**: the editor theme used a highlight tag that doesn't exist (`tags.quoted`), so the editor didn't start in tests; removed | console |
| Frontend, all tests (240), lint | **PASS** | console |
| E2E trial, 1 repetition (23 journeys) | **PASS** | console |
| Randomised loop, 5 consecutive runs (240 frontend, 85 backend unit, 187 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-12/loop-20261021-022352.json` |
| End-to-end, 5 repetitions (23 journeys each) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-12/e2e-20261021-023330.json` |

## 6. Security and audit implications

None: presentation only, nothing sent to the server.

## 7. Known limitations

- Colour contrast was chosen for readability but not measured with a contrast tool in the browser (jsdom doesn't compute colours).

## 8. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. Decision A17 is open to the stakeholder's review.
