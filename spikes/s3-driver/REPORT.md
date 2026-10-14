# Spike S3: Report

**Result: PASS on the developer workstation (partial S3).** Two independent loops each passed 5 consecutive randomised runs (30 test cases per run), fully offline, with no clusters left behind.

**Not yet done:**
- runs against the **target** PostgreSQL major versions;
- **SCRAM over TLS** (the throwaway cluster has no certificate);
- runs on the GitLab Windows runner and on Windows Server.

These items stay **open** (see the end of this report).

## Environment

| Item | Value |
|---|---|
| Machine | Developer workstation, Windows 11 Pro 10.0.26200, not domain-joined, not air-gapped |
| Java / Maven | Temurin 21.0.12.1+1 / Maven 3.9.16, project-local (`tools\`) |
| Maven mode | **Offline** (`mvn -o`), project-local repository `tools\m2\repository` |
| PostgreSQL | 18.6 (EDB installer binaries), throwaway cluster, SCRAM-SHA-256 auth, no TLS |
| pgJDBC | 42.7.14 |
| Vert.x PG Client | 5.2.1 |

## Loop history (2026-10-15)

| Report | Result | Cause | Fix |
|---|---|---|---|
| (single manual run, seed 12345) | FAIL: 18/19 | Vert.x `rowCount()` returned 379101 for `CREATE TABLE`. Not a test bug: a **driver defect**, see V5. | The test now asserts the observed behaviour so the finding is re-checked on every run |
| first loop (report replaced) | PASS 5/5 (19 tests) | Review: random branches (e.g. "cancel inside a transaction") were never hit in 5 runs, and the corpus was sampled, not complete | Branches became parameterised cases, so every branch runs every time. The full corpus runs each time in random order with random whitespace and case. |
| second loop (report replaced) | PASS 5/5 (24 tests) | Added a transaction-status probe for Vert.x (V6) | — |
| `s3-20261015-075205.json` | FAIL: 3 of 30 every run | Probe sent via `preparedQuery` gave `true` outside a transaction. Extended protocol: the implicit transaction starts at Parse, but `statement_timestamp()` is taken at Execute. | The probe (fixed app SQL, not user SQL) uses the simple protocol. The runner now stops at the first failure (`-ContinueOnFailure` to override). |
| `s3-20261015-075346.json` | **PASS 5/5** (30 tests per run) | Final code | Master seed `586947179` |
| `s3-20261015-075450.json` | **PASS 5/5** (30 tests per run) | Independent confirmation, same code | Master seed `2002493668` |

After each code change the consecutive count restarted at 0. Reports from superseded code versions were replaced. Only the two final reports are kept as evidence.

## Findings: pgJDBC 42.7.14 (Option A)

| # | Finding | Status | Plan impact |
|---|---|---|---|
| J1 | In the default (extended) mode, a multi-statement string is **split on the client**. The server receives one Execute per statement and never sees the script. | **Tested** | Confirms plan §3.2: pgJDBC alone can't enforce single-statement execution. Option A needs the S2 parser gate in front of it. |
| J2 | With `preferQueryMode=simple`, the whole script goes to the server in one Query and **all statements run** | **Tested** | The app must never set `simple` mode. Add a configuration test. |
| J3 | All 20 corpus statements (semicolons inside strings, dollar quotes, nested comments, quoted identifiers, `E''`, `U&''`, jsonb `?`/`?|` operators) reached the server as **exactly one Execute each, with unchanged text**. Escape processing was off. | **Tested** (PG 18.6) | Option A is viable *if* a parser decides the boundaries |
| J4 | With escape processing **on** (the JDBC default), pgJDBC **rewrites** SQL: `{fn ucase('x')}` becomes `upper('x')` | **Tested** | The app must call `setEscapeProcessing(false)`. Otherwise the audited text differs from what ran. |
| J5 | `setMaxRows(n)` stops the **server** after n rows (sequence side-effect proof) in both autocommit modes | **Tested** | Confirms the bounded-retrieval design (§3.4) |
| J6 | `setFetchSize(n)` with autocommit off streams through a portal. Abandoning the result after the first batch stops the server at n rows. | **Tested** | Basis for post-MVP "fetch more" |
| J7 | `UPDATE … RETURNING` with `maxRows` **modifies every matching row**; only the returned rows are cut | **Tested** | The UI must label results "truncated", never "n rows affected", for DML `RETURNING` |
| J8 | Through the public API, DDL, `SET`, `DO` and `UPDATE 0` are indistinguishable: `execute()` returns false and the update count is 0. The command tag is not public. | **Tested** | Showing "CREATE TABLE completed" needs the parser's classification (S2). Without it, show a neutral "Statement completed". |
| J9 | A user `BEGIN` / `START TRANSACTION` in autocommit mode leaves `getAutoCommit()` **true**. The driver's `BaseConnection.getTransactionState()` reports OPEN / FAILED / IDLE correctly for every variant tested, including 25P02 after an error. | **Tested** | The app must track transactions with `getTransactionState()`, which is driver-internal (`org.postgresql.core`). Pin and test it on upgrade. |
| J10 | `Statement.cancel()` ends a running statement with 57014, within about 15–130 ms of the request in the recorded runs, with or without an open transaction. A cancelled transaction becomes FAILED. The connection stays usable. | **Tested** (Windows PG) | Confirms §3.5 step 2 |
| J11 | A **same-role** companion `pg_terminate_backend(pid, 5000)` ends the main backend (57P01). Its transaction rolls back, its table locks are released, and the connection becomes invalid. | **Tested** | Confirms ADR-0004 companion escalation |
| J12 | A non-superuser **cannot** cancel or terminate another role's backend (42501) | **Tested** | The companion can't be used to disrupt other users' sessions |
| J13 | A user's `SET statement_timeout = 0` disables the server backstop (repeats S5 F2). Only the app's `cancel()` stops the statement. | **Tested** | The app's wall-clock timer is mandatory |

## Findings: Vert.x PG Client 5.2.1 (Option B)

| # | Finding | Status | Plan impact |
|---|---|---|---|
| V1 | `preparedQuery()` with 2–5 statements is **rejected by the server**: 42601 "cannot insert multiple commands into a prepared statement". **Nothing runs:** the table stayed empty. | **Tested** | Confirms Option B. Single-statement mode is enforced by PostgreSQL itself, with no parser and no hand-written splitter. |
| V2 | All 20 corpus statements succeeded through `preparedQuery()` as one unchanged Execute each | **Tested** | No false rejections for the traps tested |
| V3 | `query()` (simple protocol) runs whole scripts | **Tested** | User SQL must only ever go through `preparedQuery()`. Enforce with an architecture test. |
| V4 | A cursor `read(n)` inside a transaction stops the server at n rows. `cancelRequest()` ends a running statement with 57014. The connection stays usable. | **Tested** | Bounded retrieval and cancellation are available |
| V5 | **Driver defect:** `rowCount()` for DDL is garbage. The client decodes the last word of the command tag as digits: `CREATE TABLE` gives 379101 and `CREATE INDEX` gives 282250 (`'T'-'0'`… in base 10). DML counts and `UPDATE 0` are correct. | **Tested** (deterministic) | Never show `rowCount()` for non-DML. Classification needs the parser or a neutral message. Report it upstream. |
| V6 | There is no public transaction-status API. Workaround tested: after each user statement, run `SELECT statement_timestamp() <> transaction_timestamp()` **through the simple protocol**. It returns false when idle, after COMMIT and after ROLLBACK; true inside a block, including after SAVEPOINT/RELEASE; and 25P02 inside an aborted block. Through the extended protocol it is **wrong**: always true. | **Tested** (6 scenarios × 4 `BEGIN` variants) | Option B costs one extra round trip per statement for transaction tracking. The probe must be fixed app SQL. |
| V7 | User `BEGIN`/`COMMIT`/`ROLLBACK` work through `preparedQuery()` | **Tested** | No special handling is needed for transaction control |

## Conclusion for the driver decision (ADR-0009, Proposed)

Both drivers can technically meet the plan, but only Option B makes **PostgreSQL itself** refuse multi-statement input (V1). With pgJDBC, single-statement safety would rest entirely on the S2 parser (J1).

Proposed in ADR-0009:
- user SQL goes only through Vert.x `preparedQuery()`;
- scripts, if S2 passes, are split by the parser and each piece is sent the same way;
- pgJDBC is used only for app-owned SQL (audit DB, migrations, fixed metadata queries).

The costs of Option B:
- a probe round trip for transaction state (V6);
- no command tags for DDL without the parser (V5);
- a blocking adapter around the reactive client.

## Still open

| Item | Why it's open | Needed |
|---|---|---|
| Target PostgreSQL major versions | Tested only with 18.6. Targets are Unresolved in the inventory. | Rerun with `-PgBin` pointing at matching binaries |
| SCRAM over TLS, certificate validation | The throwaway cluster has no TLS | Phase 6 connection work, or a cluster with a test CA |
| GitLab Windows runner, Windows Server | Not available here | Run `Invoke-S3Spike.ps1` there |
| Option A single-statement gate | Depends on S2 | Spike S2 |
| Vert.x defect V5 | Upstream bug | Report to eclipse-vertx/vertx-sql-client; recheck on upgrade |
