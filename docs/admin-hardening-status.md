# Admin Hardening Pass (after Phases 2–14)

> 2026-10-21. A security review of everything built in admin Phases 2–14, without new features, followed by the full gates on the result. Evidence: `docs/evidence/admin-hardening/`. Workstation and throwaway clusters only; nothing deployed.

## 1. Review scope and result

| Area | What was checked | Result |
|---|---|---|
| Privilege checks | Every new route's access level (`Application`); session ownership (`sessions.use`); approver group and own-request rule; audit viewer reading as the user (`pgweb_audit_reader`); session signals (PostgreSQL's rule checked, then enforced by PostgreSQL); Activity hiding other roles' SQL in the query itself | No defect found |
| Audit before action | Approvals (request, decide, withdraw), saved queries (save, delete), audit viewer searches, session signals, exports, and every statement of a script or data save: each writes its record with `audit.require` (fail-closed) before acting | No defect found |
| SQL injection in generated SQL | Backend: constant queries with bound parameters only; the BEGIN isolation clause is a fixed switch. Frontend generators (`sqlGen.js`, `rowEdit.js`, `schemaDiff.js`): identifiers through `quoteIdent`, literals through `quoteLiteral` (E'' form for backslashes, NUL refused); names in comments have control characters replaced, and DROPs of names with line breaks aren't written; definitions come from PostgreSQL's `pg_get_*` functions with anchored schema stripping. Everything generated is shown before it runs and goes through the server's gate | No defect found. Type, default and view fields are SQL the user writes, by design (A1) |
| Path traversal in saved queries | File names come only from the SHA-256 of the SID; IDs must be UUIDs and never reach a path; encoded `../` is refused before pgweb | No defect found |
| Approval bypass | Server-side checks on Run and Run script; binding; consumption; limits | **Three fixes**, below; one residual risk documented |
| Browser | No `dangerouslySetInnerHTML`, `eval` or dynamic `href`; storage holds only preferences, the last database and the session history (T34) | No defect found |

## 2. Fixes

| # | Finding | Fix | Test |
|---|---|---|---|
| H1 | An approval was bound to the text but not to how it is read: with `standard_conforming_strings` changed after the decision, the same characters can mean something else (backslashes in strings) | The approval also records the setting at request time; a run with another setting needs a new approval | `ApprovalIT` (SET off → refused; SET on → runs) |
| H2 | An approval didn't record which reasons the approver saw | A run is accepted only if every reason it needs was in the approved request | Covered by the same binding; `ApprovalIT` |
| H3 | Finished requests stayed in memory for a day and counted towards the 500-request limit, so one account requesting and withdrawing repeatedly could block everyone's requests for a day | When the store is full, finished requests are forgotten (oldest first); only open requests count | `ApprovalIT.finishedRequestsDoNotFillTheStore` (501 requests, each withdrawn) |

**Residual (documented, not fixed):** approval looks at the statement kind at the top level. A role that may create functions or run DO blocks can wrap a listed statement in one, so administrators should also list `ddl` and `procedural`. What needs approval is decided from the same SQL parser as T31. Recorded in T44, A19 and the deployment guide.

## 3. Documentation

- Status docs for Phases 5–14: section numbering fixed (no gap at §8); Phase 13 gained "Known limitations"; the security sections of Phases 9, 11 and 14 written out in full.
- `user-guide.md` §9 (approval messages) and new §10 (features that depend on the server's settings); `deployment-guide.md` §9a (every optional feature, its switch, prerequisites and effect) and §11 (saved-queries folder); `runbooks.md` §7a (auditors, approvers, saved queries).
- `threat-model.md` T44, `admin-phase-4-status.md` A19 and ADR-0010 (two-person approval now exists for statements; still Proposed).
- `docs/decisions-for-owner.md`: everything waiting for the owner, on one page.

## 4. Test evidence

| What | Result | Evidence |
|---|---|---|
| `ApprovalIT` alone, after the fixes (3 tests) | **PASS** | console |
| Randomised loop, 5 consecutive runs (248 frontend, 86 backend unit, 190 integration tests per run) | **PASS**, 5 of 5, first attempt | `docs/evidence/admin-hardening/loop-20261021-093752.json` |
| End-to-end, 5 repetitions (25 journeys each) | **PASS**, 5 of 5, first attempt | `docs/evidence/admin-hardening/e2e-20261021-094816.json` |

No low-memory interruptions. No test clusters or worktrees left behind.

## 5. Ready for review?

**Yes.** Nothing new to decide beyond `docs/decisions-for-owner.md`.
