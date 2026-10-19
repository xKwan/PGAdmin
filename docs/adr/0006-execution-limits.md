# ADR-0006: Execution limits, timeouts and transaction behaviour

- **Status:** **Accepted**
- **Decision IDs:** D5, D10, D11
- **Approval:** 2026-10-15, stakeholder: "Approve all" of the listed defaults (explicit; recorded in `docs/decision-log.md`).

## Context

Limits must be enforced on the server, outside anything user SQL can change. SQL must never be rewritten.

## Proposed defaults (all configurable)

| Setting | Default |
|---|---|
| Max rows per statement | 1,000 (`setMaxRows(limit+1)`) |
| Max response size / cell display | 10 MB / 64 KB |
| Statement wall-clock limit | 60 s external, 300 s internal |
| Cancel grace before escalation | 5 s |
| Idle session / idle in transaction / max transaction age | 15 min / 10 min (warn at 8) / 60 min |
| Heartbeat abandonment (open transaction) | 120 s |
| Sessions per user / global | 3 / 50 |
| App session idle / absolute | 30 min / 8 h |
| Max SQL text | 1 MB |
| Default mode | Auto-commit; manual mode with READ COMMITTED / REPEATABLE READ / SERIALIZABLE and read-only toggle (convenience only) |

## Supported SQL (D5)

- Single-statement execution only, enforced by a verified parser gate or by the server (S2/S3). **No hand-written splitter.**
- Multi-statement scripts only if the parser passes 100% of the corpus.
- Unsupported in MVP: `COPY … FROM STDIN / TO STDOUT`, `LISTEN`, psql meta-commands.
- Target PostgreSQL major version: **18** (Stated, 2026-10-15) for both instances.

## Implementation note: scripts and further pages (admin Phase 2, 2026-10-19)

Decided by the stakeholder on 2026-10-19 (EXE-03 "stop at first error", P9-3 "fetch more"; `docs/decision-log.md`). Not a change to the limits above; two limits were added:

| Setting | Default | Effect |
|---|---|---|
| `limits.maxScriptStatements` | 1,000 | Statements one script may contain; more is refused before anything runs (`TOO_MANY_STATEMENTS`) |
| `limits.maxFetchedRows` | 10,000 | Rows one statement may deliver in all through *Load more*; then the cursor is closed (`truncatedBy: limit`) |

- **Scripts** are split by PostgreSQL's parser only; every statement passes the same single-statement gate (parser and pgJDBC each see exactly one statement), and nothing runs unless all of them pass. Statements run one at a time, each with its own timer, cancel, audit intent and outcome; the script stops at the first statement that doesn't succeed. A script shares one response-size budget across its statements. Statement boundaries depend on `standard_conforming_strings`, so a script stops if that setting changes while it runs.
- **Further pages** exist only inside a transaction: pgJDBC can keep a cursor open only with autocommit off. pgweb keeps the driver's autocommit flag equal to PostgreSQL's own state (off while a transaction is open, on once it has ended), so the driver never sends a `BEGIN` or `COMMIT` of its own and auto-commit behaviour is unchanged (`ScriptAndFetchIT`). The cursor is closed by the next statement, the end of the transaction, the row cap or the end of the session. Outside a transaction a result stays capped at `maxRows`; the page offers to run the statement again in a read-only transaction.

## Inline editing (D11)

Deferred to post-MVP. Data changes are made with SQL.

## DBA backstops (recommended, not relied on)

Role-level `statement_timeout`, `idle_in_transaction_session_timeout`; server `client_connection_check_interval` (PostgreSQL 14+).

## Validation

S2, S3 and P7 tests, including resource-release measurements after cancel. **Not run yet.**
