# Phase 7 Status: SQL Execution Engine

> Updated 2026-10-16. Evidence: `docs/evidence/phase-7/`. Workstation only, PostgreSQL 18.6.
> **Execution stays off by default** (`execution.enabled=false`). The API exists, but there is no editor UI until
> Phase 8, and gate G4 must be signed off before production use. Behaviour reference:
> [`sql-execution-behaviour.md`](sql-execution-behaviour.md).

## Delivered

| Item | Where |
|---|---|
| PostgreSQL 18's parser (libpg_query 18.1.0) through JNA, always called on one dedicated thread (P7-5). Statement boundaries, kinds, syntax-error positions in UTF-16 characters, and audit text with every constant masked by the scanner | `pgweb.sqlparse.SqlParser` |
| Single-statement gate: size limit, NUL refusal, parser count = 1, pgJDBC's own split = 1, `COPY` to or from the client and `LISTEN`/`UNLISTEN` refused, classification (metadata only) | `pgweb.execution.StatementGate` |
| Execution engine: kill switch, one statement per session (409), audit intent before execution (fail-closed), SQL sent unchanged with escape processing off, `maxRows + 1` fetch, row, byte and cell limits, own wall-clock timer, cancel, then escalation through the companion (`pg_terminate_backend`), then quarantine; `indeterminate` on connection loss; transaction state reported | `pgweb.execution.ExecutionEngine` |
| API: `POST /api/db-sessions/{id}/execute` and `/cancel`; structured error details (syntax-error `position`) in the error envelope | `DbSessionServlets.Sessions`, `ApiException`, `ErrorEnvelope` |
| `parser.library` setting; startup refused when execution is enabled and the DLL can't be loaded | `ConfigLoader`, `Application` |
| Parser build promoted from spike S2: reproducible (byte-identical DLL, SHA256 `3A8BFED5…7527`), checked by every CI job that needs it, built when missing, cached in GitLab CI, shipped in the release bundle as `native\pg_query.dll` with its `.sha256` | `native/libpg_query/`, `Assert-SqlParser` (`scripts/ci/CiCommon.ps1`), `offline/New-ReleaseBundle.ps1` |
| Test support: every test cluster logs every statement it receives (`log_statement=all`), so tests can prove that refused text never reached the server | `PgTestCluster` |

## Acceptance criteria (plan Phase 7)

| Criterion | Result | Test |
|---|---|---|
| Distinct outcomes: rows, command (with count), DDL, zero rows, error (SQLSTATE and position), timeout, cancelled, indeterminate | **Tested** | `ExecutionIT` |
| Multi-statement text refused before anything is sent; proof from the server log | **Tested**, including `;DROP`, a trailing comment and NUL | `ExecutionIT.theGateStops…` |
| The §3.1 corpus agrees with the real server (both `standard_conforming_strings` settings); only single statements run | **Tested**, every loop | `ExecutionIT`, `StatementGateTest` |
| Limits: 1,000 rows, 10 MiB, 64 KiB cells cut on a character boundary; `RETURNING` treated as rows | **Tested** | `ExecutionIT.largeResults…`, `ResultBuilderTest` |
| Type fidelity: `NULL` vs `''` vs `'NULL'`, `numeric` digits, `bytea`, `jsonb`, arrays, `interval`, `uuid`, timestamps, Unicode | **Tested** | `ExecutionIT.typesAreReturned…` |
| Timeout unaffected by `SET statement_timeout = 0`, `SET lock_timeout = 0`, `RESET ALL`; session usable afterwards | **Tested** (3 s limit, finished in 2.5–10 s) | `ExecutionIT.theTimeoutHolds…` |
| User cancel stops the statement and releases its row lock (another session then updates the row) | **Tested** | `ExecutionIT.cancelStops…` |
| Ineffective cancel → `pg_terminate_backend` through the companion → backend gone, session quarantined (404 afterwards) | **Tested** with a cancel that does nothing (test hook, refused in prod) | `ExecutionIT.anIneffectiveCancel…` |
| Degraded session (no companion): the stuck connection is closed, the outcome is `unconfirmed`, the session is quarantined | **Tested**. The backend keeps running on Windows (P7-2). | `ExecutionIT.withoutACompanion…` |
| One statement at a time per session (409 `SESSION_BUSY`) | **Tested** | `ExecutionIT.cancelStops…` |
| Audit: intent committed before execution and linked to the outcome; normalised text without literals or comments; HMAC; no text with `standard_conforming_strings = off` | **Tested** | `ExecutionIT.auditIntent…` |
| Audit DB down → 503, nothing executed (server log); an outcome that can't be written is spooled and replayed | **Tested** | `ExecutionIT.noAuditMeans…`, `ExecutionIT.anOutcome…` |
| Kill switch → 403 | **Tested** | `ExecutionIT.theKillSwitch…` |
| Connection lost during or before a statement → `indeterminate`, session ended | **Tested** | `ExecutionIT.aLostConnection…`, `ExecutionIT.aConnectionLostWhileIdle…` |
| Parser robustness (T31): seeded fuzz, 3,000 inputs × both settings, and concurrent use from many threads | **Tested** every loop | `StatementGateTest` |
| `execution` coverage ≥ 90/85 | **Met** (JaCoCo check) | loop reports |
| Packaged bundle starts with execution enabled using `native\pg_query.dll`; a wrong `parser.library` stops startup with the path in the message | **Tested** manually from `release\pgweb-0.1.0-SNAPSHOT` against `scripts\dev-db.ps1` | this file |

