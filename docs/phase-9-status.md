# Phase 9 Status: Transactions, Isolation and Concurrency

> Updated 2026-10-16. Evidence: `docs/evidence/phase-9/`. Workstation only: PostgreSQL 18.6, Edge 155, NTLM loopback.
> Execution stays **off by default**. Behaviour reference: [`sql-execution-behaviour.md`](sql-execution-behaviour.md)
> §5 and §5a.

## Delivered

| Item | Where |
|---|---|
| Transaction controls: `POST /api/db-sessions/{id}/transaction` (begin with an allow-listed isolation level and read-only option, commit, rollback). The statement text is built on the server and runs through the normal gate and audit. | `ExecutionEngine.transaction`, `DbSessionServlets` |
| UI: transaction state badge (as PostgreSQL reports it), manual-commit mode that begins a transaction before the next statement, isolation select, read-only checkbox labelled as a convenience, Commit/Rollback, Run locked while the transaction is `FAILED` | `features/transactions/TransactionBar.jsx`, `useExecution.js`, `Workspace.jsx` |
| "COMMIT rolled it back" notice when a failed transaction is committed | `ExecutionEngine` (`StatementGate.isCommit`) |
| Idle-in-transaction warning (8 of 10 minutes), maximum-transaction-age warning, idle-session warning: the heartbeat returns `closesAt`, `closeReason` and `warning` | `DbSessionManager.lifetime`, `LifetimeWarning` |
| **Only running SQL is activity** (finding P9-1) | `DbSessionManager.use` vs `get` |
| Disconnect asks first when a transaction is open | `SessionBar.jsx` |
| Closing a session while a statement runs cancels the statement first | `ExecutionEngine.stopIfRunning` |
| Plain-language explanations for `55P03`, `40P01`, `40001`, `25P02`, `25001`, `25006` and `23xxx`; constraint, schema, table and column names passed through | `api/execution.js` (`explainSqlstate`), `ResultBuilder.error` |
| Destructive-statement confirmation from PostgreSQL's parse tree (`drop`, `truncate`, `privileges`, `ddl`, `no_where` including inside a CTE, `procedural`), per profile (`confirmDestructive`, default on), audited as `exec_rejected`. Documented as a safeguard, **not a security control** | `StatementGate.destructiveReasons`, Workspace dialog |
| Per-profile banner (`bannerText`, `bannerTone`) | `ConfigLoader`, `/api/profiles`, Workspace |
| Snapshot note: Refresh inside a REPEATABLE READ/SERIALIZABLE transaction shows the same snapshot | `ResultView.jsx` |
| Nightly ×20 concurrency job (scheduled pipelines) and `Invoke-TestLoop.ps1 -ItFilter` | `.gitlab-ci.yml`, `scripts/test/Invoke-TestLoop.ps1` |

## Acceptance criteria (plan Phase 9)

