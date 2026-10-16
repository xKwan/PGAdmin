# Phase 8 Status: SQL Editor and Results UI

> Updated 2026-10-16. Evidence: `docs/evidence/phase-8/`. Workstation only: Edge 155, PostgreSQL 18.6, NTLM loopback.
> Execution is still **off by default** (`execution.enabled=false`). Turning it on in production needs gate G4 and
> the stakeholder's re-confirmation of T31 (Phase 7, finding P7-5).

## Delivered

| Item | Where |
|---|---|
| SQL editor: CodeMirror 6, PostgreSQL dialect highlighting, bracket matching and closing, search (Ctrl+F), comment toggle (Ctrl+/), keyword completion, undo history. The editor is its own lazily loaded chunk, and its injected styles carry the page's CSP nonce | `features/editor/SqlEditor.jsx` |
| Ctrl+Enter (or Run) runs the selection, or the statement at the cursor (found with the editor's grammar: a convenience only; the server's gate decides) | `features/editor/statementAt.js` |
| Running state: elapsed timer, Cancel, lock-wait indicator (polls `GET /api/db-sessions/{id}/activity` once a second) | `features/results/useExecution.js`, `ResultView.jsx`, `ExecutionEngine.activity` |
| Result grid: virtualised rows, column names with PostgreSQL types, SQL NULL marked, distinct from `''` and `'NULL'`, truncated results and cut cells marked, local sort labelled "Sorted locally (… not ORDER BY)", pages of 200 rows | `features/results/ResultGrid.jsx` |
| Command view (affected rows or "Completed."), PostgreSQL errors with SQLSTATE, detail, hint and "Show position in the editor", refusals explained (multiple statements, syntax error with position, audit unavailable, …), timeout and cancel views with `cancelOutcome`, "Outcome unknown" for indeterminate, transaction-state badge, "Session closed" when the server quarantined it | `ResultView.jsx`, `api/execution.js` |
| "Retrieved at", duration, statement class; Refresh runs the same text again | `ResultView.jsx` |
| Workspace wiring: Run disabled with a notice when execution is switched off; an ended session asks to connect again | `app/Workspace.jsx` |
| At the per-user session limit, the connect panel lists your open sessions (database, since when) with Disconnect buttons | `features/connections/ConnectPanel.jsx` |
| Error envelope extra fields reach the UI (`ApiError.details`, for example `position`) | `api/client.js` |
| Bundle budgets checked on every build (gzip): editor chunk ≤ 400 KB, entry chunk ≤ 250 KB; sizes written to `dist-report.json` | `scripts/check-dist.mjs` |
| **End-to-end suite started** (planned for Phase 11): Playwright with the installed Edge against a real environment: a throwaway cluster that authenticates the Windows account with SSPI, and the packaged JAR. The CI `e2e` job runs it | `e2e/`, `scripts/test/Invoke-E2E.ps1` |

## Acceptance criteria (plan Phase 8)

| Criterion | Result | Test |
|---|---|---|
| RTL for every §3.3 category: rows, empty rows, command, error, timeout, cancelled, indeterminate, refusals | **Tested** | `results.test.jsx` |
| Cancel flow and timeout display | **Tested** in RTL, and against the real server in Edge (statement stopped, session still usable) | `results.test.jsx`, `e2e/editor.spec.js` |
| Sorting (numeric types numerically, NULLs last, labelled local) and pagination | **Tested** | `results.test.jsx`, E2E (1,000-row result, pages) |
| NULL vs empty string vs `'NULL'` | **Tested** in RTL and E2E | |
| XSS payload in a cell renders as text | **Tested**: RTL (img, script, svg payloads; column names) and Edge (no dialog, no script) | |
| Refresh re-runs the statement | **Tested** | `results.test.jsx`, `Workspace.test.jsx` |
| Lock-wait indicator | **Tested**: API against real PostgreSQL (`waitEventType` `Lock` while blocked) and RTL | `ExecutionIT.aLockWaitIsVisible…`, `results.test.jsx` |
| CodeMirror under the strict CSP | **Tested in Edge**: no CSP violation and no page error during the whole workflow | `e2e/editor.spec.js` |
| Editor bundle ≤ 400 KB gzip | **Met**: editor chunk 126.5 KB gzip, entry chunk 91.4 KB gzip | build output, `check-dist` |
| Coverage per §8.2 (`features/editor`, `features/results` ≥ 85/80) | **Met** | loop reports |
| Gates green | See test evidence | |

## Findings

| # | Finding | Impact |
|---|---|---|
| P8-1 | **First real-browser run of the Phase 6 connection path.** Edge answers the per-request `401 Negotiate` on the `fetch` POST that opens a session, resends the body, and the session runs as the user's own role through SSPI. | The production flow (ADR-0002) works in the browser, not only in the Java handshake client (NTLM loopback; Kerberos is a target item) |
| P8-4 | The first E2E repetition passed and the next four failed to connect. The tests never disconnected, so the per-user cap of 3 DB sessions filled up, and the server correctly refused. This also showed a real gap: after a page reload, a user could hit the limit with no way to see or close the old sessions except signing out. | The connect panel now lists the user's open sessions with Disconnect buttons when the limit is reached (RTL test). The E2E tests sign out after each test, which ends the DB sessions on the server. The failed run is kept as evidence (`e2e-20261016-090046.json`). |
| P8-5 | The loop found a test defect. Right after the audit database came back, the test expected the next statement to run, but the audit pool takes a moment to reconnect. Until it does, the server correctly refuses with 503 and runs nothing. | The test retries until accepted (each refusal is fail-closed) |
| P8-2 | TanStack Virtual measures its scroll element with `offsetWidth`/`offsetHeight`, which jsdom reports as 0, so no rows were rendered in unit tests | The test setup gives only `.result-scroll` a size; behaviour in Edge is covered by E2E |
| P8-3 | The React Compiler lint rules require refs passed as props to be named `…Ref`, and flag TanStack Virtual as not memoisable | Prop renamed to `controllerRef`; the virtualiser line carries a justified lint suppression |

## Test evidence

| What | Result | File |
|---|---|---|
| Randomised loop: frontend lint + 73 Vitest tests; backend 76 unit + 109 integration tests with coverage | **PASS 5/5**, master seed `1418836141` | `docs/evidence/phase-8/loop-20261016-094421.json` |
| Earlier loops | PASS 5/5 (`1779721907`) before the P8-4 change; FAIL (P8-5 test defect); PASS 5/5 (`810503036`) before the Sonar fixes | `loop-20261016-085617.json`, `loop-20261016-090825.json`, `loop-20261016-092059.json` |
| End-to-end in Edge 155, 5 repetitions with new seeds (3 tests each: full workflow, cancel, layout at 1366×768 and 1920×1080) | **PASS 5/5**, master seed `902568239`, about 5 s per repetition | `docs/evidence/phase-8/e2e-20261016-094610.json` |
| Earlier E2E runs | FAIL (P8-4: run 1 passed, then the session limit), PASS 5/5 (`495782255`) before the Sonar fixes | `e2e-20261016-090046.json`, `e2e-20261016-092249.json` |
| SonarQube "pgweb gate", both projects; analyses cover exactly the current sources | **OK**; backend coverage 92.3%, frontend 96.0%; 0 blocker/high issues (after fixing a command function that always returned the same value, `aria-hidden` spacer rows, and a duplicated literal) | `docs/evidence/phase-8/sonar-20261016-094629.json` |
