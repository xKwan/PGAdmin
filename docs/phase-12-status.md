# Phase 12 Status: Deployment Readiness and Documentation

> Updated 2026-10-16. Evidence: `docs/evidence/phase-12/`. Workstation only: Windows 11, no domain, Edge, PostgreSQL 18.6, NTLM loopback. The PowerShell session here is **not elevated**, so pgweb was rehearsed as a process (`-NoService`), not as a Windows service.
>
> **The MVP is not tagged.** Two acceptance criteria can't be met by the author on a workstation: someone else deploying from the docs alone, and the stakeholder's sign-off. The release tag waits for both.

## Delivered

| Item | Where |
|---|---|
| Release bundle: `pgweb.jar` (SPA inside), a Java 21 runtime made with `jlink` (servers need no Java install), the service wrapper (WinSW 2.12.0 as `service\pgweb-service.exe`), the parser DLL, deploy scripts, templates, SQL scripts, SBOMs, `VERSION`, `SHA256SUMS.txt`; about 56 MB | `offline/New-ReleaseBundle.ps1` (`-VersionLabel` builds the same code under another release name, for rehearsals) |
| Install: verifies the zip and every file (refuses changed, missing, unlisted files and paths escaping the folder), side-by-side `releases\<version>`, data folders with an ACL for SYSTEM, Administrators and the service account only, `check-config` → `migrate` → `verify-audit-chain`, the `current` junction, the WinSW service, smoke test, deploy history | `deploy/windows/Install-Pgweb.ps1`, `PgwebDeploy.psm1` |
| Upgrade: everything that can fail is checked before the stop (bundle, new release vs. existing config, chain, DBA's backup confirmation); graceful stop closes every database session; expand-only migration; **automatic rollback** if the new release doesn't come up | `deploy/windows/Update-Pgweb.ps1` |
| Rollback (to the previous or a named release; audit schema and data untouched), uninstall (keeps data unless asked; refuses to delete an unreplayed spool) | `Rollback-Pgweb.ps1`, `Uninstall-Pgweb.ps1` |
| DPAPI secrets for the configuration (`LocalMachine` or `CurrentUser`), new random HMAC key | `deploy/windows/Protect-PgwebSecret.ps1` |
| Templates: service configuration (delayed automatic start, restart on failure, Ctrl+C stop with 30 s timeout, heap dumps off, rolling wrapper logs); production configuration (Kerberos only, TLS, `verify-full`, execution **off**) | `pgweb-service.xml.template`, `pgweb.properties.template` |
| The filled-in template is a valid production configuration; an unfilled one is refused | `ConfigTemplateTest` |
| A restart never restores app sessions (the runbooks rely on it to make a DataAdmin removal take effect at once) | `WebServerSessionTest` |
| Deployment rehearsal: two releases built; throwaway PostgreSQL with the shipped bootstrap and SSPI; DPAPI configuration; install; use in Edge with an uncommitted change left open; `pg_dump`; upgrade; use; tampered bundle; rollback; uninstall. Checks at each step: audit rows never decrease, chain intact, ACLs, graceful shutdown rolled back the open transaction and audited the disconnect | `scripts/test/Invoke-DeploymentRehearsal.ps1`, `e2e/rehearsal-use.mjs` |
| CI: `smoke:package` runs the rehearsal on the bundle; `deploy:test` installs or upgrades the test server from protected CI variables | `.gitlab-ci.yml` |
| WinSW 2.12.0 in the pinned toolchain; the toolchain now handles single-file packages (new test scenario) | `toolchain/software.json`, `scripts/toolchain/Toolchain.psm1`, `Test-Toolchain.ps1` |
| **Deployment guide**: prerequisites, bundle intake, AD (gMSA, SPN, constrained delegation, DataAdmin, GPO), audit database, PostgreSQL (`pg_hba`, `pg_ident`, roles), configuration and secrets, install, checks, enabling execution after G4, monitoring, AppLocker/WDAC | [`operations/deployment-guide.md`](operations/deployment-guide.md) |
| **Runbooks**: upgrade, rollback, uninstall, kill switch, rotation (`pgweb_app`, migrator, HMAC key and vaulting, TLS, server move), profiles, DataAdmin, audit investigation and chain verification, spool recovery, audit DB outage, partition archive and purge, dependency intake, troubleshooting | [`operations/runbooks.md`](operations/runbooks.md) |
| **User guide**: sign-in, servers, sessions, supported SQL and its limits, results, transactions, confirmations, object browser, what is recorded, messages | [`operations/user-guide.md`](operations/user-guide.md) |

## Acceptance criteria (plan Phase 12)

| Criterion | Result |
|---|---|
| Packaged-service smoke test in CI | **Defined** (`smoke:package`) and run locally: rehearsal PASS. A GitLab Windows runner is target item C1. |
| Install → upgrade → rollback on a test server, audit data preserved, chain verified | **Tested on the workstation** in process mode: audit rows only grow (9 → 10 → … → 16), chain intact at every step, `pg_dump` taken before the upgrade. **On a test server as a Windows service: untested in target environment** (D1). |
| Graceful shutdown closes all PostgreSQL sessions | **Tested**: after the upgrade's stop no PostgreSQL backend of the user's role was left (checked by the rehearsal); the uncommitted row was rolled back; `db_disconnect` audited at shutdown |
| Rollback within the documented time (≤ 15 min) | **Tested**: 3.5 s in the final rehearsal (24.6 s in an earlier one); the rehearsal fails if it exceeds 900 s |
| Production config turns execution on only after sign-off | **Done**: the template ships `execution.enabled=false`; the deployment guide (§9) requires G4 sign-off first |
| Someone other than the author deploys from the docs alone | **Not met yet**: needs a second person and a server (checklist D7) |
| All gates green | **Yes, on the workstation**: loop, E2E, rehearsal, both Sonar gates, and the dependency scan (0 findings, no exception: P12-7) |
| Stakeholder sign-off; MVP tagged | **Not given yet**; no tag |

## Findings

| # | Finding | Impact |
|---|---|---|
| P12-1 | `pgweb migrate` printed `audit schema at version null` when nothing was applied (Flyway leaves the target version empty). Seen in the rehearsal log. | Prints the current version; `MainIT` checks both cases |
| P12-2 | PowerShell's `Invoke-RestMethod` and .NET `HttpClient` don't answer the fresh per-request Negotiate challenge on the request that opens a database session (ADR-0002's fresh handshake). Edge does. | The rehearsal uses Edge through Playwright, as a user would. Scripts can't open DB sessions; nothing needs them to. |
| P12-3 | The audit verifier checks the chain from seq 1, so purging old partitions (approved retention: 12 months online) would make it report a break. | **Open, post-MVP, due before the first purge (12 months after go-live):** verification from an archived anchor. The runbook says not to purge until then. |
| P12-4 | `-NewHmacKey` never shows the key, but it must be kept in a vault to check old HMACs. | Runbook procedure to copy it from the `dpapi:` value with a second person present |
| P12-5 | WinSW 2.12.0 is not Authenticode-signed and GitHub publishes no checksum for it. | Pinned by SHA-256 at first download (two downloads matched); AppLocker/WDAC hash rule in the deployment guide; checklist D6 |
| P12-6 | Install and upgrade left the expanded bundle (about 56 MB) in `%TEMP%` each time; noticed when the workstation ran short of memory and disk during the test loop. | Both scripts remove their staging folder whatever the outcome (an expanded folder passed as `-Bundle` is never touched); the rehearsal fails if one is left |
| P12-7 | The only High dependency finding, `node-forge` GHSA-86w9-cpqp-85rv (CVE-2026-85393, no fixed version up to 1.4.0), came from the npm SonarQube scanner `@sonar/scan` (build-only, never shipped; it used node-forge only to read an optional truststore, not to verify RSA signatures). The stakeholder asked to fix rather than accept it. | The frontend is analysed by the **SonarScanner for Maven** already pinned for the backend (`frontend/sonar-pom.xml`, analysis only; no new download). `@sonar/scan` and the 43 other lockfile entries only it needed (axios, adm-zip, tar-stream, node-forge, …) left the lockfile; nothing else changed in it. Same analysis: 38 files indexed, 34 excluded, 23 measured files, ncloc 1,853, coverage 96.2% (lines 99.1%, branches 93.0%), the same 13 code smells on the same lines, 0 bugs, vulnerabilities and hotspots; a failing gate still fails the job (checked with a temporary stricter gate). The dependency scan passes with 0 findings. The proposed exception in `offline/vulnerability-exceptions.json` no longer matches anything and was left for the stakeholder to remove. |

Also seen, harmless: Flyway's warning `schema audit already exists, skipping` during `migrate`.

## Implementation choices (within the approved ADRs; not stakeholder decisions)

| Choice | Why |
|---|---|
| Bundle a `jlink` runtime from the pinned JDK (D7, Java 21) | Air-gapped servers need no separate Java install or update channel; the runtime is covered by the bundle checksums and upgraded with each release |
| WinSW 2.12.0 (the latest stable release at intake; v3 was pre-release) as the service wrapper | Self-contained x64 build without a .NET install; Ctrl+C stop lets pgweb's shutdown hook close sessions |
| `-NoService` mode in the deploy scripts | For rehearsals and tests only (no elevation); documented as not for servers |
| Releases side by side with a `current` junction | Upgrade and rollback only repoint and restart; old releases stay until removed |

## Test evidence

| What | Result | File |
|---|---|---|
| Randomised loop: frontend lint + 99 Vitest tests; backend 82 unit + 135 integration tests with coverage | **PASS 5/5**, master seed `722418829` (after P12-7) | `docs/evidence/phase-12/loop-20261016-233043.json` |
| End-to-end in Edge, 5 repetitions (11 tests each) | **PASS 5/5**, master seed `1593501048` (after P12-7), about 87 s per repetition. Run with `-SkipBuild` on a JAR built from the final sources just before (SPA `index.html` in the JAR identical to a fresh build), so the run fitted the session's time limit. An earlier run passed 3 repetitions and was then stopped by Claude Code for low memory on the workstation; it counts for nothing. | `docs/evidence/phase-12/e2e-20261016-233855.json` |
| Deployment rehearsal (process mode) | **PASS**: rollback 3.5 s; no user backend after the stop; ACLs; no staging folder left | `docs/evidence/phase-12/rehearsal-20261016-181309.json` |
| Dependency scan | **PASS** after P12-7: 0 findings (Maven runtime 20 components; npm frontend and e2e), no exception used. Before the change: FAIL on `node-forge` (`vuln-scan-20261016-182235.json`) | `docs/evidence/phase-12/vuln-scan-20261016-231309.json` |
| Toolchain scripts, including the new single-file scenario | **PASS 5/5** | `scripts/toolchain/tests/results/toolchain-20261016-141332.json` |
| Text files (no control characters) | **PASS**, 412 files | lint-backend job |
| SonarQube "pgweb gate", both projects | **OK**: 0 vulnerabilities, 0 security hotspots, 0 bugs, 0 blocker/high issues; coverage backend 92.5%, frontend 96.2% | `docs/evidence/phase-12/sonar-20261016-234331.json` (frontend analysed with the Maven scanner) |

## Open items for the stakeholder and the target environment

1. **Decisions awaiting the stakeholder:** re-confirm T31 after P7-5 (intermittent native crash in the parser, mitigated by a single parser thread); confirm EXE-03 (no multi-statement scripts in the MVP); remove the obsolete proposed `node-forge` entry from `offline/vulnerability-exceptions.json` (P12-7).
2. **Gate G4 sign-off** before `execution.enabled=true` anywhere outside tests.
3. **Target environment** ([`target-environment-checklist.md`](target-environment-checklist.md)): Kerberos and delegation K1–K6, PostgreSQL P1–P7, the GitLab runner and organisation SonarQube C1–C5 (including the clean-VM offline rehearsal), the Windows service on a server D1–D7 (service mode, TLS, GPO, monitoring, AppLocker/WDAC, deployment by a second person).
4. **Post-MVP:** chain verification from an archive anchor (P12-3), cursor streaming (P9-3), multi-statement scripts (EXE-03).