| Criterion | Result | Test |
|---|---|---|
| BEGIN/INSERT/ROLLBACK across three requests leaves no row; COMMIT persists | **Tested** (API, and in Edge through the UI) | `ConcurrencyIT.rollbackAcrossRequests…`, E2E |
| Isolation level and read-only applied (`SHOW transaction_isolation`, `transaction_read_only`); write in a read-only transaction gives `25006` | **Tested** | `ConcurrencyIT.isolationAndReadOnly…` |
| Same-row update: the second writer waits (lock wait visible), proceeds after commit, both increments applied | **Tested** | `ConcurrencyIT.aSecondWriterWaits…` |
| `lock_timeout` → `55P03` | **Tested** | `ConcurrencyIT.aLockTimeout…` |
| Deadlock (barrier: the first writer is seen blocked before the second closes the cycle) → exactly one `40P01`, the victim `FAILED`, the survivor `OPEN` | **Tested** | `ConcurrencyIT.aDeadlockAborts…` |
| Serialization failure under REPEATABLE READ → `40001`, `FAILED`, no automatic retry | **Tested** | `ConcurrencyIT.aConcurrentChange…` |
| Cancel while blocked on a lock → `cancelled`, transaction `FAILED` | **Tested** | `ConcurrencyIT.cancellingABlocked…` |
| Statements that can't run in a transaction block surface PostgreSQL's error (`25001`) | **Tested** | `ConcurrencyIT.postgresErrors…` |
| Constraint violations show the constraint name, table and detail | **Tested** | `ConcurrencyIT.postgresErrors…` |
| Abandoned and idle transactions: heartbeats alone don't keep them alive; warning first; reaped at 10 minutes; row lock released | **Tested** with a controllable clock | `ConcurrencyIT.heartbeatsDoNotKeep…` |
| Maximum transaction age ends a busy transaction, with a warning first | **Tested** | `ConcurrencyIT.theMaximumTransactionAge…` |
| Closing a session while a statement runs cancels it first | **Tested** | `ConcurrencyIT.closingASession…` |
| App-process kill: open work rolled back, backends gone, row lock released | **Tested**: pgweb in a child process, killed with no shutdown | `ProcessKillIT` |
| Terminate backend, cluster restart | **Tested** in Phases 6 and 7 | `ExecutionIT`, `DbSessionEdgeIT` |
| Destructive confirmations: required for every reason, nothing runs until confirmed, can be switched off per profile; docs say they aren't security controls | **Tested** (API, RTL, Edge) | `ConcurrencyIT.destructiveStatements…`, `StatementGateTest`, `transactions.test.jsx`, E2E |
| RTL for every transaction state and confirmation flow | **Tested** | `transactions.test.jsx` |
| Zero flaky failures across 20 repetitions of the concurrency suite | **PASS 20/20**, master seed `654498379`, about 17 s per run | `docs/evidence/phase-9/loop-20261016-104929.json` |

## Findings

| # | Finding | Impact |
|---|---|---|
| P9-1 | **Phase 6 defect:** every session lookup counted as activity, including heartbeats (every 30 s), activity polls and session views. An open page therefore kept an idle transaction, and its row locks, alive forever, defeating the 10-minute idle-in-transaction limit. A Phase 6 test even relied on it ("stay active and heartbeating"). | Fixed: only running SQL (`use`) is activity. The test now marks real activity. A new test shows heartbeats alone no longer keep an idle transaction alive. |
| P9-2 | In a failed transaction, PostgreSQL silently turns `COMMIT` into `ROLLBACK` (no error) | The result carries a notice so the user knows nothing was saved |
| P9-3 | Streaming large results with a cursor needs the JDBC connection in non-autocommit mode, which would change how every user statement is wrapped | Not done; memory per statement stays bounded by `maxRows + 1` rows (Phase 7). Recorded as a post-MVP item. |

## Test evidence

| What | Result | File |
|---|---|---|
| Concurrency suite (`ConcurrencyIT`, `ProcessKillIT`), 20 consecutive runs with new seeds | **PASS 20/20**, master seed `654498379`, about 17 s per run | `docs/evidence/phase-9/loop-20261016-104929.json` |
| Randomised loop: frontend lint + 89 Vitest tests; backend 78 unit + 122 integration tests with coverage | **PASS 5/5**, master seed `147407348` | `docs/evidence/phase-9/loop-20261016-110227.json` |
| Earlier runs, before the Sonar fixes changed the code | Concurrency 20/20 (`1251975856`), loop 5/5 (`1710428591`), E2E 5/5 (`1203022876`) | `loop-20261016-101848.json`, `loop-20261016-103149.json`, `e2e-20261016-103332.json` |
| End-to-end in Edge, 5 repetitions (4 tests each, including manual transactions and the confirmation dialog) | **PASS 5/5**, master seed `199593915` | `docs/evidence/phase-9/e2e-20261016-110420.json` |
| SonarQube "pgweb gate", both projects; analyses cover exactly the current sources | **OK**; backend coverage 92.2%, frontend 96.2%; 0 blocker/high issues (after removing two duplicated literals and three ambiguous JSX spacings) | `docs/evidence/phase-9/sonar-20261016-110448.json` |
