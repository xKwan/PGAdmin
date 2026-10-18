# Ports and Services

Which port belongs to which service, in production and on a developer workstation.

> Updated 2026-10-19. Workstation rows were checked with `Get-NetTCPConnection -State Listen` on the development workstation (Windows 11, no domain). Production rows come from the configuration defaults and the [deployment guide](operations/deployment-guide.md); they are **untested in the target environment** ([checklist](target-environment-checklist.md) D2, D3).

## 1. Production (target environment)

| Port | Service | Runs on | Who connects | Protocol and authentication | Set by |
|---|---|---|---|---|---|
| **8443/tcp** | **pgweb**: Windows service `pgweb` (WinSW wrapper, bundled Java runtime) | App host | Users' Edge, by the exact DNS name (for example `pgweb.corp.example`) | HTTPS with the internal CA's certificate, HSTS; Kerberos only (NTLM refused) | `server.port` (default 8443 in `prod` mode) |
| **5433/tcp** | PostgreSQL 18, **internal** | Internal DB host | pgweb, connecting **as the user** (constrained delegation); DataAdmin members only | Kerberos (`gss`/`sspi`), TLS `verify-full` when `ssl=on` | `profile.internal.port` |
| **5432/tcp** | PostgreSQL 18, **external** | External DB host | pgweb, connecting **as the user** | Kerberos (`gss`/`sspi`), TLS `verify-full` when `ssl=on` | `profile.external.port` |
| **Chosen by the DBA** (no fixed port) | PostgreSQL, **audit database** `pgweb_audit`, on a **separate cluster** (D4) | Audit DB host | pgweb as `pgweb_app` (INSERT only) and `pgweb_migrator` (install, upgrade, chain verification); auditors as `pgweb_audit_reader` | Password over TLS for the pgweb roles; Kerberos for auditors | `audit.jdbcUrl` |

**Firewall** (deployment guide §1): users → app host on 8443/tcp; app host → 5432, 5433 and the audit DB port. pgweb listens on nothing else. As a domain member the app host also talks to the domain controllers (Kerberos, including delegation); that is normal domain traffic, not a pgweb rule.

Execution is off in the shipped configuration (`execution.enabled=false`) whatever the ports; turning it on needs gate G4 (deployment guide §9).

## 2. Developer workstation

All project services bind to **localhost only**. The fixed ports below can be changed with the parameter or setting shown.

| Port | Service | Started by | Listens on | Notes |
|---|---|---|---|---|
| 5432 | PostgreSQL 18, Windows service `postgresql-x64-18` (pre-installed, **not** started by the project) | Windows, automatic | **All interfaces** (`0.0.0.0`, `::`) | Not used by pgweb or any project script. The scripts only use its binaries (`S:\Program Files\PostgreSQL\18\bin`, `configuration.properties` → `preexisting.postgresql.path`). |
| 8080 | **Docker Desktop** (`com.docker.backend`, `[::]:8080`) and the **WSL relay** (`wslrelay`, `[::1]:8080`) | Docker Desktop | IPv6 | **Conflicts with pgweb's test-mode default port**: see [section 3](#3-the-port-8080-conflict). |
| 8080 | pgweb dev instance (`serve --config .dev-db\pgweb.properties`) | You, [dev setup §3](dev-setup.md#3-run) | `127.0.0.1` | Test-mode default (`server.port`) |
| 55433 | Dev audit database cluster (`.dev-db\`) | `scripts\dev-db.ps1 start` (`-Port`) | localhost | Audit DB only, password logins: no user database sessions |
| **8090** | pgweb verification instance, **SQL execution OFF** (as shipped) | `scripts\dev\Start-VerifyEnv.ps1` (`-OffPort`) | `127.0.0.1` | [Dev setup §7](dev-setup.md#7-manual-verification-environment) |
| **8091** | pgweb verification instance, **SQL execution ON** (local test only) | `scripts\dev\Start-VerifyEnv.ps1` (`-OnPort`) | `127.0.0.1` | [Dev setup §7](dev-setup.md#7-manual-verification-environment) |
| **55434** | Verification cluster (`.verify-env\`): audit DB `pgweb_audit` and demo database `demo_db`; serves both the internal and external profile | `scripts\dev\Start-VerifyEnv.ps1` (`-PgPort`) | localhost | SSPI for your Windows account (role = lower-cased user name); password for the pgweb roles and the local superuser `pgweb_verify_super` |
| Random free ports | E2E: a PostgreSQL cluster and two pgweb instances | `scripts\test\Invoke-E2E.ps1` | localhost | Removed after the run (unless `-KeepEnvironment`) |
| Random free ports | Deployment rehearsal: a PostgreSQL cluster and pgweb (process mode) | `scripts\test\Invoke-DeploymentRehearsal.ps1` | localhost | Removed after the run |
| Random free ports | Integration-test clusters (`%TEMP%\pgweb-it-*`) | `mvn verify` | localhost | Removed after the tests |
| 9000 | Local SonarQube web server and dashboard | `scripts\sonar\Start-LocalSonar.ps1` (`-Port`) | localhost | `http://localhost:9000` |
| 9001, 9092 | SonarQube's embedded search engine and embedded database | SonarQube | localhost | SonarQube's own defaults (`sonar.search.port`, `sonar.embeddedDatabase.port`); the project doesn't change them |

## 3. The port 8080 conflict

On this workstation Docker Desktop and the WSL relay listen on port 8080 over **IPv6**, while pgweb binds `127.0.0.1:8080` (IPv4). A browser opening `http://localhost:8080` may try `::1` first and reach Docker instead of pgweb. Scripts that call `127.0.0.1` directly still reach pgweb, which hides the problem.

- Check: `Get-NetTCPConnection -State Listen -LocalPort 8080` lists every listener, IPv6 included.
- Avoid: give pgweb another port. In `.dev-db\pgweb.properties` set `server.port=8085` (or any free port) and open that port. `Start-VerifyEnv.ps1` uses 8090 and 8091, and refuses to start if a port is taken.
- Or quit Docker Desktop while working on pgweb.

## 4. How the local verification environment connects

```
Edge ── http://localhost:8090 ──► pgweb (execution OFF) ──┐
Edge ── http://localhost:8091 ──► pgweb (execution ON)  ──┤
                                                          ├─ as pgweb_app (password) ──► localhost:55434 / pgweb_audit
                                                          └─ as you (SSPI)          ──► localhost:55434 / demo_db, postgres
```

Production differs: HTTPS on 8443, Kerberos only, separate servers for internal (5433), external (5432) and the audit database, and pgweb as a Windows service.
