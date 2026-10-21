# Deployment Guide (Windows Server, air-gapped)

How to install pgweb on a Windows Server in the air-gapped environment, from the release bundle alone. Day-2 tasks (upgrade, rollback, rotation, audit, outages) are in [`runbooks.md`](runbooks.md); what users can and can't do is in [`user-guide.md`](user-guide.md).

> **Status (2026-10-16).** The scripts were rehearsed on the developer workstation (Windows 11, no domain, NTLM loopback, pgweb run as a process: `scripts\test\Invoke-DeploymentRehearsal.ps1`). The Windows service mode, Kerberos, delegation, gMSA, GPO and TLS from the internal CA are **untested in the target environment**: see [`../target-environment-checklist.md`](../target-environment-checklist.md). Do the checks marked ✔ there as you go.

Roles in this guide: **AD admin** (SPN, delegation, groups, GPO), **DBA** (PostgreSQL servers, audit database), **server admin** (the Windows host, runs the install scripts as Administrator). One person can hold several roles; production purges and key changes need a second person (runbooks).

## 1. What you need

| Item | Notes |
|---|---|
| Windows Server 2019 or later (x64), domain-joined | Windows 11 works too (test servers). 2 vCPU, 4 GB RAM, 2 GB disk for releases and logs. No Java install needed: the bundle carries its own runtime. |
| PowerShell 5.1 | Built in. Run the scripts as Administrator. |
| The release bundle | `pgweb-<version>.zip` and `pgweb-<version>.zip.sha256`, brought in through the approved media process (section 2) |
| A service account | A **gMSA** (recommended) or a domain account. Not a member of Administrators. |
| A DNS name for the app | For example `pgweb.corp.example`. Users always use this exact name. |
| A TLS certificate for that name | From the internal CA, as a PKCS#12 file (`.p12`) with its private key. |
| The internal CA root certificate | PEM file (`root.crt`), so pgweb can verify the PostgreSQL and audit servers (`verify-full`). |
| PostgreSQL 18: internal (port 5433) and external (5432) | Both with Kerberos (`gss` or `sspi`) authentication for the users (section 5). |
| A **separate** PostgreSQL cluster for the audit database | Not administered by DataAdmin members (D4, `docs/audit-policy.md`). Password authentication for the two pgweb roles, over TLS. |
| The DataAdmin AD group | Its SID goes into the configuration. |
| Firewall | Users → app host on 8443/tcp. App host → both PostgreSQL hosts (5432, 5433) and the audit cluster (port chosen by the DBA). All ports at a glance: [`../ports-and-services.md`](../ports-and-services.md). |

### Release bundle contents

| Path | What |
|---|---|
| `pgweb.jar` | The application (embedded Tomcat, the web UI inside) |
| `runtime\` | Java 21 runtime made with `jlink` from the pinned JDK |
| `service\pgweb-service.exe` | Windows service wrapper (WinSW 2.12.0) |
| `native\pg_query.dll` | SQL parser (loaded only when SQL execution is turned on) |
| `deploy\windows\` | Install, upgrade, rollback and uninstall scripts; service and configuration templates |
| `deploy\sql\` | `audit-bootstrap.sql` (DBA, once) and `verify-privileges.sql` (DBA, after every change) |
| `sbom\` | CycloneDX SBOMs for the backend and the frontend |
| `VERSION`, `SHA256SUMS.txt` | The release version; a checksum for every file. The install scripts refuse a bundle if any file is missing, changed or unlisted. |

**Application allowlisting.** `pgweb-service.exe` (WinSW 2.12.0) is **not Authenticode-signed** by its publisher. If AppLocker or WDAC is enforced, allow it by **file hash**: SHA-256 `05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da` (the bundle's `SHA256SUMS.txt` lists it under `service/pgweb-service.exe`). Also allow `runtime\bin\java.exe` (from the pinned Temurin JDK) and `native\pg_query.dll`; a path rule for `C:\Program Files\pgweb\releases\*` is simpler if your policy allows path rules there.

## 2. Bring the bundle into the air gap

On the connected build side (normally the GitLab `package` job; locally `offline\New-ReleaseBundle.ps1`):

1. Run the vulnerability scan for the release: `offline\Invoke-VulnerabilityScan.ps1 -Tag <release>`. It must pass, or every exception in `offline\vulnerability-exceptions.json` must be **approved**.
2. A second person checks the zip against its `.sha256` file.
3. Transfer the zip and `.sha256` through the approved media process. Run the organisation's malware scan.

On the server, the install scripts check the zip checksum and then every file inside against `SHA256SUMS.txt`. You don't need to unpack anything by hand.

## 3. Active Directory (AD admin)

Kerberos is the only sign-in method in production (`auth.kerberosOnly=true`): only a Kerberos logon can be delegated to PostgreSQL (ADR-0002).

1. **Service account.** A gMSA, allowed to be used by the app server:
   ```powershell
   New-ADServiceAccount -Name svc-pgweb -DNSHostName pgweb.corp.example -PrincipalsAllowedToRetrieveManagedPassword 'APPSRV01$'
   # on the app server:
   Install-ADServiceAccount svc-pgweb; Test-ADServiceAccount svc-pgweb    # True
   ```
   A normal domain account works too: give it a long random password, "password never expires" per your policy, and no interactive logon.
2. **SPN** for the name users type (not the server's own name, unless they're the same):
   ```powershell
   setspn -S HTTP/pgweb.corp.example CORP\svc-pgweb$
   setspn -Q HTTP/pgweb.corp.example     # exactly one account   (checklist K2)
   ```
   If users may also use the short name (`http://pgweb`), add `HTTP/pgweb` as well. A duplicate SPN makes Kerberos fail and the browser falls back to NTLM, which production refuses.
