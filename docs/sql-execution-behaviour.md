# SQL execution behaviour

What happens when a user runs SQL through pgweb (plan Phase 7; ADR-0006, ADR-0009; requirements EXE-01 to EXE-08,
AUD-01/02). Everything described here is enforced on the server. The browser can't switch any of it off.

## 1. The request

```
POST /api/db-sessions/{id}/execute     {"sql": "<one statement>"}
POST /api/db-sessions/{id}/script      {"sql": "<one or more statements>"}       (admin Phase 2, EXE-03)
POST /api/db-sessions/{id}/fetch       {"executionId": "<the statement's>"}       (admin Phase 2, P9-3)
POST /api/db-sessions/{id}/cancel      {}
GET  /api/db-sessions/{id}/activity    {"running", "elapsedMs", "waitEventType", "waitEvent", "lockWait",
                                        "scriptStatement", "scriptStatements"}
```

`activity` reads the running statement's `pg_stat_activity` row on the companion connection. The UI polls it once a
second while a statement runs, to show "Waiting for a lock held by another session". It changes nothing and isn't
audited. Degraded sessions report only `running` and `elapsedMs`.

In the browser (Phase 8), Ctrl+Enter runs the selection or, with no selection, the statement at the cursor. The
editor's own SQL grammar finds that statement. This is only a convenience: the server still parses whatever is sent
and refuses anything that isn't exactly one statement. **Run script** (admin Phase 2) is a separate button, never a
key that also runs single statements: it sends the selection, or the whole editor tab, to `/script` (§2a).

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
- `/execute` still takes exactly one statement. Several statements go through `/script` (§2a).
- Statement classification (`select`, `dml`, `ddl`, `transaction`, `session`, …) is shown in the UI and the audit
  only. It is **never** used to allow or deny (S2 P6: `WITH x AS (DELETE …) SELECT …` is a `SelectStmt`).
  PostgreSQL's privileges decide what a user may do.

## 2a. Scripts (EXE-03, decided 2026-10-19: "stop at first error")

1. Checks 1–3 of §2 apply to the whole text (kill switch, busy, size).
2. PostgreSQL's parser finds the statements (no hand-written splitting). A syntax error anywhere refuses the whole
   script (`400 SYNTAX_ERROR`, position in the whole text). More than `limits.maxScriptStatements` (1,000) is
   `400 TOO_MANY_STATEMENTS`.
3. **Each statement's own text** must pass checks 5–8 of §2: exactly one statement for the parser and for pgJDBC,
   nothing unsupported. Otherwise the whole script is refused (`400 UNSUPPORTED` or `400 GATE_DISAGREEMENT`, with
   `statementIndex` and the position of that statement). **Nothing is sent unless every statement passes.**
4. With `confirmDestructive`, a destructive statement anywhere needs **one** confirmation for the script
   (`409 CONFIRMATION_REQUIRED` with every reason, and `statements`).
5. The statements are then sent **one at a time**, each exactly as written, each with its own audit intent
   (fail-closed: written before the statement and only after the previous one ended), its own timer and cancel, and
   its own outcome. The session's normal transaction behaviour applies: in auto-commit each statement commits as it
   finishes; inside a transaction (or between the script's own `BEGIN` and `COMMIT`) the user commits or rolls back
   afterwards. Nothing is committed or rolled back for the user.
6. The script **stops at the first statement that doesn't succeed** (error, timeout, cancel, lost connection). The
   rest are reported as `not_run` and never reach the server. It also stops before a statement when:
   - the user cancelled (Cancel stops the running statement and keeps the rest from starting);
   - `standard_conforming_strings` changed: the boundaries were found with the old setting
     (`stopReason: settings_changed`);
   - the audit intent can't be written (`audit_unavailable`; that statement isn't sent);
   - the session ended.

The answer is `200` with `kind: "script"`: `scriptId`, `total`, `completed`, `stopReason` and `stoppedAt` (index) when
it stopped, the final `transaction`, `durationMs`, `retrievedAt`, and `statements`: one entry per statement with
`index`, `offset` and `length` (where it is in the text that was sent), `statementClass`, and either that statement's
own result document (§4) or `kind: "not_run"` (with `error` when there was a reason). All statements share one
response-size budget (`limits.maxResponseBytes`). Rows are not paged inside a script.

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

