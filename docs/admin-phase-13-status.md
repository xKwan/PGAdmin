# Admin Phase 13 Status: Keyboard Customisation

> Updated 2026-10-21. Thirteenth delivery: the fifth deferred feature the stakeholder asked for on 2026-10-20 ("keyboard customisation"). Built under decision A18 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-13/`. Workstation only.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Rebind the editor's commands | Settings → **Keyboard shortcuts in the editor**: Run, Run script, Explain, Explain analyze, comment lines. **Change** (press the new keys; Escape cancels), **None**, **Restore the default keys** | `features/settings/shortcuts.js`, `SettingsDialog.jsx` |
| Applied at once | The open editors switch keys without reloading (a CodeMirror compartment); the toolbar hint names the current keys | `SqlEditor.jsx`, `EditorArea.jsx`, `app/Workspace.jsx` |
| New default | Run script on **Ctrl+Shift+Enter** (it had no key) | `shortcuts.js` |

## 2. Design

- Kept with the colour theme in the browser (A17, A18); invalid stored values fall back to the defaults.
- **Reserved keys** can't be taken: copy, paste, cut, undo, redo, select all, find, save, close/new tab and window, reload, print, address bar, Tab, Enter, Escape, Backspace, Delete, F5. A plain letter (with or without Shift) is typing, not a shortcut. One key can't serve two commands.
- Keyboard commands obey the same rule as the buttons: nothing runs while running isn't allowed (no session, execution off, a statement running, a failed transaction).
- The pgweb commands' keymap comes before CodeMirror's own, so a chosen key wins; CodeMirror's editing keys are not rebindable (A18).

## 3. Files changed

| File | Change |
|---|---|
| `frontend/src/features/settings/shortcuts.js` (new), `SettingsDialog.jsx` | Keys, validation, the settings section |
| `frontend/src/features/editor/SqlEditor.jsx`, `EditorArea.jsx`, `app/Workspace.jsx`, `app/App.jsx` | Keymap from preferences; commands routed to Run script and Explain |
| Tests | `shortcuts.test.jsx` (3), `editor.test.jsx` (+1), E2E journey "admin Phase 13" |
| Docs | This file; `user-guide.md` §2a; `requirements.md` FN-29 and §5; `admin-phase-4-status.md` §10 (A18); README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Keys can be changed, removed and restored | **Met** | `shortcuts.test.jsx`, E2E |
| Conflicts and reserved keys refused | **Met** | `shortcuts.test.jsx` |
| The editor uses the new keys at once | **Met** | `editor.test.jsx`, E2E (F9 runs) |
| Kept in the browser | **Met** | E2E (a new page of the same browser shows the chosen key) |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Frontend, all tests (244), lint | **PASS** | console |
| E2E trial, first attempt | **FAIL**, test only: the journey reloaded the page with an edited tab, so the browser asked about unsaved changes and the test's dialog handler cancelled the reload. Now a second page of the same browser checks that the key is kept | console |
| E2E trial after the fix (24 journeys) | **PASS** | console |
| Randomised loop, 5 consecutive runs (244 frontend, 85 backend unit, 187 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-13/loop-20261021-030241.json` |
| End-to-end, 5 repetitions (24 journeys each) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-13/e2e-20261021-031243.json` |

## 6. Security and audit implications

None: presentation only. Keys don't bypass anything: running still goes through the same checks as the buttons and the server.

## 9. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. Decision A18 is open to the stakeholder's review.
