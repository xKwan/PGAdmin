# ADR-0004: DB session model and companion connection

- **Status:** **Accepted**
- **Decision IDs:** D12, D14b, D14c
- **Approval:** 2026-10-15, stakeholder: "Approve all" of the listed defaults (explicit; recorded in `docs/decision-log.md`).

## Context

Transactions must span HTTP requests, users must be isolated, and cancellation must be enforceable even when the user's password is not retained.

## Proposed decision

- Each DB session holds **two dedicated connections**, both opened as the user during the same Kerberos-delegated open request (ADR-0002): a **main** connection for user SQL and a **companion** connection for cancel escalation (`pg_terminate_backend` on its own backend), lock diagnostics (`pg_blocking_pids`) and metadata browsing.
- Sessions are pinned in the single backend instance, bound to Windows SID + app session; IDs are 128-bit random; foreign IDs return 404.
- No pooling of user connections. Connections in `BROKEN`, `TERMINATED` or unknown state are closed, never reused.
- State machine, reaper, heartbeat and cleanup per plan §3.6.
- Superuser PostgreSQL sessions are refused (D14c).
- Single backend instance (D12). The service is stateful; restart loses sessions (PostgreSQL rolls back open transactions).

## Alternatives

- **No companion connection:** cancel escalation would need a privileged monitor account (`pg_signal_backend`), and metadata would be blocked while a query runs.
- **Pooling:** unsafe for session state (GUCs, temp tables, transactions); rejected for user SQL.

## Consequences

- Two connections per session count against `max_connections` and per-role limits (A11). If the companion can't open, the session degrades (no metadata, no escalation) visibly and is audited.
- Metadata on the companion shows committed state only.

## Implementation note: switching database (admin Phase 1, 2026-10-19)

Not a change to this decision. PostgreSQL can't change a connection's database, so a switch is a **replacement**: the same open request (same route access, fresh Windows handshake, caps, `session_user` and superuser checks, audit) with `replaces` naming one of the user's own sessions on the same server. The new session opens first; the replaced one is closed (reason `switched`) only after that succeeded, so a failed switch leaves it as it was. The replaced session's statement lock is held during the switch. A transaction that isn't `IDLE` (open, failed or unknown) is refused with `TRANSACTION_OPEN` unless the user confirmed discarding it; closing makes PostgreSQL roll it back, and nothing is committed on the user's behalf. The caps count the replaced session as well: there is no temporary extra session, so at the per-user limit a switch is refused and the session kept.

## Validation

S3 (terminate from same role works on Windows PostgreSQL) and P6/P7 tests.

- **S5 F3/F4 and S3 J10–J12 (Tested on the workstation, PG 18.6, 2026-10-15):**
  - A same-role non-superuser companion can cancel and terminate the main backend. The transaction rolls back and its locks are released.
  - The same role can't signal another role's backend (42501).
  - Evidence: `spikes/s5-ephemeral-pg/REPORT.md`, `spikes/s3-driver/REPORT.md`.
- **Still open:** target PostgreSQL versions, the GitLab runner, and P6/P7 tests.