| `limits.maxFetchedRows` | 10,000 | Rows one statement may deliver in all through further pages (§4a) |
| `limits.maxScriptStatements` | 1,000 | Statements in one script (§2a) |

Known limitation: pgJDBC reads up to `maxRows + 1` rows into memory before pgweb applies the byte limits. Memory per
running statement is therefore bounded by `maxRows + 1` times the row size, not by `maxResponseBytes`. Keep
`maxRows` moderate. Rows with huge values are the case to watch: 1001 rows of 100 MB values would be about 100 GB.
With a cursor (§4a) the same bound holds per page (pgJDBC holds at most two pages).

### 4a. Further pages (P9-3, decided 2026-10-19)

Inside a transaction (state `OPEN` before the statement), a row result larger than one response stays on a
server-side cursor: the first `maxRows` rows come back with `results[0].more: true`. `POST …/fetch` with the
statement's `executionId` returns the next page (same document shape, plus `fetchId` and `rowsBefore`), until the rows
end or `limits.maxFetchedRows` is reached (`truncatedBy: "limit"`, cursor closed). Each page is audited
(`exec_fetch`, fail-closed, linked to the statement; then `exec_fetch_outcome`) and runs under the same timer and
cancel as a statement. The cursor is closed by the next statement or transaction action, by `COMMIT`/`ROLLBACK`, at
the cap, and when the session ends; a fetch after that is `409 CURSOR_CLOSED`.

pgJDBC can keep a cursor open only with its autocommit flag off. pgweb keeps that flag equal to PostgreSQL's own
state: off only while a transaction is open (pgJDBC then sends no `BEGIN`, as one is already open), on again once it
has ended (pgJDBC then has nothing to commit). The driver therefore never begins or commits anything on its own,
and auto-commit statements, including those that can't run in a transaction (`VACUUM`, `CREATE DATABASE`), behave
as before (`ScriptAndFetchIT`). **Outside a transaction there is no cursor**: the result is cut at `maxRows` as
before, and the page offers *Run again in a read-only transaction*, which begins `READ ONLY` (so the statement can't
change anything) and runs it again; the user ends that transaction with Commit or Rollback.

## 5. Transactions

The session keeps PostgreSQL's normal behaviour. Each statement commits on its own (autocommit) unless the user runs
`BEGIN`. After `BEGIN`, the state stays `OPEN` across requests until `COMMIT` or `ROLLBACK`. After an error inside a
transaction it is `FAILED`, and PostgreSQL rejects everything except `ROLLBACK` with `25P02`.

**Controls (Phase 9).** `POST /api/db-sessions/{id}/transaction` with `{"action": "begin" | "commit" | "rollback",
"isolation": "read committed" | "repeatable read" | "serializable", "readOnly": true}`. The server builds the
statement (`BEGIN ISOLATION LEVEL … READ ONLY`) from these allow-listed values only. It then runs like any other
statement: gate, audit and limits. In the browser:

- **Manual commit** mode: the next statement outside a transaction first begins one with the chosen isolation level
  and read-only option. **Commit** and **Rollback** act on the session's transaction, also one the user began by
  typing `BEGIN`.
- **Read-only is a convenience, not a security control.** Privileges decide what a role may change.
- While the transaction is `FAILED`, Run is disabled until Rollback. `COMMIT` on a failed transaction is turned into
  a rollback by PostgreSQL without an error; the result says so ("COMMIT rolled it back. Nothing was saved.").
- Inside a `REPEATABLE READ` or `SERIALIZABLE` transaction, Refresh shows the same snapshot, and the UI says so.
- Disconnecting with an open transaction asks first, because it rolls the transaction back.

**Ending abandoned transactions (ADR-0006, plan §3.6).** Only running SQL counts as activity. Viewing a session,
heartbeats and activity polls don't, so an open page can't keep an idle transaction and its locks alive (finding
P9-1). The heartbeat answer carries `closesAt`, `closeReason` (`idle`, `idle_in_transaction`,
`max_transaction_age`) and `warning`. The UI warns at 8 of the 10 idle-in-transaction minutes, and 2 minutes before
the other limits. When a limit is reached, the session is closed and PostgreSQL rolls back.