3. **Constrained delegation** (Kerberos only, no protocol transition) from the service account to the PostgreSQL services. Users log on with Kerberos, so protocol transition isn't needed:
   ```powershell
   Set-ADServiceAccount svc-pgweb -Add @{ 'msDS-AllowedToDelegateTo' = @(
       'POSTGRES/internal-pg.corp.example', 'POSTGRES/external-pg.corp.example') }
   Get-ADServiceAccount svc-pgweb -Properties msDS-AllowedToDelegateTo     # checklist K3
   ```
   The PostgreSQL service accounts must hold the matching `POSTGRES/<host>` SPNs (DBA, section 5). The SPN host must be the DNS name in `profile.*.host`, never an IP address (finding S7-3).
4. **Users** have normal domain accounts. Accounts marked "sensitive and cannot be delegated", and members of Protected Users, can sign in to pgweb but can't open database sessions: PostgreSQL refuses the delegated logon, and the failed attempt is audited (`db_connect_result`).
5. **DataAdmin group.** Note its SID: `(Get-ADGroup DataAdmin).SID.Value`. Membership is read at sign-in, so a removal takes effect at the user's next sign-in (at most 8 hours, `auth.session.absoluteHours`).
6. **Browsers (GPO).** Edge and Chrome send Kerberos tickets only to allowed sites. In a GPO for user desktops:
   - Edge: *Administrative Templates → Microsoft Edge → HTTP authentication → Configure list of allowed authentication servers* (`AuthServerAllowlist`) = `pgweb.corp.example`. The delegation allowlist (`AuthNegotiateDelegateAllowlist`) is **not** needed: constrained delegation happens on the server, so the browser never forwards the user's ticket-granting ticket.
   - Alternatively add `https://pgweb.corp.example` to the **Local intranet** zone (Site to Zone Assignment List, value 1).
   - Use the exact host name from the certificate and the SPN (S6 E3). Check from a desktop: the app opens without a password prompt, and `klist` shows a ticket for `HTTP/pgweb.corp.example` (checklist K1, D4).

## 4. Audit database (DBA)

On the **separate** audit cluster, as a superuser, once:

```powershell
psql -h audit-db.corp.example -p 5432 -U postgres -d postgres `
     -v migrator_password="<long random>" -v app_password="<long random>" `
     -f deploy\sql\audit-bootstrap.sql
```

This creates the database `pgweb_audit` and three roles: `pgweb_migrator` (owns the schema, used only by install, upgrade and chain verification), `pgweb_app` (the running service, INSERT only) and `pgweb_audit_reader` (no login; grant it to named auditors). The tables are created later by the install script (`pgweb migrate`).

- Enable TLS on the audit cluster and allow the two roles from the app host only, for example in `pg_hba.conf`:
  ```
  hostssl pgweb_audit pgweb_app,pgweb_migrator  10.1.2.30/32  scram-sha-256
  hostssl pgweb_audit +pgweb_audit_reader       <auditor subnet> gss include_realm=0 map=corp
  ```
- Keep the two passwords for the next section only; don't store them in files.
- Back up `pgweb_audit` with your normal schedule (`pg_dump -Fc`), and always before an upgrade (runbooks).

