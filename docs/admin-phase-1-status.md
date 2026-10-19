# Admin Phase 1 Status: Connection Status and Database Switching

> Updated 2026-10-19. First phase of the stakeholder's plan "Comprehensive PostgreSQL Administration Workspace" (decision log, 2026-10-19). Evidence: `docs/evidence/admin-phase-1/`. Workstation only: Windows 11, no domain, Edge, PostgreSQL 18.6, NTLM loopback. Reviewed by the stakeholder on 2026-10-19 ("Check and continue"); admin Phase 2 followed (`docs/admin-phase-2-status.md`).

## 1. Inspection findings

**Why "Show my databases" worked while the workspace said "Not connected".** The workspace had one status, `Not connected`, which only meant *no database session is open* (`frontend/src/app/Workspace.jsx`, before this phase). Database discovery is a different backend call, `POST /api/profiles/{profile}/databases` (`DbSessionManager.databases`): it connects as the user to the maintenance database, reads `pg_database` filtered by `has_database_privilege(... 'CONNECT')`, records `metadata_browse` and closes the connection. Its success proved the server reachable for the user, but nothing in the UI recorded it. There was no notion of server reachability at all, so the two indicators described different things and looked contradictory.

**Authoritative sources of state** (unchanged by this phase):

| State | Source |
|---|---|
| Identity | Waffle's server-side context, `GET /api/me` (account, SID, DataAdmin, mechanism) |
| Server reachable for this user | A successful discovery call (an authenticated connection as the user), or an open session |
| Session open | `201` from `POST /api/db-sessions/{profile}` (after `session_user` and superuser checks), then heartbeats every 30 s; `404`/`410` mean it ended |
| Transaction state | pgJDBC's `TransactionState` on the main connection (`IDLE`, `OPEN`, `FAILED`), reported with every result and heartbeat |

**Requirements and decisions read** (actual text, not summaries): FN-02, FN-03 (`requirements.md` §3); D9 (MVP scope, approved 2026-10-15) and the deferred list (§5); EXE-01 (kill switch) and EXE-03 (scripts: **open**, "not to be implemented until decided"); ADR-0002 (fresh Negotiate handshake per open, no stored credentials), ADR-0004 (two connections per session, caps 3 per user and 50 global, superusers refused); the audit policy (connect events are best effort and spooled during an outage; execution is fail-closed); gates G4 and G5.

Phase 1 fits FN-02/FN-03 and ADR-0004: no approved decision changes.

## 2. Plan (as carried out)

