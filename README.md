# PGAdmin (pgweb): browser-based PostgreSQL query interface

An internal web application, similar to DBeaver for everyday workflows, that lets Windows-domain users run SQL against the PostgreSQL databases they are allowed to use. Authorisation, limits and auditing are enforced on the server. Built for **air-gapped Windows Server and Windows 11**.

- **Sign-in:** Windows integrated authentication (Waffle; Kerberos in production).
- **Database access:** as the user's own PostgreSQL role, through Kerberos constrained delegation. **No database passwords anywhere** (ADR-0002).
- **Internal PostgreSQL (5433):** members of the DataAdmin AD group only.
- **External PostgreSQL (5432):** every user, limited to databases they own or that are shared with them (ADR-0003).

> **Status:** see [Project status](#project-status).

## Documentation

| Topic | Document |
|---|---|
| Implementation plan (architecture, phases, decision register) | [`docs/implementation-plan-v2.md`](docs/implementation-plan-v2.md). Where it differs from an Accepted ADR, the ADR wins. |
| Architecture decisions (all Accepted 2026-10-15) | [`docs/adr/`](docs/adr/README.md) |
| Decision log (stakeholder instructions) | [`docs/decision-log.md`](docs/decision-log.md) |
| What still has to be proven in the real environment | [`docs/target-environment-checklist.md`](docs/target-environment-checklist.md) |
| Phase status | [`docs/phase-1-status.md`](docs/phase-1-status.md), [`docs/phase-2-status.md`](docs/phase-2-status.md), [`docs/phase-3-status.md`](docs/phase-3-status.md), [`docs/phase-4-status.md`](docs/phase-4-status.md), [`docs/phase-5-status.md`](docs/phase-5-status.md), [`docs/phase-6-status.md`](docs/phase-6-status.md), [`docs/phase-7-status.md`](docs/phase-7-status.md), [`docs/phase-8-status.md`](docs/phase-8-status.md), [`docs/phase-9-status.md`](docs/phase-9-status.md), [`docs/phase-10-status.md`](docs/phase-10-status.md), [`docs/phase-11-status.md`](docs/phase-11-status.md), [`docs/phase-12-status.md`](docs/phase-12-status.md); administration workspace: [`docs/admin-phase-1-status.md`](docs/admin-phase-1-status.md), [`docs/admin-phase-2-status.md`](docs/admin-phase-2-status.md), [`docs/admin-phase-3-status.md`](docs/admin-phase-3-status.md), [`docs/admin-phase-4-status.md`](docs/admin-phase-4-status.md) (with the decisions made under the delegation of 2026-10-20), [`docs/admin-phase-5-status.md`](docs/admin-phase-5-status.md), [`docs/admin-phase-6-status.md`](docs/admin-phase-6-status.md), [`docs/admin-phase-7-status.md`](docs/admin-phase-7-status.md), [`docs/admin-phase-8-status.md`](docs/admin-phase-8-status.md), [`docs/admin-phase-9-status.md`](docs/admin-phase-9-status.md), [`docs/admin-phase-10-status.md`](docs/admin-phase-10-status.md), [`docs/admin-phase-11-status.md`](docs/admin-phase-11-status.md), [`docs/admin-phase-12-status.md`](docs/admin-phase-12-status.md), [`docs/admin-phase-13-status.md`](docs/admin-phase-13-status.md), [`docs/admin-phase-14-status.md`](docs/admin-phase-14-status.md) |
| **Deployment** on Windows Server (AD, SPN, delegation, TLS, PostgreSQL, install, checks) | [`docs/operations/deployment-guide.md`](docs/operations/deployment-guide.md) |
| **Runbooks**: upgrade, rollback, kill switch, rotation, DataAdmin, audit, spool, outage, partitions | [`docs/operations/runbooks.md`](docs/operations/runbooks.md) |
| **User guide**: supported SQL behaviour and limitations | [`docs/operations/user-guide.md`](docs/operations/user-guide.md) |
| SQL execution behaviour (gate, limits, timeout, cancel, outcomes, audit) | [`docs/sql-execution-behaviour.md`](docs/sql-execution-behaviour.md) |
| Developer setup: build, local audit DB, run, manual verification environment, configuration reference | [`docs/dev-setup.md`](docs/dev-setup.md) |
| **Ports and services** at a glance (production and workstation) | [`docs/ports-and-services.md`](docs/ports-and-services.md) |
| Requirements, threat model, audit policy | [`docs/requirements.md`](docs/requirements.md), [`docs/threat-model.md`](docs/threat-model.md), [`docs/audit-policy.md`](docs/audit-policy.md) |
| Environment inventory, compatibility matrix | [`docs/environment-inventory.md`](docs/environment-inventory.md), [`docs/compatibility-matrix.md`](docs/compatibility-matrix.md) |
| Offline dependency intake | [`offline/README.md`](offline/README.md) |
| How to contribute (branches, merge rules, test loop) | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| Installed software (generated) | [`software-list.md`](software-list.md), [`configuration.properties`](configuration.properties) |

## Repository layout

```
deploy/sql/           audit database bootstrap and privilege verification for DBAs
deploy/windows/       install, upgrade, rollback, uninstall scripts; service and configuration templates
backend/              Java 21 service: plain Jakarta Servlet + Waffle on embedded Tomcat 11, pgJDBC (Maven)
frontend/             React single-page app (Vite, Vitest), built into the backend JAR
offline/              air-gap intake: Maven settings, dependency and release bundles, SHA-256 manifests
scripts/ci/           CI job logic shared by .gitlab-ci.yml and local runs; pipeline gate tests
scripts/test/         randomised 5x test loop, E2E runner, deployment rehearsal
scripts/dev-db.ps1    local development audit database
scripts/sonar/        project-local SonarQube start/stop and quality-gate setup
scripts/toolchain/    install / uninstall / activate project-local software, plus tests
toolchain/            software manifest (software.json) and pinned versions (software.lock.json)
docs/                 plan, ADRs, requirements, threat model, audit policy, evidence/ (test-loop reports)
spikes/               feasibility spikes with their evidence reports (never built into the app)
tools/                project-local software and caches (NOT committed; created by the install script)
.gitlab-ci.yml        GitLab pipeline (Windows runner, PowerShell, offline)
```

## Setting up a development machine (Windows 11 or Windows Server)

Everything the project installs goes into `tools\` inside this folder:
- Temurin JDK 21 and Maven 3.9;
- Zig 0.17, a C compiler used only to build the SQL parser DLL;
- the libpg_query 18.1.0 source;
- SonarQube Community Build 26.9.

The dependency caches also live there: `tools\m2` (Maven), `tools\npm-cache` (npm) and `tools\sonar-user-home` (SonarScanner). Nothing is installed system-wide, and nothing changes the registry or the permanent PATH. The exact list is in `software-list.md`.

### Prerequisites

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server 2016+ (x64) | |
| Windows PowerShell 5.1 | Built in |
| Git for Windows | To clone the repository |
| Node.js 24 LTS | Build and test only; **not** needed on production servers |
| PostgreSQL 18 (EDB installer) | Same major version as the production servers. Only its `bin` folder is used, for throwaway test clusters; the installed service is never touched. |
| Microsoft Edge | End-to-end tests (Playwright `msedge` channel; no browser download) |

### 1. Install the project toolchain

**Machine with internet access:**

```powershell
powershell -ExecutionPolicy Bypass -File scripts\toolchain\Install-Toolchain.ps1
```

This downloads the pinned archives (versions and checksums in `toolchain\software.lock.json`), verifies them, extracts them into `tools\`, checks that they run, and regenerates `configuration.properties` and `software-list.md`.

**Air-gapped machine:** follow [`offline/README.md`](offline/README.md). In short, build a dependency bundle on a connected machine with `offline\New-DependencyBundle.ps1`, transfer it, then run `offline\Import-DependencyBundle.ps1 -Bundle <path>`. The import verifies every file's SHA-256 and installs the toolchain with `-Offline`.

### 2. Use the tools in a PowerShell session

```powershell
. .\scripts\toolchain\Use-Toolchain.ps1
java -version
mvn -v
```

This sets the following **for the current session only**:
- `JAVA_HOME` and `MAVEN_HOME`;
- `MAVEN_ARGS`, which points the Maven repository at `tools\m2`;
- `npm_config_cache` (`tools\npm-cache`) and `SONAR_USER_HOME` (`tools\sonar-user-home`);
- the Playwright variables;
- `PATH`.

### 3. Build and test

```powershell
# Frontend (first time on a connected machine: npm ci; afterwards: npm ci --offline)
cd frontend; npm ci --offline; npm run lint; npm run test:coverage; npm run build; cd ..
# Backend: Enforcer, -Werror compile, unit + integration tests, JaCoCo thresholds
cd backend; mvn -B -o -s ..\offline\maven-settings.xml verify; cd ..
# The whole pipeline, as GitLab runs it (all jobs offline)
powershell -ExecutionPolicy Bypass -File scripts\ci\Invoke-LocalPipeline.ps1 -Package
```

The package job writes `release\pgweb-<version>.zip`. It contains the backend JAR with the SPA inside, the runtime libraries, CycloneDX SBOMs and `SHA256SUMS.txt`.

### 4. SonarQube (local)

```powershell
. .\scripts\sonar\Start-LocalSonar.ps1        # starts tools\sonarqube-*, applies the "pgweb gate", sets SONAR_TOKEN
powershell -ExecutionPolicy Bypass -File scripts\ci\Invoke-LocalPipeline.ps1 -Sonar
powershell -ExecutionPolicy Bypass -File scripts\sonar\Stop-LocalSonar.ps1
```

- On first start, the admin password is replaced with a random one. It is stored, with the analysis token, in `tools\sonar-local.json`, which is never committed.
- The dashboard is at http://localhost:9000.
- In CI, the organisation's SonarQube is used instead, through the `SONAR_HOST_URL` and `SONAR_TOKEN` variables.

### 5. Remove what the project installed

```powershell
powershell -ExecutionPolicy Bypass -File scripts\toolchain\Uninstall-Toolchain.ps1 -Name sonarqube   # one package
powershell -ExecutionPolicy Bypass -File scripts\toolchain\Uninstall-Toolchain.ps1 -All -Purge       # everything (deletes tools\)
```

Uninstall only ever deletes inside `tools\`, and refuses any recorded path that points elsewhere.

### Running the application, database and Windows service

See [`docs/dev-setup.md`](docs/dev-setup.md): a local audit database (`scripts\dev-db.ps1 start`), the `serve`, `check-config`, `migrate` and `verify-audit-chain` commands, and the full `pgweb.properties` reference.

To deploy on a server, build the release bundle (`offline\New-ReleaseBundle.ps1`, or the GitLab `package` job) and follow [`docs/operations/deployment-guide.md`](docs/operations/deployment-guide.md). The bundle carries its own Java runtime and the service wrapper, so the server needs no other software.

## Testing

Every phase's suites must pass **at least 5 consecutive runs**, each with fresh random seeds. Any failure, and any code change, resets the count to 0. Reports record the seeds, so any run can be replayed.

| Suite | Command | Evidence |
|---|---|---|
| Application test loop (frontend lint + Vitest; backend unit + integration + coverage) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test\Invoke-TestLoop.ps1 -Tag <phase>` | `docs/evidence/<phase>/loop-*.json` |
| End-to-end tests in Edge against a real environment (see [`docs/dev-setup.md`](docs/dev-setup.md) §6) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test\Invoke-E2E.ps1 -Tag <phase> -Repeat 5` | `docs/evidence/<phase>/e2e-*.json` |
| Pipeline gates fail when faults are injected | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\tests\Test-PipelineGates.ps1` | `docs/evidence/phase-2/gates-*.json` |
| Deployment rehearsal: build two releases, install → use in Edge → upgrade → tampered bundle refused → rollback → uninstall, on a throwaway environment | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test\Invoke-DeploymentRehearsal.ps1 -Tag <phase>` | `docs/evidence/<phase>/rehearsal-*.json` |
| Offline intake, end to end | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\tests\Test-OfflineIntake.ps1` | `docs/evidence/phase-2/intake-*.json` |
| SonarQube gate status as evidence (run the `sonar-backend` and `sonar-frontend` jobs first: the export refuses a gate whose analysis doesn't cover the current sources) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sonar\Export-SonarGate.ps1 -Tag <phase>` | `docs/evidence/<phase>/sonar-*.json` |
| Toolchain scripts | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\toolchain\tests\Test-Toolchain.ps1` | `scripts/toolchain/tests/results/` |
| Spikes S1–S7 | `spikes\<spike>\Invoke-*.ps1` | `spikes/<spike>/REPORT.md` |

## Project status

| Phase | State |
|---|---|
| 0: Discovery and decisions | Done. All ADRs Accepted 2026-10-15 ([`docs/decision-log.md`](docs/decision-log.md)). Environment facts still to be collected are in [`docs/target-environment-checklist.md`](docs/target-environment-checklist.md). |
| 1: Feasibility spikes | Done on the workstation (S1–S3, S5–S7); Kerberos, the domain, the runner and a clean VM remain target-environment items ([`docs/phase-1-status.md`](docs/phase-1-status.md)). |
| 2: Offline baseline, scaffolding, CI, SonarQube | **Done** on the workstation: loop 5/5, gate checks, intake exercise and both Sonar gates passed. A clean VM and the GitLab runner remain target-environment items ([`docs/phase-2-status.md`](docs/phase-2-status.md)). |
| 3: Backend foundation | **Done** on the workstation: configuration, secrets, redacted logging, error envelope, audit schema with hash chain, CLI, test-cluster guard ([`docs/phase-3-status.md`](docs/phase-3-status.md)) |
| 4: Sign-in, authorisation, sessions, CSRF, audit service | **Done** on the workstation (NTLM; Kerberos is a target-environment item) ([`docs/phase-4-status.md`](docs/phase-4-status.md)) |
| 5: Frontend shell, landing page, components | **Done** ([`docs/phase-5-status.md`](docs/phase-5-status.md)) |
| 6: Database connections and sessions (Kerberos delegation, no passwords) | **Done** on the workstation; cross-host delegation is a target-environment item ([`docs/phase-6-status.md`](docs/phase-6-status.md)) |
| 7: SQL execution engine (API only; off by default) | **Done** on the workstation: single-statement gate on PostgreSQL's own parser, limits, timeout, cancel and escalation, fail-closed audit ([`docs/phase-7-status.md`](docs/phase-7-status.md)) |
| 8: SQL editor and results UI | **Done** on the workstation: CodeMirror editor (strict CSP), result grid, cancel, lock-wait indicator; first end-to-end suite in Edge against a real environment ([`docs/phase-8-status.md`](docs/phase-8-status.md)) |
| 9: Transactions, isolation and concurrency | **Done** on the workstation: transaction controls, failed-transaction lockout, warnings before limits, destructive-statement confirmations (not a security control), real-PostgreSQL concurrency suite run 20 times in a row ([`docs/phase-9-status.md`](docs/phase-9-status.md)) |
| 10: Metadata browser | **Done** on the workstation: privilege-filtered object tree on the companion connection, parameterised catalog SQL only, quoted-name insert ([`docs/phase-10-status.md`](docs/phase-10-status.md)) |
| 11: Security hardening, E2E and security suite | **Done** on the workstation, with one open decision (a proposed dependency exception) and the clean-VM rehearsal left for the target environment ([`docs/phase-11-status.md`](docs/phase-11-status.md)) |
| 12: Deployment readiness and documentation | **Done** on the workstation: release bundle with its own Java runtime and service wrapper, install/upgrade/rollback/uninstall scripts, deployment rehearsal, deployment guide, runbooks, user guide. Not yet: deployment by someone else from the docs, service mode on a server, stakeholder sign-off, MVP tag ([`docs/phase-12-status.md`](docs/phase-12-status.md)) |
