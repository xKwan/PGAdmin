# Admin Phase 9 Status: Audit Viewer

> Updated 2026-10-21. Ninth delivery: the first of the deferred features the stakeholder asked for on 2026-10-20 ("audit viewer screen (read-only)"). Built under decision A14 (`docs/admin-phase-4-status.md` §10); ADR-0005 has an addendum, its status unchanged. Evidence: `docs/evidence/admin-phase-9/`. Workstation only; every test ran against throwaway clusters.

## 1. Scope

| Item | Delivered | Where |
|---|---|---|
| Audit viewer, read-only | **Audit trail** on the start page (when switched on): filters, newest-first list, details, older pages | `features/audit/AuditPage.jsx` |
| Server side | `POST /api/audit/search` with the filters as JSON | `auditviewer/AuditViewer.java`, `AuditViewerServlet.java` |
| Configuration | `auditViewer.enabled` (default `false`), `host`, `port`, `database`, `sslMode`, `sslRootCert` | `ConfigLoader`, production template, `dev-setup.md` |

## 2. Design

- **pgweb can't read the audit trail; auditors can.** Each search opens a short-lived connection to the audit database **as the signed-in user** (the request's own Windows handshake, as when opening a session; in tests a password connector). PostgreSQL grants SELECT only to members of `pgweb_audit_reader`, as ADR-0005 already decided; the runtime role `pgweb_app` stays INSERT-only. A role that isn't an auditor gets `AUDIT_READ_DENIED`; one without a login there gets `CONNECT_FAILED`. The usual connection rules apply: failed-open limit, mapped role verified, superusers refused (`DbSessionManager.oneOff`).
- **Every search is recorded first** as `audit_view` (period, filters, page), fail-closed, whether or not the role may read the trail.
- **Bounded and constant.** One constant query; every filter is a bound value (the user filter is a literal `ILIKE` match with its wildcards escaped, or an exact SID). Period required, at most 31 days; at most 200 rows per page (100 by default); paging below the last sequence number; a read-only transaction with a 15-second statement timeout, rolled back.
- **POST**, so the filters, which may name a person, stay out of URLs and access logs; the request carries its own Negotiate handshake for the connection.
- **Read only.** No export, no edits, no purge (purges stay prohibited, P12-3). Statements are shown as stored: literals removed.
- **Off by default.** The audit database must first accept auditors' Kerberos logins (a login role per auditor in `pgweb_audit_reader`, a `gss`/`sspi` line in its `pg_hba.conf`, SPN and constrained delegation to `POSTGRES/<audit host>`), which is the infrastructure owner's step.

## 3. Files changed

| File | Change |
|---|---|
| `backend/…/auditviewer/AuditViewer.java`, `AuditViewerServlet.java` (new) | Search, validation, `audit_view` |
| `backend/…/dbsession/DbSessionManager.java` | `oneOff`: a short-lived user connection with the session rules |
| `backend/…/config/Config.java`, `ConfigLoader.java`; `deploy/windows/pgweb.properties.template` | `auditViewer.*` (off in the template) |
| `backend/…/Application.java`, `web/MeServlet.java` | Route under the fresh-Negotiate filter; `capabilities.auditViewer` |
| `frontend/src/api/audit.js`, `features/audit/AuditPage.jsx` (new); `app/App.jsx`, `features/landing/LandingPage.jsx`, `styles.css` | Start-page card and page |
| `scripts/test/Invoke-E2E.ps1` | The test account is a `pgweb_audit_reader` member on the throwaway cluster; the viewer is switched on |
| Tests | `AuditViewerIT` (6), `ConfigLoaderTest` (+1), `audit.test.jsx` (4), E2E journey "admin Phase 9"; `AuthorizationMatrixIT` covers the new route automatically |
| Docs | This file; `user-guide.md` §7f; `audit-policy.md` §2 and §6; ADR-0005 addendum; `threat-model.md` T42; `requirements.md` FN-25 and §5; `dev-setup.md`; README |

## 4. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Read-only | **Met** | No write path; read-only transaction |
| Only auditors read the trail | **Met** | `AuditViewerIT`: auditor reads; non-auditor 403; no login 422 |
| Searches recorded | **Met** | `AuditViewerIT` (the search is in its own result), E2E |
| Filters are values, bounded | **Met** | `AuditViewerIT` (SQL-looking and wildcard text, limits, range, validation) |
| Off unless configured | **Met** | `AuditViewerIT` (404, capability false), `ConfigLoaderTest`, template test |

## 5. Test evidence

| What | Result | Evidence |
|---|---|---|
| Backend targeted: `AuditViewerIT`, `AuthorizationMatrixIT`, `ArchitectureTest`, `ConfigLoaderTest`, `ConfigTemplateTest` | **PASS** | console |
| Frontend targeted: audit, App and landing tests, lint | **PASS** (after naming the Details button explicitly) | console |
| E2E trial, 1 repetition (20 journeys) | **PASS** | console |
| Randomised loop, first attempt | **FAIL** in run 1 (master seed 871552666): JaCoCo branch coverage of `pgweb.auditviewer` 0.77, below the 0.85 minimum. Fixed with tests (statement events with rows and duration, error mapping) and by removing two unreachable branches in the servlet; the failed run's report was not kept | console |
| Randomised loop, 5 consecutive runs (227 frontend, 85 backend unit, 183 integration tests per run) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-9/loop-20261021-002820.json` |
| End-to-end, 5 repetitions (20 journeys each; the new one opens a session, searches the trail for its connection, opens the details, and finds its own search recorded) | **PASS**, 5 of 5 | `docs/evidence/admin-phase-9/e2e-20261021-003743.json` |

## 6. Security and audit implications

- T42: the trail exposed to a non-auditor, or readable by pgweb's own account. Bounded as above.
- New audit event `audit_view`.

## 7. Known limitations

- In the E2E the auditor reads the same throwaway cluster as the data, through SSPI on loopback; Kerberos delegation to a separate audit host is target-environment work (as for K3).
- No export of search results (deliberately).

## 9. Ready for review?

**Yes.** Loop 5/5 and E2E 5/5. Decision A14 is open to the stakeholder's review.
