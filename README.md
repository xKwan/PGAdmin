# PGAdmin — Browser-based PostgreSQL query interface

An internal web application, similar to DBeaver for everyday workflows, that lets Windows-authenticated users run SQL against authorised PostgreSQL databases with server-side authorisation, limits and auditing. Built for **air-gapped Windows Server and Windows 11** environments.

> **Status:** planning and Phase 0/1 foundations. The application itself is not built yet. See [Project status](#project-status).

## Documentation

| Topic | Document |
|---|---|
| Implementation plan (architecture, phases, decision register) | [`docs/implementation-plan-v2.md`](docs/implementation-plan-v2.md) |
| Architecture decisions | [`docs/adr/`](docs/adr/README.md) |
| Decision log (stakeholder instructions) | [`docs/decision-log.md`](docs/decision-log.md) |
| Requirements (MVP vs deferred) | [`docs/requirements.md`](docs/requirements.md) |
| Threat model | [`docs/threat-model.md`](docs/threat-model.md) |
| SQL audit policy | [`docs/audit-policy.md`](docs/audit-policy.md) |
| Environment inventory | [`docs/environment-inventory.md`](docs/environment-inventory.md) |
| Compatibility matrix | [`docs/compatibility-matrix.md`](docs/compatibility-matrix.md) |
| Installed software (generated) | [`software-list.md`](software-list.md), [`configuration.properties`](configuration.properties) |

## Repository layout

```
docs/                 plan, ADRs, requirements, threat model, audit policy, inventory
scripts/toolchain/    install / uninstall / activate project-local software, plus tests
toolchain/            software manifest (software.json) and pinned versions (software.lock.json)
spikes/               feasibility spikes with their evidence reports
tools/                project-local software (NOT committed; created by the install script)
configuration.properties   generated: where the project's software is installed
software-list.md           generated: what is installed, versions, purpose, checksums
```

## Setting up a development machine (Windows 11 or Windows Server)

Everything the project installs goes into `tools\` inside this folder. It currently installs Temurin JDK 21, Maven 3.9, Zig 0.17 (a C compiler, used only to build the SQL parser DLL) and the libpg_query 18.1.0 source. The exact list is in `software-list.md`. Nothing is installed system-wide, and nothing changes the registry or the permanent PATH.

### Prerequisites

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server 2016+ (x64) | |
| Windows PowerShell 5.1 | Built in |
| Git for Windows | To clone the repository |
| PostgreSQL (EDB installer) | Same major version as the production servers. Only its `bin` folder is needed for tests; the running service is never modified. |

### 1. Install the project toolchain

**Machine with internet access:**

```powershell
powershell -ExecutionPolicy Bypass -File scripts\toolchain\Install-Toolchain.ps1
```

This downloads the pinned JDK and Maven (versions in `toolchain\software.lock.json`), verifies their checksums, extracts them into `tools\`, checks that they run, and regenerates `configuration.properties` and `software-list.md`.

**Air-gapped machine:**

1. On a connected machine, run the install above once.
2. Copy the repository **and** the `tools\downloads\` folder to the air-gapped machine (through your approved transfer process).
3. Run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\toolchain\Install-Toolchain.ps1 -Offline
   ```
   Offline mode never uses the network. It fails clearly if an archive is missing or its checksum doesn't match the lock file.

### 2. Use the tools in a PowerShell session

```powershell
. .\scripts\toolchain\Use-Toolchain.ps1
java -version
mvn -v
```

This sets `JAVA_HOME`, `MAVEN_HOME`, `MAVEN_ARGS` (Maven repository inside `tools\m2`) and `PATH` **for the current session only**.

### 3. Remove what the project installed

```powershell
# One package
powershell -ExecutionPolicy Bypass -File scripts\toolchain\Uninstall-Toolchain.ps1 -Name maven
# Everything, including downloads and the Maven repository (deletes tools\)
powershell -ExecutionPolicy Bypass -File scripts\toolchain\Uninstall-Toolchain.ps1 -All -Purge
```

Uninstall only ever deletes inside `tools\`, and refuses any recorded path that points elsewhere.

### Backend, frontend and database setup

These sections will be added as each phase is completed: the Spring Boot backend, the React frontend, the audit database, and Windows service deployment.

## Testing

Every component is tested with randomised scenarios and must pass **at least 5 consecutive runs**. Any failure resets the count to 0. Reports record the seeds, so any run can be replayed.

| Suite | Command | Evidence |
|---|---|---|
| Toolchain scripts | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\toolchain\tests\Test-Toolchain.ps1` | `scripts/toolchain/tests/results/` |
| Spike S5: throwaway PostgreSQL clusters, concurrency and cancellation | `powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s5-ephemeral-pg\Invoke-S5Spike.ps1` | [`spikes/s5-ephemeral-pg/REPORT.md`](spikes/s5-ephemeral-pg/REPORT.md) |
| Spike S1 (partial): Waffle Negotiate/NTLM with Spring Boot 4.1 and 3.5 | `powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s1-waffle\Invoke-S1Spike.ps1` | [`spikes/s1-waffle/REPORT.md`](spikes/s1-waffle/REPORT.md) |
| Spike S2: libpg_query parser built offline with Zig, checked against the server | `powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s2-parser\Invoke-S2Spike.ps1` | [`spikes/s2-parser/REPORT.md`](spikes/s2-parser/REPORT.md) |
| Spike S3: driver protocol (pgJDBC vs Vert.x), offline Maven | `powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s3-driver\Invoke-S3Spike.ps1` | [`spikes/s3-driver/REPORT.md`](spikes/s3-driver/REPORT.md) |

## Project status

| Phase | State |
|---|---|
| 0: Discovery and decisions | Drafts complete. Awaiting stakeholder decisions and environment facts ([`docs/phase-0-status.md`](docs/phase-0-status.md)). |
| 1: Feasibility spikes | Toolchain automation done. S2, S3 and S5 passed on the workstation with PostgreSQL 18; target PostgreSQL versions, the runner and Windows Server are still pending. S1 partly passed: Waffle works with Spring Boot 4.1/Security 7 and 3.5/Security 6 using NTLM with a local account; Kerberos and domain groups need a domain-joined host. Driver decision drafted (ADR-0009, Proposed). New risk T31 (native parser in the backend's process) needs a decision. S4 and S6 not started. |
| 2–12 | Not started |
