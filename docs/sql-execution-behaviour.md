# SQL execution behaviour

What happens when a user runs SQL through pgweb (plan Phase 7; ADR-0006, ADR-0009; requirements EXE-01 to EXE-08,
AUD-01/02). Everything described here is enforced on the server. The browser can't switch any of it off.

## 1. The request

```
POST /api/db-sessions/{id}/execute     {"sql": "<one statement>"}
POST /api/db-sessions/{id}/cancel      {}
```

`{id}` is a DB session the caller opened (Phase 6). A session that belongs to someone else, or to another app
session of the same user, is reported as `404`.

## 2. Checks before anything is sent to PostgreSQL

These run in order. A request that fails a check is refused. Nothing reaches the server, and the server's own
statement log proves it (`ExecutionIT`).

| # | Check | Refusal |
|---|-------|---------|
| 1 | `execution.enabled=true` (kill switch, EXE-01; default **false**) | `403 EXECUTION_DISABLED` |
| 2 | The session is idle: one statement at a time (EXE-08) | `409 SESSION_BUSY` |
| 3 | Text at most `limits.maxSqlBytes` (default 1 MiB) | `413 TOO_LARGE` |
| 4 | PostgreSQL's own grammar (libpg_query 18.1, with the session's current `standard_conforming_strings`) accepts it | `400 SYNTAX_ERROR`, with `position` (characters, 1-based) |
| 5 | It contains a statement | `400 EMPTY_STATEMENT` (empty, only `;`, only comments) |
| 6 | It contains exactly **one** statement | `400 MULTIPLE_STATEMENTS` ("found N") |
| 7 | pgJDBC's client-side splitter also sees exactly one statement | `400 GATE_DISAGREEMENT` |
| 8 | Not `COPY … FROM STDIN / TO STDOUT`, `LISTEN` or `UNLISTEN` | `400 UNSUPPORTED` |
| 9 | The audit **intent** record is committed (AUD-01) | `503 AUDIT_UNAVAILABLE` in fail-closed mode |

Notes:

- **No hand-written splitting.** Statement boundaries come only from PostgreSQL's parser. pgJDBC is a second check,
  because pgJDBC itself splits text on `;` on the client. If the two disagree, the text is not run. The corpus in
  `ParserCorpus` (quotes, `E''`, `U&''`, `standard_conforming_strings` on/off, nested comments, dollar quoting,
  `DO`, PL/pgSQL, `BEGIN ATOMIC`, multi-action rules, psql meta-commands, Unicode, CTEs, syntax errors) runs
  against the real server in every test loop.
- A trailing `;` is fine: `SELECT 1;` is one statement.
- psql meta-commands (`\dt`, `\gset`, `\copy`, `\i`) are not SQL. PostgreSQL's grammar rejects them as syntax
  errors.
- **Multi-statement scripts are not offered in this release.** EXE-03 allows them only if the parser passes the whole
  corpus. It does (S2), but running a script needs its own rules for partial failure, transactions and audit (one
  intent per statement). That is deferred. A user who needs several statements runs them one at a time, inside
  `BEGIN … COMMIT` if they must be atomic.
- Statement classification (`select`, `dml`, `ddl`, `transaction`, `session`, …) is shown in the UI and the audit
  only. It is **never** used to allow or deny (S2 P6: `WITH x AS (DELETE …) SELECT …` is a `SelectStmt`).
  PostgreSQL's privileges decide what a user may do.

## 3. Execution

- The statement is sent exactly as written. pgweb never rewrites SQL (EXE-05): no `LIMIT` is added and JDBC escape
  processing is off (ADR-0009 J4). It runs on the session's dedicated connection, as the user's own role.
- The driver asks the server for at most `limits.maxRows + 1` rows, so pgweb can tell when a result was truncated.
- **Timeout (EXE-06).** pgweb's own wall-clock timer (`profile.<name>.statementTimeoutSeconds`: external 60 s,
  internal 300 s) starts when the statement is sent. `SET statement_timeout = 0`, `RESET ALL` and similar don't
  affect it.
- **Cancel (EXE-07).** On timeout or user cancel, pgweb sends PostgreSQL's cancel request. If the statement hasn't
  ended after `limits.cancelGraceSeconds` (5 s), pgweb ends the backend with `pg_terminate_backend` on the session's
  companion connection. That runs as the same user, which PostgreSQL allows for one's own backends. The DB session is
  then **quarantined**: closed and never reused. If there is no companion (degraded session) or termination fails,
  the main connection is closed instead, and the outcome is `unconfirmed`. PostgreSQL then ends the backend only
  when it notices the client has gone, which during a long statement may be only when the statement finishes
  (finding P7-2). `client_connection_check_interval` would detect it sooner, but PostgreSQL on Windows rejects any
  value other than 0. **DBA recommendation:** set a per-role or per-database `statement_timeout`
  (`ALTER ROLE … SET statement_timeout = '15min'`) as the last backstop. It doesn't replace pgweb's own timer; it
  only bounds a statement pgweb could no longer stop. A user can still change it with `SET` in their own session,
  so it is a backstop, not a control. Degraded sessions are shown as "Limited" in the session bar.