| Area | Change | Security boundary touched |
|---|---|---|
| Backend `DbSessionManager.replace`, `DbSessionServlets.OpenRequest` | Switch = the existing open request plus `replaces` and `discardTransaction`: new session first, old one closed only on success; refuses `TRANSACTION_OPEN` (unless confirmed), `SESSION_BUSY`, `INVALID_REPLACE`; caps unchanged | Same route, route access (internal = DataAdmin), fresh handshake, ownership check (others' IDs are 404), audit |
| Frontend `useServerDiscovery`, `ConnectionStatus` | Discovery when the workspace opens; identity, server, database and session shown separately | None (reads backend answers only) |
| Frontend `ConnectPanel`, `SessionBar`, `LostSession`, `Workspace` | List on open; "Connect to <last used>"; Switch database; one-step reconnect; editor text kept across switch and reconnect | None. Confirmations are a convenience; the server enforces the transaction rule |
| Frontend `lastDatabase.js` | The last database name per profile and user SID in `localStorage` | Name only: no credentials, tokens or session IDs (SEC-08) |
| Tests | `DbSessionSwitchIT` (10), `connections.test.jsx` (rewritten: 25 tests, 15 more than before), E2E journey (new) and E2E environment (second database) | — |
| Docs | This file, user guide §3 and messages, audit policy catalogue, ADR-0004 implementation note, requirements FN-02/FN-03, decision log | — |

**Not done, on purpose:** opening a session automatically when the workspace opens. Every open is audited and counts against the per-user limit of 3; the approved rules don't say sessions may be opened without the user choosing. Phase 1 offers a one-click **Connect to <last used>** instead. Automatic opening is listed as a stakeholder decision.

## 3. Baseline before any change

Run on a clean worktree of `b7b8a97` (`dirty: false`), so no Phase 1 edit could leak in. A first attempt in the working tree was stopped and discarded: Maven had compiled a half-edited source file.

| What | Result | Evidence |
|---|---|---|
| Frontend lint + 99 Vitest tests; backend 82 unit + 135 integration tests, coverage thresholds | **PASS** (1 run, master seed `614185438`); coverage frontend lines 98.96 %, backend lines 94.1 % | `docs/evidence/admin-phase-1/baseline-loop-20261019-005729.json` |
| End-to-end in Edge, 11 tests | **PASS** (1 repetition, master seed `1721004829`) | `docs/evidence/admin-phase-1/baseline-e2e-20261019-010203.json` |

## 4. Delivered

| Requirement (plan Phase 1) | How |
|---|---|
| A. Server reachability separate from the session | `ConnectionStatus`: **Signed in** (account, mechanism), **Server** (Checking…, Reachable, or why not: Could not connect, Windows sign-in incomplete, Access refused, Too many failed attempts, Audit service unavailable, Application server unreachable), **Database**, **Session** (No session, Opening…, Open, Switching to…, Ended). Reachable only after a backend-confirmed discovery or session. |
| B. Initial behaviour | Discovery runs when the workspace opens and lists the databases; no session is claimed or opened. Errors appear as the server state and in the panel, never as a session state. |
| C. Switching | **Switch database** in the session bar; current database marked and shown in the bar and the status. Server-side replace (above); a failed switch says the current session is still open. An open (or failed) transaction: the page asks, and the server refuses unless confirmed; rollback, never commit. Not offered while a statement runs. Explorer and results reload for the new database; the editor keeps its text. |
| D. Reconnect and docs | **Reconnect to <database>** and **Choose another database** when a session ends. Last database remembered (name only). User guide and messages updated. |

## 5. Acceptance criteria

| Criterion | Result | Verified by |
|---|---|---|
| Server reported reachable when authenticated discovery succeeds, without a session | **Met** | RTL "discovers the server when it opens…"; E2E journey (Reachable + No session + no session bar) |
| Never reports a session before the backend confirms it | **Met**: the session state is set only from a `201` | RTL (Opening… until the answer; a failed open never shows Open) |
| Discovery and connection errors presented accurately | **Met** | RTL `serverState` mapping, discovery failure, failed open with the database named |
| Switch between two databases without the disconnect workflow | **Met** | `DbSessionSwitchIT.switchingOpensTheNewSessionAndThenClosesTheOldOne`; RTL; E2E (`e2e_db` → `e2e_other`, `current_database()` checked) |
| A failed switch doesn't mark the new database active | **Met**; the old session stays usable | `DbSessionSwitchIT.aFailedSwitchKeepsTheCurrentSession`; RTL |
| An open transaction can't be silently discarded | **Met**, enforced by the server | `DbSessionSwitchIT.anOpenTransactionIsNeverDiscardedWithoutConfirmation` (data rolled back, not committed), `aFailedTransactionAlsoNeedsConfirmation`; RTL; E2E (cancel keeps the session) |
| Session limits, delegation and audit still enforced | **Met** | `DbSessionSwitchIT`: cap, other users' sessions (404), DataAdmin for internal (403), cross-server (400), unknown fields (400), audit records with `replaces` and reason `switched`, audit outage (spooled, then replayed) |
| Login, authorisation and execution unchanged | **Met**: full suites 5/5 and E2E 5/5 | §6 |
| Tests cover connect, reconnect, switching, failed switching, inaccessible databases, audit unavailability, transaction warnings | **Met** | Above; reconnect in RTL and E2E (backend terminated by the superuser) |

## 6. Test evidence (after the change)

| What | Result | Evidence |
|---|---|---|
| Frontend lint + Vitest, single runs during development | **PASS**: lint clean; 114 tests (99 before + 15) | console only |
| `DbSessionSwitchIT` alone, real PostgreSQL 18 | **PASS**, 10 tests (seed `310686637`) | console only |
| End-to-end in Edge, 1 repetition on the new build | **PASS**, 12 tests (11 + the new journey), seed `1652325445` | report not kept (a single repetition) |
| Randomised loop, 5 consecutive runs | **PASS** 5/5 (master seed `797180720`): each run lint + 114 frontend tests, 82 backend unit + 145 integration tests (135 + 10 `DbSessionSwitchIT`), coverage thresholds. A first attempt was stopped during run 1 by low workstation memory (no run finished, nothing failed); this is the re-run. | `docs/evidence/admin-phase-1/loop-20261019-163127.json` |
| End-to-end in Edge, 5 repetitions | **PASS** 5/5, 12 tests each (master seed `544695145`) | `docs/evidence/admin-phase-1/e2e-20261019-164044.json` |
| Text files (no control characters) | **PASS**, 423 files | console |

Commands: `scripts\test\Invoke-TestLoop.ps1 -Tag admin-phase-1`; `scripts\test\Invoke-E2E.ps1 -Tag admin-phase-1 -Repeat 5`; `scripts\ci\Test-TextFiles.ps1`.

## 7. Security and audit implications

- No new route, access level or credential path: switching uses the existing open route with its access check and fresh handshake. Strict JSON still rejects unknown fields (a client still can't name a host, port or role).
- `replaces` must be one of the caller's own sessions in the same app session (others get 404, the same as probing an ID).
- Audit: `db_connect_attempt`/`db_connect_result` carry `details.replaces`; the replaced session's `db_disconnect` has reason `switched`. Connection events keep the approved best-effort behaviour (spooled during an outage, replayed later); execution stays fail-closed.
- The browser stores only the last database name per profile and SID. A shared browser profile reveals that name to the next Windows user of the same profile; no secret is stored.
- Discovery now runs every time a workspace opens: one short connection and one `metadata_browse` record each time. A failure counts towards the failed-open limit (5 per 5 minutes), exactly as clicking the old button did.

## 8. Known limitations

- Reachability is per user (discovery as that user), not an independent health check. `Could not connect` can't tell "server down" from "no access to the maintenance database": the server deliberately returns one generic message.
- At the session limit a switch is refused and the current session kept; the user disconnects first. There is no automatic close-then-open, because it could leave the user with no session.
- Kerberos, delegation and the non-DataAdmin view are still target-environment checks (K1–K6); everything here ran over NTLM loopback.

## 9. Conflicts with approved decisions (later phases; nothing implemented)

| Plan item | Conflict | Proposed decision |
|---|---|---|
| Phase 2 "execute an explicitly selected script" | EXE-03 is **open** and "not to be implemented until decided" | Decide EXE-03 first: script rules for partial failure, transactions and audit (one intent per statement) |
| Phase 2 query history, saved scripts | Audit policy stores only normalised SQL and an HMAC; nothing about statements is kept in the browser | Decide where history lives (browser only, server), its retention, and whether literals may be stored |
| Phase 2 large results "not loaded entirely" | Today: server-side row limit (1,000) and a virtualised grid; cursor streaming deferred (P9-3) | Approve P9-3 (fetch more) within Phase 2 |
| Phase 3 management screens and data editing | "DDL designers" and "inline data editing" are deferred (D9); generated SQL is execution, so EXE-01 and gate G4 apply | ADR: generated-SQL path through the same gate, audit and kill switch; G4 scope covers it |
| Phase 3 create/drop database | Needs a connection to another database and the user's own `CREATEDB`/ownership; no privileged account (SEC-06, D14c) | Confirm: done only with the user's own role privileges |
| Phase 4 export, import | Result export deferred; bulk reading accepted as T29; `COPY … FROM STDIN` is not supported today | ADR: limits, audit events (`data_export`, `data_import`), streaming, and whether T29 still holds |
| Phase 5 backup/restore | No ADR. Running `pg_dump`/`pg_restore` as the user means launching a process with the delegated identity on the app host; the binaries aren't in the bundle (ADR-0008 intake); dump files are data at rest on the server | ADR before any work: process identity, executable intake, storage, retention, ACLs, audit |
| Phase 6 roles and passwords | No PostgreSQL passwords exist (ADR-0002, Kerberos only); no privileged account (D14c) | Role management only with the user's own `CREATEROLE`; no password workflows |
| All phases | The release is not tagged (G4 and MVP sign-off pending); new scope changes what is signed off | Decide whether the MVP is signed off and tagged before admin Phase 2 starts |

## 10. Ready for review?

**Yes, for stakeholder review.** Code, tests and documentation are complete and the delivery gate passed (5 consecutive loop passes, 5 E2E repetitions). Phase 2 is not started: it waits for this review and for the decisions in §9 and the decision log (automatic session open).
