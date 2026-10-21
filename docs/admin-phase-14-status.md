# Admin Phase 14 Status: Two-Person Approval for High-Risk Statements

> Updated 2026-10-21. Fourteenth delivery: the last deferred feature the stakeholder asked for on 2026-10-20 ("two-person approval for high-risk statements"). Built under decision A19 (`docs/admin-phase-4-status.md` §10). Evidence: `docs/evidence/admin-phase-14/`. Workstation only; every test ran against throwaway clusters. **Nothing is configured in the production template**: the feature is inert until a profile lists reasons.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Which statements need a second person | `profile.<name>.approvalReasons`: any of `drop`, `truncate`, `ddl`, `privileges`, `procedural`, `no_where` (the confirmation step's reasons); needs `auth.approverSid` and `confirmDestructive=true` | `ConfigLoader` |
| Enforcement | Run and Run script refuse such text with `APPROVAL_REQUIRED` unless an approval of exactly that text exists for this user and session; it is used up by the run | `ExecutionEngine.approvalFor` |
| Request | **Request approval** after the refusal (`POST /api/db-sessions/{id}/approval`); **Check again**, **Withdraw the request**, **Run it now** | `features/approvals/ApprovalPanels.jsx` (`ApprovalNeeded`), `useExecution.js` |
| Decide | **Approvals** start-page card for members of the approver group: open requests of others with the full text; **Approve** / **Reject** with a comment, after a confirmation; your own requests | `ApprovalsPage`, `GET /api/approvals`, `POST /api/approvals/{id}/decision`, `DELETE /api/approvals/{id}` |

## 2. Design

- **The server decides and enforces.** What needs approval is computed from the gate's parse (the same reasons as the confirmation step) and the profile's list, on every run. The UI only offers to ask.
- **Bound tightly** (A19): an approval covers one text (its HMAC), one requester (SID), their app session and database session, and one run, within 15 minutes of the decision. Changing a character, another session, or running it as a script instead of a statement needs a new approval. Requests expire after 30 minutes; at most 5 open per account and 500 in all.
- **A second person**: only members of the approver group decide, and never on their own requests (`OWN_REQUEST`), even if they are approvers. Others don't see open requests at all.
- **The approver reads exactly what will run**: the request keeps the text, in memory only, and the HMAC binds it to the run. Requests end with a restart (conservative: no SQL text at rest).
- **Audited first, without the text**: `approval_requested`, `approval_granted`, `approval_rejected`, `approval_withdrawn` (fail-closed); a refused run is `exec_rejected` with `approval_required`; an approved run's `exec_intent` carries `approvalId`.
- **Still the requester's own role**: approval adds a control; it never grants anything. The run goes through the gate, the confirmation, the kill switch and PostgreSQL's privileges as before.

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/execution/Approvals.java`, `ApprovalsServlet.java` (new) | Requests, decisions, binding, expiry |
| `backend/…/execution/ExecutionEngine.java`, `dbsession/StatementRunner.java`, `DbSessionServlets.java` | Check on Run and Run script; `POST …/approval` |
| `backend/…/config/Config.java`, `ConfigLoader.java`, `web/MeServlet.java`, `Application.java` | `auth.approverSid`, `profile.*.approvalReasons`; `capabilities.approver`; routes |
| `frontend/src/api/approvals.js`, `features/approvals/ApprovalPanels.jsx` (new); `features/results/useExecution.js`, `app/Workspace.jsx`, `app/App.jsx`, `features/landing/LandingPage.jsx`, `styles.css` | Panel after a refusal; Approvals page and card |
| `scripts/test/Invoke-E2E.ps1` | TRUNCATE on the internal profile needs approval; the approver group is BUILTIN\Users |
| Tests | `ApprovalIT` (2), `ConfigLoaderTest` (+1), `FilterUnitTest` and `DbSessionEdgeIT` (new record fields), `approvals.test.jsx` (4), E2E journey "admin Phase 14" |
| Docs | This file; `user-guide.md` §4 "Two-person approval"; `audit-policy.md` §2; `threat-model.md` T44; `requirements.md` FN-30 and §5; `dev-setup.md`; `admin-phase-4-status.md` §10 (A19); README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Listed statements don't run without a second person's approval | **Met** | `ApprovalIT`, E2E |
| The requester can't approve their own request | **Met** | `ApprovalIT` (an approver-group member asking), E2E (the request isn't offered to its author) |
| Approval only for that text, session and one run | **Met** | `ApprovalIT` (other text, other session, script form, second run all refused) |
| Rejected, withdrawn and limited requests | **Met** | `ApprovalIT` |
| Audited without the text | **Met** | `ApprovalIT` |
| Off unless configured | **Met** | `ConfigLoaderTest`; no reasons in the template |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted, first runs | **FAIL**, tests only: two older tests build the configuration records directly and needed the new fields; `ApprovalIT`'s second test left five open requests and audit counts that the first test then saw in some orders. Fixed (the second test withdraws its requests; audit checks look for their own approval ID) and run under three seeds | console |
| Frontend, first run | **FAIL**, a real defect: typing a comment in the approve dialog lost characters, because the dialog moved focus back to Cancel whenever its cancel handler changed. The page now passes a stable handler | console |
| Backend targeted and frontend (248), lint, after the fixes | **PASS** | console |
| E2E trial, 1 repetition (25 journeys) | **PASS** | console |
| Randomised loop, 5 consecutive runs (248 frontend, 86 backend unit, 189 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-14/loop-20261021-034405.json` |
| End-to-end, 5 repetitions (25 journeys each) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-14/e2e-20261021-035412.json` |

## 6. Security and audit implications

- **Bypassing the second person (T44):** the server decides what needs approval on every run, from the gate's parse and the profile's list; the browser can only ask. An approval is bound to the requester's SID, app session and database session, the text's HMAC, the run's form (statement or script) and the session's `standard_conforming_strings`; it covers only the reasons the approver was shown, is used once, and expires 15 minutes after the decision. The requester can never decide (`OWN_REQUEST`), even as an approver; only members of `auth.approverSid` can.
- **Audit before action:** `approval_requested`, `approval_granted`, `approval_rejected` and `approval_withdrawn` are written first (fail-closed), without the text; a refused run is `exec_rejected` with `approval_required`; an approved run's `exec_intent` carries the approval ID.
- **No new privilege:** the statement still runs as the requester's own role, through the gate, the confirmation and the kill switch.
- **Text in memory only:** approvers read the full text; it is never written to disk and ends with a restart.
- **Residual:** approval covers statement kinds at the top level. A role that may create functions or run DO blocks could wrap a listed statement in one; list `ddl` and `procedural` as well where that matters. Who is in the approver group is the organisation's control.

## 7. Known limitations

- The E2E has one Windows account, so it covers asking, waiting, refusal of self-approval and withdrawal; approval by another person is covered by `ApprovalIT` with two accounts.
- Requests are lost when pgweb restarts (by design).
- No notification: approvers look at the Approvals page.

## 8. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. Decision A19 is open to the stakeholder's review.