## 4. The result

`200` with a result document. HTTP status reports only whether the request was handled. The statement's own outcome
is in `kind`:

| `kind` | Meaning |
|--------|---------|
| `rows` | The statement returned rows (`SELECT`, `… RETURNING`, `SHOW`, `EXPLAIN`, …). `results[0]` has `columns` (`name`, PostgreSQL `type`), `rows`, `rowCount`, `truncated`, `truncatedBy` (`rows` or `bytes`) and `truncatedCells` (`[row, column]` pairs). |
| `command` | No rows. `results[0].rowCount` is the affected-row count when PostgreSQL reports one (`UPDATE 7`). |
| `error` | PostgreSQL refused the statement: `error.sqlstate`, `message`, and `position`, `detail`, `hint`, `severity` when the server gave them. |
| `timeout` | Stopped by pgweb's timer. `cancelOutcome`: `confirmed`, `escalated_terminated` or `unconfirmed`. |
| `cancelled` | Stopped by the user. `cancelOutcome` as above. |
| `indeterminate` | The connection was lost while the statement ran. **It may or may not have taken effect.** Check before running it again. |

Every document also has `executionId`, `statementClass`, `transaction` (`IDLE`, `OPEN` or `FAILED`, as tracked by
pgJDBC), `durationMs` and `retrievedAt`. When the session was quarantined, `sessionEnded: true` is set, and the user
must open a new session.

### Values

- Every value is PostgreSQL's own text output (`rs.getString`), so nothing is lost to Java types. `numeric` keeps
  every digit; `bytea` is hex (`\xdeadbeef`); arrays, `jsonb`, `interval`, `uuid` and timestamps appear as `psql`
  would show them. Timestamps use the session's `TimeZone`.
- SQL `NULL` is JSON `null`. The empty string and the text `'NULL'` stay strings.

### Limits (ADR-0006)

| Setting | Default | Effect |
|---------|---------|--------|
| `limits.maxRows` | 1000 | Rows kept per result; `truncatedBy: rows` beyond. |
| `limits.maxResponseBytes` | 10 MiB | Estimated size of the kept rows; `truncatedBy: bytes` beyond. |
| `limits.maxCellBytes` | 64 KiB | A longer value is cut on a character boundary and listed in `truncatedCells`. |
| `limits.maxSqlBytes` | 1 MiB | Longest accepted statement text. |

Known limitation: pgJDBC reads up to `maxRows + 1` rows into memory before pgweb applies the byte limits. Memory per
running statement is therefore bounded by `maxRows + 1` times the row size, not by `maxResponseBytes`. Keep
`maxRows` moderate. Rows with huge values are the case to watch: 1001 rows of 100 MB values would be about 100 GB.
Streaming with a cursor (`setFetchSize`) needs an open transaction and is planned with transaction support
(Phase 9).

## 5. Transactions

The session keeps PostgreSQL's normal behaviour. Each statement commits on its own (autocommit) unless the user runs
`BEGIN`. After `BEGIN`, the state stays `OPEN` across requests until `COMMIT` or `ROLLBACK`. After an error inside a
transaction it is `FAILED`, and PostgreSQL rejects everything except `ROLLBACK` with `25P02`. The reaper ends
sessions idle in a transaction (Phase 6 rules); PostgreSQL rolls back.

## 6. Audit (docs/audit-policy.md)

| Event | When | Contents |
|-------|------|----------|
| `exec_rejected` | A check in §2 refused the text | HMAC and length of the text, the reason. No text. |
| `exec_intent` | Before the statement is sent; must be committed (fail-closed) | `execution_id`, class, **normalised text** (comments removed, every constant replaced by `$n` by PostgreSQL's scanner), HMAC-SHA256 of the full text, length, transaction state before. |
| `exec_outcome` | After the statement ended | `intent_id`, outcome (`success`, `error`, `timeout`, `cancelled`, `indeterminate`), SQLSTATE, row count, truncated, duration, cancel outcome, transaction states. Written best-effort: spooled and replayed if the audit DB is down at that moment. |
| `cancel_requested`, `cancel_result` | User cancel and how every stop ended | `confirmed`, `escalated_terminated` or `unconfirmed`. |
| `session_quarantined` | The session was closed after escalation or connection loss | Reason. |

With `standard_conforming_strings = off`, the scanner's view of literals is not reliable. Then **no text** is stored,
only the HMAC and metadata (S2 N3). The literal values a user typed never appear in the audit record or the
application log.

## 7. Turning execution on

1. Build the parser library on a connected build machine:
   `powershell -File native\libpg_query\Build-PgQuery.ps1`. It is reproducible: the SHA-256 must match
   `native/libpg_query/pg_query.dll.sha256`. The release bundle carries it as `native\pg_query.dll`.
2. Set `parser.library` if the DLL is not at `native\pg_query.dll` under the working directory.
3. Set `execution.enabled=true`. pgweb refuses to start if execution is enabled and the parser can't be loaded.
4. Gate G4 (plan) must be signed off first.
