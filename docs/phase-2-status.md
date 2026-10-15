# Phase 2 Status: Offline Baseline, Scaffolding, CI, SonarQube

> Updated 2026-10-15. Evidence: `docs/evidence/phase-2/`.
> All results are from the developer workstation: Windows 11 Pro, not domain-joined, with network access. Every tool was forced offline for the checks. **A clean, network-disabled VM and the GitLab runner are still needed** (`docs/target-environment-checklist.md` C1–C5).

## Delivered

| Item | Where |
|---|---|
| Backend skeleton: Java 21, plain Servlet stack, exact pins, Enforcer (Java/Maven versions, plugin versions, convergence, banned Spring/Vert.x/`javax.servlet`), `-Xlint:all -Werror`, Surefire + Failsafe in random order, JaCoCo merged coverage with plan §8.2 thresholds (security-critical packages 90/85), CycloneDX SBOM, executable JAR with `lib/` | `backend/pom.xml` |
| Frontend skeleton: React 19, Vite 8, Vitest 5 (jsdom, shuffled, seeded), coverage thresholds per plan §8.2, ESLint 10 with bans (`dangerouslySetInnerHTML`, `innerHTML`, browser storage, `eval`), external-URL check on the build output, CycloneDX SBOM | `frontend/` |
| GitLab pipeline with every stage (E2E placeholder until P11), no remote includes, no Docker, no SaaS | `.gitlab-ci.yml` |
| One script per CI job, also runnable locally | `scripts/ci/Invoke-CiJob.ps1`, `scripts/ci/Invoke-LocalPipeline.ps1` |
| Air-gap Maven settings (offline, mirror to a closed port, `checksumPolicy=fail`); npm registry forced to a closed port | `offline/maven-settings.xml`, `scripts/ci/CiCommon.ps1` |
| Intake scripts: dependency bundle with SHA-256 manifest, verified import, release bundle | `offline/` |
| SonarQube Community Build 26.9, project-local; "pgweb gate" (13 conditions) as default; both projects | `scripts/sonar/` |
| Randomised 5× test loop for every later phase | `scripts/test/Invoke-TestLoop.ps1` |
| Contribution rules | `CONTRIBUTING.md` |

## Pinned versions (all published on or before 2026-10-01)

**Backend:**
- **Runtime:** Tomcat 11.0.26, Waffle 3.7.0, JNA 5.19.1, pgJDBC 42.7.13 (42.7.14 is under two weeks old), Jackson 2.22.3, SLF4J 2.0.20, Logback 1.6.5, Flyway 13.9.0, HikariCP 7.1.0.
- **Test:** JUnit 6.1.3, AssertJ 3.27.7, ArchUnit 1.5.1, Awaitility 4.3.0.

**Frontend:**
- **Runtime:** React 19.3.0, CodeMirror 6 (state 6.7.6, view 6.43.13, lang-sql 6.10.0), TanStack Query 5.104.0, TanStack Table 8.21.3, TanStack Virtual 3.14.13.
- **Build and test:** Vite 8.3.2, Vitest 5.0.3, ESLint 10.11.0, MSW 2.15.0, Testing Library.

**Version choices that differ from the newest release:**
- **TanStack Table 8:** v9 is a new API.
- **MSW 2:** v3 is a new API.
- **No `eslint-plugin-react`:** it doesn't support ESLint 10. Core JSX parsing plus `no-restricted-syntax` covers the bans.
- **No `vitest-axe`:** unmaintained since 2022. `axe-core` is used directly.
- **No `react-resizable-panels`:** a small in-house splitter is planned instead.

## Acceptance criteria (plan Phase 2)

| Criterion | Result | Evidence |
|---|---|---|
| A clean offline checkout passes every CI stage | **Tested (workstation):** a fresh clone with no `tools\` imported the dependency bundle, installed the toolchain offline, and passed deps-verify, toolchain-check, lint, tests and package. **Untested in target environment:** a clean network-disabled VM, and the GitLab runner. | `intake-*.json` |
| `deps-verify` fails if a network fetch is attempted (induced) | **Tested:** an empty npm cache gives `ENOTCACHED`; an empty Maven repository fails offline, with 0 jars fetched | `gates-*.json` G1, G2 |
| A lint error and a coverage drop each fail the pipeline (verified once, then reverted) | **Tested:** `dangerouslySetInnerHTML` fails ESLint; an untested class fails JaCoCo; an untested module fails Vitest | `gates-*.json` G3–G5 |
| Both Sonar gates green, with coverage visible | **Tested** with the local Community Build 26.9 | Sonar runs below |
| SBOMs and the SHA-256 manifest are produced | **Tested:** `pgweb-backend-sbom.json`, `pgweb-frontend-sbom.json`, `SHA256SUMS.txt` in the release bundle; the manifest detects modified, missing and extra files | `gates-*.json` G6 |
| The intake procedure is documented and exercised once | **Tested (workstation):** `offline/README.md`; the bundle was built, verified and imported | `intake-*.json` |

## Test evidence

| Check | Result | File |
|---|---|---|
| Randomised loop: frontend lint + Vitest with coverage; backend Enforcer, `-Werror`, tests, JaCoCo | **PASS 5/5**, master seed `392750411` | `docs/evidence/phase-2/loop-20261015-221900.json` |
| Pipeline gates fail on injected faults (G1–G7) | **PASS** (first run, then rerun after the settings change) | `gates-20261015-215959.json`, `gates-20261015-221828.json` |
| Intake end to end in a fresh clone | FAIL (P2-5), then **PASS** after the fix | `intake-20261015-221145.json`, `intake-20261015-221705.json` |
| Local pipeline, all 10 jobs including both Sonar analyses and package | **PASS** (seed `533964708`) | `ci-logs` (not committed) |
| SonarQube "pgweb gate" | **OK** for both projects; coverage 100% (skeleton code); 0 blocker/high issues | `sonar-20261015-222251.json` |

The Phase 2 code is a skeleton. The loop's 3 frontend and 3 backend tests check the build and gates themselves; the feature test suites start in Phase 3.

## Findings

| # | Finding | Impact |
|---|---|---|
| P2-1 | Maven's enhanced local repository records the source repository id of each artifact. With a mirror id other than `central`, cached artifacts are "present, but unavailable" offline. | The air-gap mirror id must be `central` (or the bundle must be built with the same mirror id) |
| P2-2 | `dependency:go-offline` and `resolve-plugins` ask for default-lifecycle plugins that the build doesn't use (`maven-release-plugin`) | `deps-verify` resolves dependencies, then runs an offline `package`, which exercises exactly the plugins the build binds |
| P2-3 | SonarScanner caches its engine and JRE in `%USERPROFILE%\.sonar` by default | `SONAR_USER_HOME` now points at `tools\sonar-user-home` (a toolchain cache) and is part of the dependency bundle. The scanner gets its JRE from the SonarQube server ("JRE provisioning"), which works in the air gap. |
| P2-4 | npm 11 skips package install scripts unless approved | None of ours are needed; documented in `offline/README.md` |
| P2-5 | **CycloneDX `makeBom` declares "requires online" and silently skips under `-o`.** In the working copy, an SBOM left over from the first online build hid this. The first intake exercise in a fresh clone failed at `package` (`intake-20261015-221145.json`). | `maven-settings.xml` no longer sets `<offline>` (CI jobs pass `-o`; the mirror still blocks every download). The package job deletes old SBOMs, runs `makeBom` without `-o`, and fails if either SBOM is missing. The second intake exercise passed (`intake-20261015-221705.json`). |