## Decisions and open items

| Item | State |
|---|---|
| **Multi-statement scripts are not offered** (EXE-03). The parser passes the whole corpus, so they would be allowed, but running a script needs rules for partial failure, transactions and one audit intent per statement. | Not in this release. **Stakeholder to confirm**, or ask for it as a later phase. |
| T31 in-process parser: re-confirm after finding P7-5, or switch to an out-of-process parser host | **Stakeholder decision requested** |
| Streaming large results with a cursor (needs a transaction) | Phase 9 (transactions). Until then, memory per statement is `maxRows + 1` rows. |
| Closing a session while one of its statements runs | Works (the statement ends as `indeterminate` and the session is quarantined); a cleaner cancel-first close is a Phase 9 concurrency item. |

## Findings

| # | Finding | Impact |
|---|---|---|
| P7-1 | libpg_query reports a syntax error's `cursorpos` in **characters** (code points: PostgreSQL's `pg_mbstrlen`), while statement locations are **bytes** (S2 P5). The first implementation converted it as bytes and placed errors too early after non-ASCII text. | Converted from code points to UTF-16 positions; tested with é, 漢 and an emoji |
| P7-2 | PostgreSQL on Windows rejects `client_connection_check_interval` ("must be set to 0 on this platform"). When pgweb has to close a degraded session's connection, the backend runs until its statement finishes. | Documented. DBA backstop: per-role `statement_timeout`. The session bar shows "Limited" for degraded sessions. The test asserts this behaviour. |
| P7-3 | A NUL character (allowed in JSON) would end the C string the parser reads, while pgJDBC would send the whole text: `SELECT 1\u0000; DROP …` could have passed as one statement. Found by writing the fuzz test. | The gate refuses any text containing NUL; unit and server-log tests |
| P7-5 | **Intermittent native fault in the parser (root cause not identified).** The fuzz test hit `java.lang.Error: Invalid memory access` inside `pg_query_free_parse_result` in 2 of 8 full unit-suite runs. Same seed, same inputs; it didn't recur deterministically. It never appeared in isolation: 1.8 million parses in a separate JVM, the exact failing inputs replayed 100 times, 160,000 parses on 8 threads, and parsing after worker threads exit all gave 0 faults. Ruled out: struct layouts (checked against `pg_query.h`) and stderr redirection (compiled out on Windows: `pg_query_internal.h` defines `DEBUG`, and the DLL imports no `_pipe`/`_dup`). | **Mitigation:** every libpg_query call now runs on one dedicated, long-lived thread (`pgweb-sql-parser`). The library's per-thread state (emulated TLS) is then never torn down by a thread exit, and calls never overlap. After the change: 0 faults in 12 unit-suite soak runs plus the 5 loop runs. A fault would still fail closed: the request ends with 500 and nothing is sent to PostgreSQL. **Residual risk to T31 is higher than assumed when it was accepted. The stakeholder should re-confirm the in-process decision** (the alternative is the out-of-process parser host from ADR-0009). |
| P7-6 | The loop found a test defect. After a backend was terminated while idle, the test expected the next request to report `410`, but the reaper (every 10 s) may already have removed the lost session, giving `404`. Both are correct: no result is ever returned from a dead session. | Test accepts either |
| P7-4 | During this phase, Python heredocs sent through the shell lost backslashes, putting control characters (NUL, BEL, backspace) into sources and one committed doc (`dev-setup.md`, Phase 6) | Fixed; the repository is scanned for control characters before each commit |

## Test evidence

| What | Result | File |
|---|---|---|
| Randomised loop: frontend lint + 38 Vitest tests; backend 76 unit + 107 integration tests with coverage (real PostgreSQL 18.6, real SSPI) | **PASS 5/5**, master seed `714041232`; no native fault in any run | `docs/evidence/phase-7/loop-20261016-081923.json` |
| Earlier loops | PASS 5/5 (`677234264`) before the Sonar refactoring; FAIL (P7-5 native fault); FAIL (P7-6 test defect); PASS 5/5 (`458067711`) before CI-script changes | `loop-20261016-065454.json`, `loop-20261016-071633.json`, `loop-20261016-074632.json`, `loop-20261016-075850.json` |
| Parser soak after the P7-5 change: the unit suite 12 times | 0 faults | `ci-logs` (not committed) |
| SonarQube "pgweb gate", both projects; the export checks that the analyses cover exactly the current sources | **OK**; backend coverage 92.3%, frontend 95.9%; 0 blocker/high issues (after removing two over-complex methods, duplicated literals, and synchronisation on a parameter) | `docs/evidence/phase-7/sonar-20261016-080715.json` |
| Release bundle (`Invoke-CiJob -Job package`) contains `native\pg_query.dll` with the expected SHA256 | **Tested** | `release\pgweb-0.1.0-SNAPSHOT\SHA256SUMS.txt` (not committed) |
