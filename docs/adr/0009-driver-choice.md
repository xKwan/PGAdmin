# ADR-0009: PostgreSQL driver and single-statement enforcement

- **Status:** Proposed
- **Decision IDs:** S3 outcome (plan §3.2), related to D5
- **Approval:** _pending_

## Context

The plan requires single-statement execution unless a validated parser approves a script (plan §3.1–§3.2), with no hand-written SQL splitter. Spike S3 measured what each candidate driver sends to PostgreSQL (evidence: `spikes/s3-driver/REPORT.md`, PG 18.6, workstation only).

- **pgJDBC 42.7.14** splits multi-statement strings on the client (J1). It sends parser-approved single statements unchanged, provided escape processing is off (J3, J4). It tracks transaction state internally (J9), and `maxRows`, `cancel()` and the companion terminate all work (J5, J10, J11).
- **Vert.x PG Client 5.2.1** `preparedQuery()` leaves the decision to the server, and the server rejects multi-statement input before anything runs (V1). It has no transaction-status API, so a simple-protocol probe is needed (V6). `rowCount()` is wrong for DDL (V5).

## Proposed decision

1. **PostgreSQL enforces single statements, not the application.** User SQL goes to PostgreSQL only through Vert.x `preparedQuery()`, one Parse per request, so the server rejects any input holding more than one command (42601) before anything runs. This holds whatever S2 concludes.
2. **Scripts** are allowed only if S2 passes. The parser then splits the script, and each piece is sent through the same `preparedQuery()` path. A parser bug can't cause two statements to run from one piece, because the server still refuses.
3. **pgJDBC is kept for app-owned SQL only:** the audit database, Flyway migrations, fixed metadata queries and companion-connection queries. It never carries user-typed SQL. This keeps standard JDBC tooling where the SQL is the application's own.

The ADR stays **Proposed** until the stakeholder approves it and S2 has concluded.
4. These rules apply (each needs an automated test):
   - **pgJDBC:**
     - never use `preferQueryMode=simple` (J2);
     - always call `setEscapeProcessing(false)` for user SQL (J4);
     - read transaction state from `getTransactionState()`, never from `getAutoCommit()` (J9).
   - **Vert.x:**
     - user SQL only via `preparedQuery()`, never `query()` (V3);
     - never display `rowCount()` for non-DML statements (V5);
     - check transaction status with the fixed simple-protocol probe after each statement (V6).
   - **Both:**
     - DML with `RETURNING` and a row limit is reported as "output truncated", not as the affected-row count (J7);
     - the app's own wall-clock timer plus cancel is mandatory, because the server-side `statement_timeout` can be overridden by the user (J13).

## Alternatives

- **pgJDBC for user SQL, behind the S2 parser gate (Option A):** viable per S3 (J3), but then single-statement safety depends entirely on the parser. Kept as the fallback if Vert.x proves unworkable in Phase 7.
- **pgJDBC with its own splitting accepted:** rejected. The driver's lexer would be acting as a hand-written splitter, which the plan rules out.
- **Hand-written splitter or keyword filter:** rejected by the plan.

## Consequences

- **Two drivers.** User sessions use Vert.x, which adds a reactive client to a servlet application. It needs a thin blocking adapter with timeouts.
- **Transaction tracking** costs one extra round trip per user statement.
- **No command tags for DDL** without the parser: the UI shows a neutral "Statement completed".
- **Driver connection settings and the Vert.x upgrade path** must be tested, including defect V5.

## Validation

- S3: **Tested** on the Windows 11 workstation with PostgreSQL 18.6, two independent 5/5 randomised loops (`spikes/s3-driver/results/`).
- **Open:** target PostgreSQL majors, TLS, GitLab runner, Windows Server, and S2.
