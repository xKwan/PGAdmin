# ADR-0009: PostgreSQL driver and single-statement enforcement

- **Status:** **Accepted** (revised 2026-10-15: pgJDBC for all SQL)
- **Decision IDs:** S3 outcome (plan §3.2), related to D5
- **Approval:** 2026-10-15, stakeholder. The stakeholder chose "pgJDBC + parser gate", after being told that Vert.x can't do Kerberos and that the server would therefore no longer double-check single statements. See `docs/decision-log.md`.

## Context

Plan §3.1–§3.2 requires single-statement execution unless a validated parser approves a script, with no hand-written SQL splitter. Spike S3 measured both candidate drivers (`spikes/s3-driver/REPORT.md`). The earlier proposal sent user SQL through Vert.x `preparedQuery()`, so that PostgreSQL itself rejects multi-statement text.

**New constraint (ADR-0002):** both instances authenticate with Kerberos only. The Vert.x PG Client (5.2.1) supports only cleartext, MD5 and SCRAM authentication: its jar contains no GSS or SSPI classes (checked 2026-10-15). pgJDBC 42.7.x supports both GSSAPI (`org.postgresql.gss`) and Windows SSPI (`org.postgresql.sspi`, through Waffle JNA).

## Decision

1. **pgJDBC carries all SQL**: user SQL, audit, migrations, metadata and companion queries. Vert.x is not used.
2. **Single-statement gate in the application (S2 parser):**
   - Text from the user is parsed by libpg_query (built for PostgreSQL 18) with the session's real `standard_conforming_strings`.
   - It is executed only if it contains **exactly one** statement. Parser errors are reported as syntax errors and nothing is sent.
   - Statement-free input (empty, only `;` or only comments) is rejected before sending.
3. **Second check:** pgJDBC splits multi-statement strings on the client (J1). The app verifies that pgJDBC also sees exactly one statement before execution: it runs the driver's own parser through `Connection.nativeSQL` / `Parser.parseJdbcSql` with the same settings. If the two disagree, nothing is sent, and the event is audited as `gate_disagreement`.
4. **Scripts:** the parser splits the script, and each piece goes through the same gate and the same execution path. Script mode is allowed only when the session reports `standard_conforming_strings=on` (S2 P4).
5. **pgJDBC rules for user SQL** (each has an automated test):
   - never use `preferQueryMode=simple` (J2);
   - always call `setEscapeProcessing(false)` (J4);
   - read transaction state from `getTransactionState()`, never from `getAutoCommit()` (J9);
   - `setMaxRows(limit+1)` with a fetch size for bounded retrieval (J5);
   - DML with `RETURNING` and a row limit is reported as "output truncated", not as the affected-row count (J7);
   - the app's own wall-clock timer plus `cancel()` and companion `pg_terminate_backend` escalation is mandatory, because the user can override `statement_timeout` (J10, J11, J13).

## Alternatives

| Option | Why not chosen |
|---|---|
| Vert.x `preparedQuery()` with server-side rejection | Can't authenticate with Kerberos |
| pgJDBC with its own client-side splitting accepted | The driver's lexer would act as a hand-written splitter, which the plan forbids |
| A hand-written splitter or keyword filter | Forbidden by the plan |

## Consequences

- **Single-statement safety depends on the parser gate plus the driver cross-check.** The server no longer double-checks. Mitigations:
  - S2 agreed 100% with the server: 40/40 corpus items, and 0 disagreements on 400–650 differential fuzz inputs per run, over 10 runs.
  - The corpus and the differential fuzzer become part of the application's test suite (P7) and run in every 5× loop.
  - A parser change needs the full corpus to pass again.
- One driver and one set of connection settings to test and maintain.
- No command tags for DDL beyond what pgJDBC reports. The UI shows "Statement completed" for statements that return neither rows nor a count.

## Validation

- S3 (pgJDBC rules J1–J13) and S2 (parser), on PG 18.6, workstation: **Tested**.
- P7 acceptance tests (gate, cross-check, limits, cancel): planned.
- TLS, the GitLab runner and Windows Server: **Untested in target environment**.
