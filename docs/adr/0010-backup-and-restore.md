# ADR-0010: Backup and restore of user databases

- **Status:** **Proposed, awaiting approval**
- **Decision IDs:** A6 (admin plan Phase 5)
- **Approval:** none yet. Drafted 2026-10-20 at the stakeholder's request ("Backup and restore: don't build it. Write a draft ADR for it … and mark it 'Proposed, awaiting approval'"). Nothing in this ADR is built; decision A6 (`docs/admin-phase-4-status.md` §10) stays in force until this ADR is Accepted.

## Context

The administration plan of 2026-10-19 asks for backup and restore (its Phase 5). pgweb today never runs anything except SQL statements on the user's own connection, through the execution gate, as the user's own PostgreSQL role (ADR-0002, ADR-0004, ADR-0009). A logical backup needs `pg_dump`, and a restore `pg_restore` or `psql`: separate programs that open their own connections. That raises questions no approved decision answers:

1. **Identity.** Under which identity do `pg_dump` and `pg_restore` connect? pgweb has no privileged database account (D14c) and never holds passwords (ADR-0002).
2. **Storage.** Where does a dump live, if anywhere, and who can read it? A dump is a full copy of the data at rest, outside PostgreSQL's privileges.
3. **Retention.** How long are stored dumps kept, and how are they deleted?
4. **Restore authority.** Who may restore, into what, and with which safeguards? Restore is the most destructive operation in the plan.
5. **Binaries.** `pg_dump` and `pg_restore` are not in the bundle; they would enter through the offline intake (ADR-0008).

Organisations normally take backups at the server level (base backups and WAL archiving, managed by the DBAs). This ADR covers only **user-initiated logical copies** of databases the user can read, as a convenience; it is not a replacement for server backups.

## Options

| Option | Identity | Storage | Restore | Assessment |
|---|---|---|---|---|
| **A. Child process under the user's delegated identity** | `pg_dump`/`pg_restore` started by the service with the request's delegated Windows token (`CreateProcessWithTokenW`), SSPI to PostgreSQL as the mapped role, like the session connections | Streamed to the browser as a download (default); optional ACL'd share | Into a new, empty database the user creates (CREATEDB), or one they own and that is empty | Keeps "PostgreSQL decides" (D14c). Needs the service account's `SeImpersonatePrivilege` (it has it as a service) and constrained delegation to `POSTGRES/<host>` (already K3). Child processes must have fixed arguments and no shell |
| B. A backup service account | One privileged role (`pg_read_all_data`, or superuser) | Server share | By DBAs | Violates D14c (no privileged account); everyone could read everything through pgweb. Rejected |
| C. Not in pgweb | n/a | n/a | n/a | Users ask DBAs; pgweb offers export of query results (admin Phase 7) for small extracts. The current state (A6) |

## Proposed decision

**Option A, limited as follows, and off by default** (`backup.enabled=false`; it also follows the execution switch, so production has none until both are turned on).

1. **Identity: always the user's own.** `pg_dump` and `pg_restore` run as child processes under the request's delegated Windows identity, connect with SSPI as the user's mapped role, and are refused for superuser roles, as session connections are. No password, no `.pgpass`, no service or DBA account. PostgreSQL's privileges decide what is dumped: a role that can't read a table can't dump it (`pg_dump` then fails; it is not given `--no-privileges` workarounds).
   - Fixed argument lists built by the service (`-Fc`, `--no-password`, `--dbname` as a connection string with the database name URL-encoded); the user chooses only the database, and optionally schemas or tables by name (validated, passed as separate arguments, never through a shell).
   - One job per user at a time; a time limit (default 30 minutes) and a size limit (default 2 GB); Cancel ends the process.
2. **Storage: none by default.** The dump is streamed to the user's browser as a download while `pg_dump` writes it; nothing is written to the app server's disk. Once saved, the file is the user's responsibility, as for an export (T40).
   - **Optional server-side storage** only if the infrastructure owner provides a dedicated share (`backup.storeDir`): one folder per user (by SID), NTFS ACL service account only, encrypted volume (BitLocker), files named by job ID with a SHA-256 manifest. A user sees and downloads only their own dumps.
3. **Retention** (server-side storage only): **14 days** by default (`backup.retentionDays`, 1 to 90), deleted by the service's daily maintenance; every deletion is audited. Dumps are never part of pgweb's own backups or upgrade packages.
4. **Restore: only into an empty database, never over existing data.**
   - Target: a **new database** created by the user in the same flow (their CREATEDB privilege), or a database they own that has **no tables** (checked as their role just before). `--clean` and `--if-exists` are never used; restoring over a database that has data is not offered.
   - `pg_restore --no-owner --no-privileges --single-transaction --exit-on-error` as the user's role, so a failure leaves the target unchanged.
   - **Two-person approval** (the deferred feature, when built): a restore must be approved by a second person from the configured approver group before it starts. Until two-person approval exists, restore is not offered even with `backup.enabled=true`.
   - The UI asks for confirmation and names the target; the server checks it all again.
5. **Audit (fail-closed, like SQL):** `backup_requested` (database, schemas, format) before the process starts, `backup_completed` (bytes, SHA-256 of the file, duration, exit code; **no data**); `restore_requested`, `restore_approved`, `restore_completed` likewise. If the intent can't be written, nothing runs.
6. **Binaries:** PostgreSQL 18 client binaries (`pg_dump.exe`, `pg_restore.exe` and their DLLs) through the offline intake (ADR-0008), with SBOM entries and hashes; the version must be at least the server's. Path in `backup.binDir`; the service refuses to start the feature if the hashes don't match the manifest.

## Consequences

- A new kind of process runs under user identities on the app host. It needs a threat-model entry (token handling, argument injection, resource exhaustion, orphaned processes) and tests that kill and clean up processes.
- Streaming downloads hold an HTTP request open for the length of the dump; the per-user and global job limits protect the service.
- Users can take a full copy of anything their role can read. That is already true with `pg_dump` on their own machine; pgweb adds an audit record of it.
- Restore depends on two-person approval, so it can't ship before that feature.

## Questions for the approver

1. Is a user-initiated logical backup wanted at all, given server-level backups exist (option C remains possible)?
2. Download only, or also the optional server share? If the share: its location, owner and encryption.
3. Retention of 14 days for stored dumps: acceptable, or another figure?
4. Restore only into a new or empty database, with two-person approval: acceptable, or should restore stay with the DBAs?
5. Who is in the approver group for restores?

## Validation

None yet; nothing is built. If Accepted: integration tests with throwaway clusters (dump and restore as a non-superuser role; a role without SELECT on a table can't dump it; restore refused into a database with tables; process killed on cancel and on timeout; audit records without data), and an E2E journey.