## 5. The PostgreSQL servers (DBA)

For both instances (internal 5433, external 5432):

1. **Kerberos.** The PostgreSQL service account holds the SPN `POSTGRES/<host>` (uppercase `POSTGRES`), and the server has its keytab (`krb_server_keyfile`), or uses `sspi` when PostgreSQL runs on Windows under a domain account.
2. **`pg_hba.conf`** for connections from the app host:
   ```
   hostssl all all 10.1.2.30/32 gss include_realm=1 krb_realm=CORP.EXAMPLE map=pgweb
   ```
   (`sspi include_realm=1 map=pgweb` on Windows.) Checklist P1.
3. **`pg_ident.conf`**: map each user principal to the role pgweb expects. pgweb's rule is the lower-cased `sAMAccountName` (`identity.roleCase=lower`); exceptions go into `identity.roleOverrides` **and** `pg_ident.conf`:
   ```
   # MAPNAME  SYSTEM-USERNAME                 PG-USERNAME
   pgweb      /^([^@]+)@CORP\.EXAMPLE$        \1
   ```
   PostgreSQL compares the user name case-sensitively. If principals arrive in mixed case, create the roles in lower case and use one line per user, or a lower-case regular expression with the roles to match. Check two test users (P2).
4. **Roles.** One login role per user, never a superuser (pgweb refuses superuser sessions; P3). Internal: only members of DataAdmin need roles. External: grant `CONNECT` only on the databases each user owns or is given; note that `PUBLIC` has `CONNECT` on new databases by default, so revoke it where that matters (P6).
5. **Connections.** Each pgweb database session uses two connections (the session and a companion for cancellation); allow for this in `max_connections` and role connection limits (P4).
6. **Recommended backstops** (pgweb doesn't rely on them): `statement_timeout` and `idle_in_transaction_session_timeout` per role, `client_connection_check_interval` (Linux servers; Windows rejects it).

## 6. Prepare the configuration (server admin, on the target server)

1. Copy the template from the bundle: `deploy\windows\pgweb.properties.template` → for example `D:\intake\pgweb.properties`. Fill in every `<...>`. Every setting is described in [`../dev-setup.md`](../dev-setup.md) section 4. pgweb refuses to start on an unknown key, a missing value, or anything production doesn't allow (NTLM, plain-text secrets, IP addresses for PostgreSQL hosts, TLS without `verify-full`).
2. Keep `execution.enabled=false`. SQL execution is turned on only after gate G4 is signed off (section 9).
3. **Secrets** are DPAPI-protected on the target server. Run in an elevated PowerShell **on that server** (a DPAPI value only works on the machine where it was made):
   ```powershell
   cd <unpacked bundle>\deploy\windows
   .\Protect-PgwebSecret.ps1                 # paste the pgweb_app password      -> audit.password
   .\Protect-PgwebSecret.ps1                 # pgweb_migrator password           -> audit.migrator.password
   .\Protect-PgwebSecret.ps1                 # the .p12 password                 -> server.tls.keystorePassword
   .\Protect-PgwebSecret.ps1 -NewHmacKey     # new random audit HMAC key         -> audit.hmacKey
   ```
   The script reads the secret without echo and prints `dpapi:...`. Copy the HMAC key into the organisation's vault ([runbooks, section 5](runbooks.md#copying-the-hmac-key-to-the-vault)): without it, SQL text HMACs in old audit records can't be checked. `LocalMachine` scope (the default) means the configuration file's ACL is what protects the secrets; the install script restricts it.
4. Put `pgweb.p12` and `root.crt` where the configuration points (the template uses `C:\ProgramData\pgweb\config\`). Do this **before** installing, because the install script starts the service:

   ```powershell
   New-Item -ItemType Directory -Force C:\ProgramData\pgweb\config
   Copy-Item D:\intake\pgweb.p12, D:\intake\root.crt C:\ProgramData\pgweb\config\
   ```
   The install script then sets the folder's ACL (SYSTEM, Administrators and the service account only).

## 7. Install (server admin, elevated PowerShell)

```powershell
cd D:\intake
Expand-Archive pgweb-1.0.0.zip -DestinationPath D:\intake   # only to reach the scripts: D:\intake\pgweb-1.0.0\
D:\intake\pgweb-1.0.0\deploy\windows\Install-Pgweb.ps1 `
    -Bundle D:\intake\pgweb-1.0.0.zip -Config D:\intake\pgweb.properties -ServiceAccount 'CORP\svc-pgweb$'
```

The script stops at the first failed step. It:

1. verifies the zip against its `.sha256`, then every file against `SHA256SUMS.txt` (and refuses paths that would escape the folder);
2. copies the release to `C:\Program Files\pgweb\releases\<version>`;
3. creates `C:\ProgramData\pgweb\{config,logs,spool,work,backup}` with access for SYSTEM, Administrators and the service account only;
4. copies the configuration to `C:\ProgramData\pgweb\config\pgweb.properties` (an existing one is kept unless `-ReplaceConfig`);
5. runs `check-config`, then `migrate` (creates or extends the audit schema; expand-only), then `verify-audit-chain`;
6. points `C:\Program Files\pgweb\current` at the release, registers the Windows service `pgweb` (WinSW, automatic delayed start, restart on failure) under the service account, and starts it;
7. waits until `https://<publicHost>:8443/api/health` and `/api/ready` answer 200, and records the step in `C:\ProgramData\pgweb\deploy-history.json`.

Other parameters: `-InstallRoot`, `-DataRoot`, `-ServiceName`, `-JavaOptions` (default `-Xms256m -Xmx1g`), `-Environment test` (allows a test-mode configuration on a test server). `-NoService` runs pgweb as a process of the calling user instead of a service; it exists for rehearsals and is **not** for servers.

The migrate step may print the warning `schema audit already exists, skipping`: harmless (the migration tool notes that the schema's `CREATE SCHEMA IF NOT EXISTS` had nothing to do).

**Heap dumps stay off** (`-XX:-HeapDumpOnOutOfMemoryError` in the service configuration): a dump could contain the HMAC key and query results (threat T10/T27).

## 8. Check the installation

| Check | How | Checklist |
|---|---|---|
| Service running as the service account | `Get-CimInstance Win32_Service -Filter "Name='pgweb'" \| Select State, StartName` | D1 |
| HTTPS with the internal CA's certificate, HSTS | Open `https://pgweb.corp.example:8443/` in Edge; `curl.exe -sI https://pgweb.corp.example:8443/api/health` shows `Strict-Transport-Security` | D2 |
| Network | `Test-NetConnection internal-pg.corp.example -Port 5433` (and 5432, and the audit cluster) | D3 |
| Silent Kerberos sign-in | From a user desktop: no prompt; `https://pgweb.corp.example:8443/api/me` shows your account and `"authMechanism":"Kerberos"`; `klist` lists `HTTP/pgweb.corp.example` | K1, D4 |
| Sessions run as the user | Open an internal (as a DataAdmin member) and an external session; the session panel shows `session_user` = your role | K4 |
| A non-DataAdmin user can't use the internal server | Sign in as a test user outside DataAdmin: only "External" is offered; the API answers 403 | K5 |
| Least privilege | The DBA runs `psql -X -v ON_ERROR_STOP=1 -d pgweb_audit -f deploy\sql\verify-privileges.sql` on the audit cluster: every check `ok = t`, exit code 0 (3 if any check fails) | — |
| Audit chain | `& 'C:\Program Files\pgweb\current\runtime\bin\java.exe' -jar 'C:\Program Files\pgweb\current\pgweb.jar' verify-audit-chain --config C:\ProgramData\pgweb\config\pgweb.properties` prints `audit chain intact: N records` | — |
| Audit outage alert | Stop the audit cluster briefly (test server): `/api/ready` answers 503, `pgweb.log` shows `audit_failure`, monitoring raises the alert (section 10) | D5 |

## 9. Turning on SQL execution (after gate G4)

Execution is off in the shipped configuration (EXE-01). Turn it on only after gate G4 is signed off in writing, and after the target-environment checks in [`../target-environment-checklist.md`](../target-environment-checklist.md) pass (G5).

1. Check that `native\pg_query.dll` in the release matches `native/libpg_query/pg_query.dll.sha256` (it does if the bundle checks passed).
2. In `C:\ProgramData\pgweb\config\pgweb.properties` set `execution.enabled=true`.
3. `Restart-Service pgweb`. pgweb refuses to start if execution is on and the parser can't be loaded; the service log says why.
4. Record the change (who, when, which sign-off) in your change system. Turning it off again is the kill switch (runbooks).

## 9a. Optional features (all off in the template)

Each is switched on in `pgweb.properties` and takes effect after `pgweb check-config` and `Restart-Service pgweb`. Reference: [`../dev-setup.md`](../dev-setup.md) section 4. Record each change in your change system, as for section 9.

| Feature | Setting | Before switching on | What it changes |
|---|---|---|---|
| SQL execution | `execution.enabled=true` | Gate G4 signed off (section 9) | Run, Run script, Explain, transactions, row editing, Cancel query and End session on others' sessions (PostgreSQL's own rule applies) |
| Confirmation of destructive statements | `profile.<name>.confirmDestructive=true` (template: true) | — | Users confirm DROP, TRUNCATE, DDL, GRANT/REVOKE, DO, UPDATE/DELETE without WHERE and EXPLAIN ANALYZE |
| Two-person approval | `auth.approverSid=<SID of the approvers' AD group>` and `profile.<name>.approvalReasons=drop,truncate,…` (any of `drop`, `truncate`, `ddl`, `privileges`, `procedural`, `no_where`) | `confirmDestructive=true` on that profile; an AD group of approvers. List `ddl` and `procedural` too if a listed statement must not be wrapped in a function or DO block | Listed statements run only after another member of the group approved that exact text, once. Requests are kept in memory and end with a restart |
| Audit trail viewer | `auditViewer.enabled=true` and `auditViewer.host`, `port`, `database`, `sslMode`, `sslRootCert` | DBA: grant `pgweb_audit_reader` on the audit database to each auditor's login role; AD admin: add `POSTGRES/<audit-host>` to the service account's delegation (section 3) | Auditors read the trail in pgweb, as themselves; each search is itself recorded. pgweb's own account stays INSERT-only |
| Saved queries | `savedQueries.enabled=true` | Accept that users' SQL text is stored on this server under `server.workDir\saved-queries` (service account only); include it in the server's backup | Users keep up to 200 named queries each, private to their account |

Backup and restore are not available (ADR-0010, Proposed). Audit purges stay prohibited (runbooks, section 11).

## 10. Monitoring

pgweb has no built-in alert transport; connect the organisation's monitoring agent to these signals:

| Signal | Where | Meaning |
|---|---|---|
| `GET /api/ready` ≠ 200 | HTTPS probe every minute | The audit database is unreachable: SQL execution is refused (fail-closed) |
| `GET /api/health` ≠ 200 | HTTPS probe | The process doesn't answer |
| `ERROR ... audit_failure` | `C:\ProgramData\pgweb\logs\pgweb.log` | An audit write failed (spooled, or the operation refused). Alert on any occurrence. |
| Files in `C:\ProgramData\pgweb\spool\audit-spool.jsonl` older than 5 minutes | File monitor | Audit records waiting for replay (runbooks, "Spool recovery") |
| Service stopped | Windows service monitor | WinSW restarts it twice (10 s, 60 s), then leaves it stopped |

Forwarding these to the Windows Event Log or the SIEM is a target-environment item (D5). Logs roll at 50 MB, kept 30 days (2 GB cap); service wrapper logs are in `logs\service\`.

## 11. Where things are

| Path | Contents |
|---|---|
| `C:\Program Files\pgweb\releases\<version>\` | Installed releases (kept for rollback) |
| `C:\Program Files\pgweb\current` | Junction to the active release |
| `C:\ProgramData\pgweb\config\` | `pgweb.properties`, `pgweb.p12`, `root.crt`, `pgweb-service.xml` |
| `C:\ProgramData\pgweb\logs\` | `pgweb.log` (redacted), `service\` (wrapper) |
| `C:\ProgramData\pgweb\spool\` | Audit records not yet written to the audit database. Never delete. |
| `C:\ProgramData\pgweb\work\saved-queries\` | Users' saved queries, one file per account (only with `savedQueries.enabled=true`). SQL text: back it up and protect it like the configuration |
| `C:\ProgramData\pgweb\deploy-history.json` | Install, upgrade, rollback and uninstall steps with timings |

## 12. Test servers and the CI deployment

A test server is installed the same way with `-Environment test`. The GitLab `deploy:test` job (manual, on the default branch, runner on the test server, elevated) installs or upgrades from the `package` job's bundle, using protected CI variables: `PGWEB_TEST_CONFIG_FILE` (path of the prepared configuration on the server), `PGWEB_TEST_SERVICE_ACCOUNT`, and `PGWEB_AUDIT_BACKUP_CONFIRMED=yes` (set by the DBA for each upgrade, after the audit backup). Before it, the `smoke:package` job rehearses install → use → upgrade → tampered bundle → rollback → uninstall on a throwaway environment.
