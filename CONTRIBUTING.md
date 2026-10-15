# Contributing

## Branches

| Branch | Use |
|---|---|
| `main` | Protected. Only fast-forward or merge-request merges after every pipeline stage has passed. |
| `phase-<n>/<topic>` | Work for one roadmap phase (`docs/implementation-plan-v2.md` §9) |
| `spike/<id>-<topic>` | Feasibility spikes. Their code under `spikes/` is evidence only and is never built into the application. |
| `fix/<topic>`, `docs/<topic>` | Small changes outside a phase |

## Merge requests

A merge request to `main` needs:
1. The pipeline green up to the `analysis` stage (`.gitlab-ci.yml`). Locally: `scripts\ci\Invoke-LocalPipeline.ps1 -Sonar -Package`.
2. **The randomised test loop passing 5 consecutive times** with fresh seeds (`scripts\test\Invoke-TestLoop.ps1 -Tag <phase>`). Any failure, and any code change, resets the count to 0. The loop report goes to `docs/evidence/<phase>/`.
3. Both SonarQube projects green on the "pgweb gate" (plan §8.3). With Community Build, analysis runs on `main` after the merge; a red gate on `main` blocks the next merge.
4. Documentation updated in the same change: README setup sections, ADRs if a decision changed, the phase status file.
5. No secrets, no binaries, nothing from `tools\`.

Overriding a SonarQube finding (false positive) needs a written justification in SonarQube and the stakeholder's acknowledgement.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/) style: `type(scope): summary`, with types `feat`, `fix`, `test`, `docs`, `build`, `ci`, `refactor`, `chore`. The body says what changed and why, and names the evidence (loop report, seeds) for test claims.

## Code rules (enforced)

| Rule | Enforced by |
|---|---|
| Exact versions everywhere; no Spring, Vert.x or `javax.servlet` | Maven Enforcer (`requirePluginVersions`, `bannedDependencies`, `dependencyConvergence`); `npm install --save-exact` |
| Java compiles warning-free | `-Xlint:all -Werror` |
| Coverage thresholds (plan §8.2); they only go up | JaCoCo `check`, Vitest `coverage.thresholds` |
| No `dangerouslySetInnerHTML`, `innerHTML`, `localStorage`/`sessionStorage`, `eval` | ESLint (`frontend/eslint.config.js`) |
| No external URLs in the SPA bundle | `frontend/scripts/check-dist.mjs` in `npm run build` |
| Every dependency is available offline | CI `deps-verify` (Maven `-o` with `offline/maven-settings.xml`; `npm ci --offline`) |

## Adding or updating a dependency

Follow the intake procedure in [`offline/README.md`](offline/README.md). In short: pick an exact version at least two weeks old, record it, rebuild online once to fill the caches, regenerate the SBOMs, and run the full test loop.