**Concurrency is PostgreSQL's job.** The app adds no data locking; it reports what PostgreSQL decides:

| Situation | What PostgreSQL does | What the user sees |
|---|---|---|
| Same row updated by another session | The statement waits, then proceeds after the other commits | Timer and "Waiting for a lock held by another session" |
| `lock_timeout` reached | `55P03` | "Could not obtain a lock in time. This statement changed nothing." |
| Deadlock | `40P01`: one transaction is aborted | "Deadlock detected. PostgreSQL rolled back the transaction; roll back here too, then try again." |
| Concurrent change under REPEATABLE READ / SERIALIZABLE | `40001` | "…conflicted with a concurrent change. Roll back and try again; nothing is retried automatically." |
| Constraint violated | `23xxx` | The constraint's name, PostgreSQL's message and detail |
| Statement in an aborted transaction | `25P02` | "The transaction is aborted. Roll back to continue." |
| Statement that can't run in a transaction (`VACUUM`, `CREATE DATABASE`, …) | `25001` | "…cannot run inside a transaction. Commit or roll back first." |
| Cancel inside a transaction | The statement ends; the transaction is aborted | "Cancelled"; state `FAILED` |
| Session closed while a statement runs | The app cancels it first, then closes | "Cancelled" |
| The pgweb process dies | The sockets close; PostgreSQL rolls back and releases the locks | The user reconnects |

## 5a. Confirmations for destructive statements

With `profile.<name>.confirmDestructive=true` (the default), the server refuses these statements with
`409 CONFIRMATION_REQUIRED` until they are sent again with `"confirmed": true`. The browser shows a dialog with the
reason:

| Reason | Statements (from PostgreSQL's parse tree) |
|---|---|
| `drop` | `DROP …` |
| `truncate` | `TRUNCATE` |
| `privileges` | `GRANT`, `REVOKE`, `ALTER DEFAULT PRIVILEGES` |
| `ddl` | Other `CREATE`, `ALTER`, `COMMENT`, … |
| `no_where` | An `UPDATE` or `DELETE` without `WHERE` anywhere in the statement, including inside a `WITH` |
| `procedural` | `DO` blocks (they can do anything) |

**This is a safeguard against mistakes, not a security control.** The user can always confirm, and functions or
procedures can do the same things without asking. Privileges decide what a role may do. Each refusal is audited as
`exec_rejected` (`parse_status = confirmation_required`).

A profile can also show a banner in the workspace (`profile.<name>.bannerText`, for example `PRODUCTION`, with
`bannerTone` `info`, `warn` or `danger`).

## 6. Audit (docs/audit-policy.md)

| Event | When | Contents |
|-------|------|----------|
| `exec_rejected` | A check in §2 refused the text | HMAC and length of the text, the reason. No text. |
| `exec_intent` | Before the statement is sent; must be committed (fail-closed) | `execution_id`, class, **normalised text** (comments removed, every constant replaced by `$n` by PostgreSQL's scanner), HMAC-SHA256 of the full text, length, transaction state before. |
| `exec_outcome` | After the statement ended | `intent_id`, outcome (`success`, `error`, `timeout`, `cancelled`, `indeterminate`), SQLSTATE, row count, truncated, duration, cancel outcome, transaction states. Written best-effort: spooled and replayed if the audit DB is down at that moment. |
| `cancel_requested`, `cancel_result` | User cancel and how every stop ended | `confirmed`, `escalated_terminated` or `unconfirmed`. |
| `script_outcome` | A script ended (§2a) | HMAC and length of the whole script, outcome or stop reason, statements and completed count. Each statement has its own `exec_intent`/`exec_outcome` with `scriptId`, `statementIndex`, `statements`. |
| `exec_fetch`, `exec_fetch_outcome` | Before and after a further page (§4a) | Linked to the statement's execution; page number, rows before, rows in the page. The intent is fail-closed. |
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
