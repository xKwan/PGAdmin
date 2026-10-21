# Decisions for the owner

> 2026-10-21. Everything that is waiting for you, one line each. **Safe** = accepting changes nothing in production and can be reversed as described in `docs/admin-phase-4-status.md` §10. **Think** = needs your judgement or someone else's. Nothing here has been deployed, SQL execution in production is still off, and audit purges are still prohibited.

## Decisions taken on your behalf (A1–A19)

| # | What | Recommended answer, and what accepting it means | Mark |
|---|---|---|---|
| A1 | Management screens only write SQL into the editor; it runs through the normal gate and audit | Accept: no second execution path exists | Safe |
| A2 | Table data: browse anything readable; edit only tables with a primary key, one transaction per save | Accept: rows can't be changed by position | Safe |
| A3 | Create, rename and drop databases only as generated SQL, never the connected database | Accept: PostgreSQL's own privileges decide | Safe |
| A4 | Export only rows already on screen, recorded in the audit trail first, formulas neutralised | Accept: no bulk extract path | Safe |
| A5 | CSV import turns a file into INSERT statements for review (1 MB, 5,000 rows) | Accept: no server-side file handling | Safe |
| A6 | Backup and restore not built | Accept until you decide ADR-0010 | Safe |
| A7 | Cancel query and end session on others' sessions, under PostgreSQL's rule, confirmed and audited | Accept: inactive while execution is off | Safe |
| A8 | Roles only as generated SQL; no passwords, no superuser | Accept | Safe |
| A9 | (Superseded by A13) | Nothing to decide | Safe |
| A10 | The UI asks before every generated change, even where the server doesn't require it | Accept | Safe |
| A11 | Cancel and end session follow the execution switch | Accept: off in production | Safe |
| A12 | Activity shows SQL text only for your own role's sessions | Accept | Safe |
| A13 | Schema comparison only reads; drops are comments unless you tick the option | Accept | Safe |
| A14 | Audit viewer reads as the signed-in auditor; off by default | Accept: stays off until you switch it on | Safe |
| A15 | Completion offers only objects your role can use | Accept | Safe |
| A16 | Saved queries are files on the app server, per account; off by default | Accept: stays off until you switch it on | Safe |
| A17 | Theme kept in the browser | Accept | Safe |
| A18 | Only pgweb's own editor commands can be rebound | Accept | Safe |
| A19 | Two-person approval: off unless configured, in memory, bound to one text, session and run; never self-approval | Accept: stays off until you configure it. It relies on the same SQL parser as T31 to decide what needs approval | Safe |

## Backup and restore (ADR-0010, Proposed)

| Item | Recommended answer, and what accepting it means | Mark |
|---|---|---|
| The ADR itself | Leave it Proposed. Building it adds a process that runs as each user on the app server, so ask your infrastructure owner first | **Think** |
| Q1 Is user-initiated backup wanted at all? | Not now: rely on the DBAs' server backups (option C). Nothing gets built | **Think** |
| Q2 Download only, or also a server share? | Download only: no dumps stored on the server | Safe |
| Q3 Retention for stored dumps | 14 days (applies only if a share is ever added) | Safe |
| Q4 Restore only into a new or empty database, with two-person approval? | Yes, if it is built at all; otherwise restores stay with the DBAs | **Think** |
| Q5 Who approves restores? | The DBA team's AD group | **Think** |

## Other open items

| Item | Recommended answer, and what accepting it means | Mark |
|---|---|---|
| T31 native SQL parser runs inside pgweb's process | Re-confirm the accepted risk while production execution is off, and look at it again before G4. Two native faults were seen before parser calls moved to one thread; their cause is still unknown. The alternative is to rebuild the parser out of process | **Think** |
| G4 / MVP release sign-off | Not until you or a delegate have tried the build on a test server with real AD sign-in. Signing off allows SQL execution to be switched on in production | **Think** |
| P12-3 audit purges | Keep prohibited (runbooks §11 archives, never deletes) | Safe |
| Production: `execution.enabled` | Keep `false` until G4 is signed off | Safe |
| Production: `auditViewer.enabled` | Keep `false`. To switch it on you need auditor logins on the audit database and Kerberos delegation to its host | Safe |
| Production: `savedQueries.enabled` | Keep `false`. Switching it on stores users' SQL text on the app server | Safe |
| Production: `approvalReasons` / `auth.approverSid` | Leave unset. To use approval you need an approver AD group; also list `ddl` and `procedural` | Safe |

Reply 'accept all recommendations' to approve everything marked safe.
