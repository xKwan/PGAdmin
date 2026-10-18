# Environment Inventory

> **Phase:** 0 (discovery). **Plan reference:** `docs/implementation-plan-v2.md` §12.
> Status labels follow the plan: **Verified**, **Tested**, **Stated**, **Assumed**, **Unresolved**.
> Every entry needs a **source** (who confirmed it, or which config or command output it came from).
> Record redacted values only: no passwords, no real `pg_hba.conf` addresses, no hostnames that must stay confidential.

---

## 1. Developer workstation (where this repository was planned)

Collected on 2026-10-15 by running read-only commands on the workstation. **This machine is not the target environment.** These facts describe local development capability only.

| Item | Value | Status | Source |
|---|---|---|---|
| OS | Windows 11 Pro 10.0.26200 | Verified | `Win32_OperatingSystem` |
| Domain-joined | **No** | Verified | `Win32_ComputerSystem.PartOfDomain` |
| Git | 2.56.0.windows.2 | Verified | `git --version` |
| Node.js / npm | v24.20.0 / 11.19.0 | Verified | `node -v`, `npm -v` |
| JDK / Maven | Not installed system-wide. Since 2026-10-15, installed **project-locally** in `tools\` by `scripts/toolchain`: Temurin JDK 21.0.12.1+1 and Maven 3.9.16 (see `software-list.md`) | Verified | `Get-Command`, directory scan; `java -version`, `mvn -v` from `tools\` |
| PostgreSQL | 18.6, Windows service `postgresql-x64-18`, binaries in `S:\Program Files\PostgreSQL\18\bin` (`initdb`, `pg_ctl`, `psql` present) | Verified | `psql --version`, service query |
| Docker | Docker Desktop installed and running | Verified | service `com.docker.service` |
| Listening ports that matter to pgweb (2026-10-19) | PostgreSQL service on **5432**, all interfaces. Docker Desktop and the WSL relay on **8080** over IPv6, which conflicts with pgweb's test-mode default port. Details: [`ports-and-services.md`](ports-and-services.md) | Verified | `Get-NetTCPConnection -State Listen` |
| Microsoft Edge | 155.0.4283.45 | Verified | `msedge.exe` file version |
| GitLab Runner, SonarScanner | Not on PATH | Verified | `Get-Command` |
| Network access | Not checked; assumed **not** air-gapped (a development machine) | Assumed | — |

**Consequences for Phase 1:**
- **S1 (Waffle) can't run here.** It needs a domain-joined Windows host and a domain test account.
- **S4 (offline toolchain) can't be proven here.** It needs a clean, network-disabled VM.
- **S2, S3 and S5 could run here** after a JDK is installed through the approved intake process. They should use PostgreSQL binaries of the **same major version as the target servers**. Local PostgreSQL 18 is only valid if the targets are also 18 (see §2).
- Docker is available here, but **must not become a project dependency** (plan F7, D6).

---

## 2. Target PostgreSQL instances

| Item | Internal (5433) | External (5432) | Status |
|---|---|---|---|
| Host OS / server version | | | Unresolved |
| PostgreSQL version (`SELECT version();`) | | | Unresolved |
| Installed via EDB Windows installer | Stated | Stated | Stated |
| `pg_hba.conf` auth methods in use (`SELECT type, database, user_name, auth_method FROM pg_hba_file_rules;`, superuser or `pg_read_all_settings`) | | | Unresolved |
| Do user accounts have passwords (SCRAM/md5), or are they SSPI/GSS only? | | | Unresolved (decides option I-A) |
| TLS enabled (`SHOW ssl;`), certificate issuer | | | Unresolved |
| `max_connections`, `superuser_reserved_connections` | | | Unresolved |
| Typical per-role `rolconnlimit` (`SELECT rolname, rolconnlimit FROM pg_roles WHERE rolcanlogin;`, counts only) | | | Unresolved |
| Number of login roles that are superusers (`SELECT count(*) FROM pg_roles WHERE rolsuper AND rolcanlogin;`) | | | Unresolved |
| Role naming convention (e.g. `jsmith`, `jsmith@DOMAIN`, `DOMAIN\jsmith`) | | n/a | Unresolved |
| `client_connection_check_interval` (PG 14+), role-level `statement_timeout` defaults | | | Unresolved |
| Databases users need (allow-list candidates) | | | Unresolved |
| Installed extensions with escape potential (`dblink`, `postgres_fdw`, untrusted PLs): `SELECT extname FROM pg_extension;` | | | Unresolved |
| Who administers the instance | | | Unresolved |

## 3. Identity and Windows authentication

| Item | Value | Status |
|---|---|---|
| AD domain name(s) whose users may sign in | | Unresolved |
| Where `DataAdmin` is defined: AD group (name?) or the existing application's role store (table/API?) | | Unresolved (D1) |
| Existing application: language, Java version | | Unresolved (A1) |
| Existing application: framework (Spring Boot / Spring MVC / plain servlet / Tomcat valve) and version | | Unresolved (A1) |
| Waffle version and modules (`waffle-jna`, `waffle-spring-security*`, `waffle-tomcat*`) | | Unresolved (A1, A2) |
| Waffle configuration (Negotiate/NTLM providers, impersonation, principal format, role prefix) | | Unresolved |
| Application server and HTTPS setup for the existing app | | Unresolved |
| Service account type (gMSA / domain account) and whether an `HTTP/<host>` SPN can be registered | | Unresolved (A3) |
| Kerberos constrained delegation permitted? (only if option I-B is pursued) | | Unresolved |
| Browser GPO: intranet zone / `AuthServerAllowlist` for the app host | | Unresolved |
| Managed browser versions (Edge, Chrome) on user desktops | | Unresolved |

## 4. Build, CI and analysis infrastructure

| Item | Value | Status |
|---|---|---|
| GitLab edition and version (self-managed?) | | Unresolved |
| GitLab Runner version; can it be installed and updated offline? | | Unresolved (A14) |
| Windows runner available? Executor (shell/PowerShell)? Domain-joined? | | Unresolved (A8) |
| Runner service account: may it run `initdb`/`pg_ctl` and write to a temp directory? | | Unresolved (S5) |
| Edge installed on the runner, version | | Unresolved (S6) |
| SonarQube version and edition (Community Build / Developer / Enterprise) | | Unresolved (A9, D8) |
| SonarQube: installed Java and JavaScript analysers, scanner JRE provisioning enabled, GitLab integration configured | | Unresolved |
| Internal artifact repository (Nexus/Artifactory) with Maven and npm proxies or hosted repos? | | Unresolved (A12) |
| Approved process for transferring software into the air gap (media, malware scanning, approvers) | | Unresolved |
| Organisational standards: JDK distribution and version, Node.js version, service wrapper, secret store | | Unresolved (D7) |

## 5. Deployment target

| Item | Value | Status |
|---|---|---|
| Windows Server version for the app host | | Unresolved |
| Can the app host reach both PostgreSQL instances (firewall rules for 5432/5433)? | | Unresolved |
| Internal CA available for the HTTPS certificate | | Unresolved |
| Where the audit DB may be hosted (separate cluster/port permitted?) | **Decided:** a separate cluster/port, not administered by DataAdmin members (stakeholder, 2026-10-15; `audit-policy.md` §8, D4). Host and port still to be supplied by the DBA. | Stated (decision); host and port Unresolved |
| Monitoring or alerting system available for audit-failure alerts (Windows Event Log forwarding, SIEM) | | Unresolved |

## 6. Audit and governance

| Item | Value | Status |
|---|---|---|
| Required audit retention (online / archived) and its legal or policy basis | **Decided:** at least 12 months online, 7 years archived (stakeholder, 2026-10-15; `audit-policy.md` §8). Purging the online audit database is prohibited until P12-3 is done and approved (`operations/runbooks.md` §11). The legal or policy basis is not recorded. | Stated (retention); basis Unresolved (D13) |
| Named auditor group or accounts | **Decided:** a named auditor group, through `pgweb_audit_reader` (stakeholder, 2026-10-15; `audit-policy.md` §8). The group's name is still to be supplied. | Stated (decision); group name Unresolved |
| Is storing normalised SQL text (literals removed) acceptable? Are identifiers (table and column names) acceptable? | **Decided:** normalised text plus HMAC, raw text off; identifiers stored (stakeholder, 2026-10-15; `audit-policy.md` §8, D4) | Stated |
| Fail-closed or fail-open when audit can't be written | **Decided:** fail-closed, both profiles (stakeholder, 2026-10-15; `audit-policy.md` §8, D4) | Stated |
