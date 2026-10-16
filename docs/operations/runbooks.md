# Operations Runbooks

Day-2 procedures for pgweb on Windows Server. Installation is in [`deployment-guide.md`](deployment-guide.md). Paths below are the defaults (`C:\Program Files\pgweb`, `C:\ProgramData\pgweb`, service `pgweb`). Run the scripts in an elevated PowerShell from the **installed release's** `deploy\windows` folder, or from a newer bundle's.

Shorthands used below:

```powershell
$pgweb  = 'C:\Program Files\pgweb\current'
$config = 'C:\ProgramData\pgweb\config\pgweb.properties'
function pgweb { & "$pgweb\runtime\bin\java.exe" -jar "$pgweb\pgweb.jar" @args --config $config }
# pgweb check-config | pgweb verify-audit-chain | pgweb migrate | pgweb version (version takes no --config)
```

Exit codes of the `pgweb` commands: 0 OK, 1 failed, 2 bad configuration, 3 audit chain broken (`verify-audit-chain` prints `AUDIT CHAIN BROKEN at seq ...`).

**Every configuration change** follows the same pattern: edit `pgweb.properties`, run `pgweb check-config` (it must print `configuration OK`), then `Restart-Service pgweb`. A restart closes every database session; PostgreSQL rolls back their open transactions, and users see their session as closed. Announce restarts during working hours.

Contents: [upgrade](#1-upgrade) · [rollback](#2-rollback) · [uninstall](#3-uninstall) · [kill switch](#4-kill-switch-sql-execution-off) · [secret and key rotation](#5-secret-and-key-rotation) · [server profiles](#6-adding-or-changing-server-profiles) · [DataAdmin](#7-granting-and-removing-dataadmin) · [audit investigation](#8-audit-investigation-and-chain-verification) · [spool recovery](#9-spool-recovery) · [audit DB outage](#10-audit-database-outage) · [partition archive and purge](#11-audit-partition-archive-and-purge) · [dependency intake](#12-dependency-update-intake) · [troubleshooting](#13-troubleshooting)

## 1. Upgrade

Time: about 5 minutes, of which the service is down for well under a minute. In the workstation rehearsal the whole script took 30 s, including both chain verifications.

1. Bring the new bundle in (deployment guide, section 2).
2. **DBA:** back up the audit database and keep the file with the change record:
   ```powershell
   pg_dump -h audit-db.corp.example -U pgweb_migrator -Fc -f pgweb_audit-<date>.dump pgweb_audit
   ```
3. Run:
   ```powershell
   .\Update-Pgweb.ps1 -Bundle D:\intake\pgweb-1.1.0.zip -AuditBackupConfirmed
   ```
   Before anything stops, the script verifies the bundle, copies it to `releases\<new>`, has the new release check the existing configuration, and verifies the audit chain. Then it stops the service gracefully (all database sessions closed), migrates the audit schema with the new release (expand-only, so the old release still works against it), repoints `current`, starts, smoke-tests, and verifies the chain again.
4. **If any step after the stop fails, the script restarts the previous release by itself** and fails with the reason. Nothing else to do except read the message.
5. Check: `pgweb version`, the deployment-guide section 8 checks that apply, and `deploy-history.json`.

The configuration is kept as it is. If the new release needs a new setting, its release notes say so; add it before step 3 (the check-config step catches it).

## 2. Rollback

Target: **within 15 minutes**. Workstation rehearsals: 3–25 s for the whole script, including the smoke test and chain verification (`docs/evidence/phase-12/rehearsal-*.json`).

```powershell
.\Rollback-Pgweb.ps1                    # to the release that was active before the current one
.\Rollback-Pgweb.ps1 -Version 1.0.0     # to a specific installed release
Get-ChildItem 'C:\Program Files\pgweb\releases'    # installed releases
```

It stops the service, repoints `current`, starts, smoke-tests, and verifies the audit chain. **The audit schema is not downgraded and no audit data is removed**: migrations are expand-only, so an older release works against the newer schema. Don't restore the audit dump to roll back; it would discard records written since the upgrade. Restore it only if the audit database itself is damaged (DBA decision, two people).

If the rollback script itself can't run (for example the module is damaged), do it by hand:

```powershell
Stop-Service pgweb
cmd /c rmdir "C:\Program Files\pgweb\current"
cmd /c mklink /J "C:\Program Files\pgweb\current" "C:\Program Files\pgweb\releases\1.0.0"
Start-Service pgweb
Invoke-WebRequest -UseBasicParsing https://pgweb.corp.example:8443/api/ready
```

## 3. Uninstall

```powershell
.\Uninstall-Pgweb.ps1                                  # removes the service and "current"; keeps releases and data
.\Uninstall-Pgweb.ps1 -RemoveReleases                  # also removes C:\Program Files\pgweb
.\Uninstall-Pgweb.ps1 -RemoveReleases -RemoveData      # also C:\ProgramData\pgweb (config, logs, spool)
```

The audit database is never touched. `-RemoveData` refuses while the spool folder holds any file: replay or archive them first ([section 9](#9-spool-recovery)). Copy `logs\` to the archive share first if your retention policy covers application logs. Remove the SPN and delegation (AD admin) and the gMSA if the server is retired.

## 4. Kill switch (SQL execution off)

To stop all SQL execution at once, for example during an incident:

1. In `pgweb.properties` set `execution.enabled=false`.
2. `pgweb check-config`, then `Restart-Service pgweb`.

Users can still sign in, browse metadata and open sessions; every execution is refused with `403 EXECUTION_DISABLED`. To turn execution back on, set `true` and restart; this needs the same sign-off as the first enablement (deployment guide, section 9).

Faster, without editing the file: `Stop-Service pgweb` stops everything (sessions closed, open transactions rolled back).

## 5. Secret and key rotation

Make every new DPAPI value **on the server itself** with `Protect-PgwebSecret.ps1` (deployment guide, section 6). Keep the change in your change system, with the time of the restart.

### Audit runtime password (`pgweb_app`)

1. Generate a password. `.\Protect-PgwebSecret.ps1` → the new `dpapi:` value.
2. Put it into `audit.password`; `pgweb check-config`.
3. **DBA:** `ALTER ROLE pgweb_app PASSWORD '<new>';` (in `psql`, use `\password pgweb_app` so it isn't logged).
4. Immediately `Restart-Service pgweb`. Between steps 3 and 4, audit writes fail: executions are refused, other records are spooled and replayed after the restart. Keep the gap short, or do it in a quiet period.
5. Check `/api/ready` = 200 and that `pgweb.log` has no new `audit_failure`.

### Migrator password (`pgweb_migrator`)

Used only by install, upgrade and `verify-audit-chain`, not by the running service. DBA changes it, you update `audit.migrator.password`, run `pgweb verify-audit-chain` to check. No restart needed.

### Audit HMAC key (`audit.hmacKey`)

The key keys the HMAC of each SQL text and of session IDs in audit records. Records keep the HMAC made with the key in use at the time; **the old key is needed to check old records, so never destroy it**.

1. `.\Protect-PgwebSecret.ps1 -NewHmacKey` prints the protected value. Store the key itself in the organisation's vault with "valid from <date time>" ([how](#copying-the-hmac-key-to-the-vault)).
2. Put the value into `audit.hmacKey`; `pgweb check-config`; `Restart-Service pgweb`.
3. In the vault, mark the previous key "valid until <restart time>". Investigators pick the key by the record's `occurred_at`.

Rotate when policy says (for example yearly) and at once if the key may have leaked (for example the config file and the server's DPAPI were both exposed).

#### Copying the HMAC key to the vault

The key is a 64-character text (48 random bytes, base64). It exists only inside the `dpapi:` value, which an administrator on the same server can open. With a second person present, in an elevated PowerShell on the server:

```powershell
Add-Type -AssemblyName System.Security
$v = '<the audit.hmacKey value without the leading dpapi:>'
[Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($v), $null, 'LocalMachine'))
```

Type the result into the vault entry, then close the window (`Clear-History`). Don't save it to a file or a ticket.

### TLS certificate

1. Get the renewed `.p12` from the internal CA for the same host name. If its password changed, make a new `dpapi:` value for `server.tls.keystorePassword`.
2. Replace `C:\ProgramData\pgweb\config\pgweb.p12` (keep the ACL: copy into the folder, don't move from elsewhere), `pgweb check-config`, `Restart-Service pgweb`.
3. Check the new expiry date in the browser.

### Moving to a new server

DPAPI values only work on the server where they were made. Make new values on the new server; copy `pgweb.properties` with the `dpapi:` values replaced. The HMAC key is the same key (from the vault), protected again on the new server.

## 6. Adding or changing server profiles

pgweb has two fixed profiles, `internal` (DataAdmin only) and `external`. Their settings are `profile.internal.*` and `profile.external.*` (reference: `docs/dev-setup.md`, section 4). To change one, for example after a server move:

1. **AD admin:** if the host name changes, add the new `POSTGRES/<new-host>` to the service account's `msDS-AllowedToDelegateTo` (deployment guide, section 3), and remove the old one afterwards.
2. Edit `profile.<name>.host`, `port`, `sslMode`, `sslRootCert`, `statementTimeoutSeconds`, `confirmDestructive`, `bannerText`/`bannerTone`. Host names only, never IP addresses.
3. `pgweb check-config`, `Restart-Service pgweb`.
4. Open a session on the changed profile and check `session_user` (deployment guide, section 8).

To take a profile out of use, remove all its `profile.<name>.*` lines (at least one profile must remain). Its tile disappears from the landing page.

## 7. Granting and removing DataAdmin

DataAdmin is the AD group whose SID is `auth.dataAdminSid`; only its members can use the internal server (checked by the server on every request).

**Grant:**
1. **AD admin:** add the user to the DataAdmin group.
2. **DBA, internal server:** create the user's login role (lower-cased `sAMAccountName`, never superuser), its grants, and a `pg_ident.conf` mapping if the general rule doesn't cover it (deployment guide, section 5); reload PostgreSQL.
3. The user signs out of pgweb (or closes the browser) and signs in again: group membership is read at sign-in, from a new Kerberos ticket. A user who stays signed in may need `klist purge` or a Windows logoff/logon to get a ticket with the new group.

**Remove:**
1. **AD admin:** remove the user from the group. It takes effect at their next pgweb sign-in, at the latest after `auth.session.absoluteHours` (8).
2. For immediate effect: `Restart-Service pgweb` signs everyone out (announce it). The DBA can also `ALTER ROLE <user> NOLOGIN` on the internal server and end their backends: `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '<user>';`.

## 8. Audit investigation and chain verification

Auditors are named accounts granted `pgweb_audit_reader` (`GRANT pgweb_audit_reader TO "<auditor>";`, by the DBA). They query `audit.event` directly with `psql` or any SQL client; pgweb has no audit UI.

**What a record holds:** `docs/audit-policy.md`. SQL is stored as normalised text (literals become `$1…`, comments removed) plus `sql_hmac`; the full text is not stored.

Useful queries:

```sql
-- Everything one user did in a period (by SID: names can change)
SELECT seq, occurred_at, event_type, profile, database_name, statement_class, outcome, sqlstate, sql_normalised
FROM audit.event
WHERE user_sid = 'S-1-5-21-...' AND occurred_at >= '2026-10-01' AND occurred_at < '2026-11-01'
ORDER BY seq;

-- Executions with an uncertain or missing outcome
SELECT i.occurred_at, i.windows_user, i.database_name, i.sql_normalised, o.outcome
FROM audit.event i
LEFT JOIN audit.event o ON o.event_type = 'exec_outcome' AND o.execution_id = i.execution_id
WHERE i.event_type = 'exec_intent' AND (o.event_id IS NULL OR o.outcome = 'indeterminate')
ORDER BY i.seq;

-- Refusals: DataAdmin, superuser, identity mismatch, rejected statements
SELECT occurred_at, windows_user, event_type, details
FROM audit.event WHERE event_type IN ('authz_denied', 'superuser_refused', 'session_user_mismatch', 'exec_rejected')
ORDER BY seq DESC LIMIT 100;
```

- `indeterminate` means the statement **may have committed** (the connection broke or a cancel wasn't confirmed). An intent without any outcome means the outcome is unknown; treat both as "possibly executed".
- An intent and its outcome share `execution_id`; the outcome's `intent_id` names the intent's execution.

**Was this exact SQL text run?** Compute its HMAC with the key that was valid at the time (from the vault) and look for it:

```powershell
$key  = [Text.Encoding]::UTF8.GetBytes('<key text from the vault>')   # the key is the text itself, as stored
$text = Get-Content -Raw .\suspect.sql
$mac  = New-Object Security.Cryptography.HMACSHA256 -ArgumentList (,$key)
-join ($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes('sql:' + $text)) | ForEach-Object { $_.ToString('x2') })
# then: SELECT * FROM audit.event WHERE sql_hmac = '<that value>';
```

The text must match exactly, character for character (line endings included).

**Chain verification.** Run monthly, before every upgrade (the upgrade script does it), and whenever records are questioned:

```powershell
pgweb verify-audit-chain     # "audit chain intact: N records", or "AUDIT CHAIN BROKEN at seq S: <problem>"
```

It recomputes every record's hash from the first record. **A broken chain is a security incident:** don't restart or upgrade; preserve the audit database (DBA: `pg_dump` and a file-system copy if possible), the server's logs and spool; report it. The chain makes edits and deletions *detectable*; a superuser on the audit cluster can still change data (threat T21), which is why that cluster is separate from the servers DataAdmin administers.

## 9. Spool recovery

When an audit record can't be written, pgweb writes it to `C:\ProgramData\pgweb\spool\audit-spool.jsonl` (hash-chained lines) and retries every 30 seconds. When the replay succeeds, the file is renamed to `audit-spool-replayed-<time>.jsonl` and an `audit_spool_replayed` record is written. Normally there is nothing to do.

- **Never delete or edit spool files.** The replayed files are kept for forensics; move them monthly to the audit archive share with a SHA-256 manifest (`offline\Write-Sha256Manifest.ps1`).
- **Replay keeps failing** (`audit_failure: spool replay failed ...` every 30 s): fix the audit database first ([section 10](#10-audit-database-outage)). Replay is one transaction, so a failure leaves nothing half-written.
- **`audit spool chain broken at line N`**: the file was altered or truncated. Treat it as an incident. Stop the service, copy the folder to the incident store, then move `audit-spool.jsonl` out of the folder (pgweb starts a new spool). Give the old file to the auditors; it is not replayed automatically.
- **Server lost or replaced:** copy the old server's `spool` folder into the new server's `spool` folder before its first start; it is replayed then. A crash between the commit and the rename can replay records twice: such duplicates share their `event_id`.

## 10. Audit database outage

**What users see:** with `audit.failMode=closed` (approved), every SQL execution is refused with `503 AUDIT_UNAVAILABLE` ("audit unavailable"); sign-in, metadata browsing, opening and closing sessions still work, and those records are spooled. `/api/ready` answers 503.

1. Confirm: `/api/ready` is 503; `pgweb.log` shows `audit_failure`; `Test-NetConnection audit-db.corp.example -Port 5432`.
2. **DBA:** restore the audit cluster (service, disk space, connection limit for `pgweb_app`, password, `pg_hba`). Don't change `audit.failMode` to `open` to keep users working: that allows unaudited changes and is outside the approved policy.
3. When the database is back, pgweb recovers by itself within 30 seconds: `/api/ready` = 200, the spool is replayed ([section 9](#9-spool-recovery)), executions work again. No restart needed.
4. Run `pgweb verify-audit-chain` and record the outage window in the incident log.

## 11. Audit partition archive and purge

Retention (approved): **12 months online, 7 years archived**. `audit.event` is partitioned by month (`audit.event_yYYYYmMM`, created three months ahead by pgweb at start-up through `audit.ensure_partitions`; rows outside any partition go to `audit.event_default`). The DBA does this; purging needs **two people**.

**Monthly archive** (each closed month, keeps it online too):

```powershell
pgweb verify-audit-chain          # must be intact first; note the record count
psql -h audit-db -U pgweb_migrator -d pgweb_audit -c "\copy (SELECT * FROM audit.event_y2026m10 ORDER BY seq) TO 'event_y2026m10.csv' WITH (FORMAT csv, HEADER)"
pg_dump -h audit-db -U pgweb_migrator -Fc -t audit.event_y2026m10 -f event_y2026m10.dump pgweb_audit
Get-FileHash event_y2026m10.* -Algorithm SHA256 | Format-List >> MANIFEST.txt
```

Copy the files and the manifest to the ACL-restricted archive share. Record in the manifest the first and last `seq` of the month and the `row_hash` of the last row: `SELECT min(seq), max(seq) FROM audit.event_y2026m10;` and `SELECT encode(row_hash,'hex') FROM audit.event WHERE seq = <max>;`. That last hash anchors the next month's chain.

**Purge** (partitions older than 12 months, already archived and checked):

> **Known limitation.** `verify-audit-chain` checks the chain from the very first record (seq 1). After the first partition is dropped it will report `AUDIT CHAIN BROKEN at seq <first remaining>`. Verification from an archived anchor (the last `row_hash` recorded in the manifest) is not implemented yet. The first purge falls due 12 months after go-live; implement anchor verification before then (tracked in `docs/phase-12-status.md`). **Until then, don't purge.**

When it is available, the procedure will be: verify the chain; check the archive files against the manifest (second person); `ALTER TABLE audit.event DETACH PARTITION audit.event_y2025m10; DROP TABLE audit.event_y2025m10;` as `pgweb_migrator`; verify from the anchor.

The 7-year archive is purged from the share by the records-management process.

## 12. Dependency update intake

New or updated libraries and tools come in only through the offline intake: [`../../offline/README.md`](../../offline/README.md) (exact versions at least two weeks old, checksums, vulnerability scan, second-person check, approved media). After intake, a release is built by the pipeline and installed with [section 1](#1-upgrade). Vulnerability exceptions need the stakeholder's approval in `offline/vulnerability-exceptions.json`.

The WinSW service wrapper and the Java runtime are part of the release bundle; updating them is an ordinary upgrade.

## 13. Troubleshooting

| Symptom | Likely cause | Check / fix |
|---|---|---|
| Service stops right after starting | Configuration refused | `C:\ProgramData\pgweb\logs\service\*.err.log`; `pgweb check-config` lists every problem |
| `dpapi` secret can't be decrypted | The value was made on another machine, or in `CurrentUser` scope for another account | Make it again on this server ([section 5](#5-secret-and-key-rotation)) |
| Browser asks for a password, or 401 | Host not in `AuthServerAllowlist`/intranet zone; user opened another host name; no Kerberos ticket (NTLM is refused in production) | `klist` on the client; GPO; use the exact `server.publicHost` |
| Kerberos fails for everyone | Missing or duplicate `HTTP/<host>` SPN; clock skew over 5 minutes | `setspn -Q HTTP/<host>`; `w32tm /query /status` |
| Sign-in works, database session fails for everyone | Delegation missing or to the wrong SPN; PostgreSQL `pg_hba`/`pg_ident`; `POSTGRES/<host>` SPN or keytab on the PostgreSQL side | Service account `msDS-AllowedToDelegateTo`; PostgreSQL server log |
| Session fails for one user | No role, no `CONNECT`, `pg_ident` mapping, account "cannot be delegated" or in Protected Users | PostgreSQL log; the user's AD account options |
| Internal tile missing for a DataAdmin member | Membership not in the user's ticket yet | User signs in again after `klist purge` ([section 7](#7-granting-and-removing-dataadmin)) |
| Every execution refused: `AUDIT_UNAVAILABLE` | Audit database down | [Section 10](#10-audit-database-outage) |
| Every execution refused: `EXECUTION_DISABLED` | Kill switch off (default) | [Section 4](#4-kill-switch-sql-execution-off) |
| Service won't start with execution on | Parser library missing or not loadable | `parser.library`; `native\pg_query.dll` present (no Visual C++ redistributable needed: MinGW build); AppLocker/WDAC allows it |
| "You already have 3 open database sessions" (`SESSION_LIMIT`), or `SERVER_BUSY` | Per-user (3) or global (50) session limit | The user closes sessions from the list shown; limits in `limits.*` |
| Upgrade failed and rolled back | Read the script's message; `deploy-history.json`; service logs | Fix the cause, run the upgrade again |
| `AUDIT CHAIN BROKEN` | Records changed or removed | Security incident ([section 8](#8-audit-investigation-and-chain-verification)) |

Each error response carries a `requestId`; the same ID is in the `pgweb.log` lines for that request.
