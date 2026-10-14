# Decision Log

Records instructions from the stakeholder that affect how work proceeds. Entries are not ADR approvals unless they say so explicitly. ADR status is tracked in `docs/adr/README.md`.

| Date | Instruction (stakeholder's words) | How it is applied |
|---|---|---|
| 2026-10-15 | "Please proceed" (after the v2.1 plan) | Gate G0 approved. Phase 0 started. |
| 2026-10-15 | Work hands-free; each phase needs randomised tests passing at least 5 consecutive times (reset on any failure), full documentation including setup, and a commit pushed to GitHub. Target: Windows Server and Windows 11, air-gapped. | Applied to every phase and spike. |
| 2026-10-15 | Install required software inside the project folder, reversibly, tracked in `configuration.properties` and a software list. Never commit program files. | `scripts/toolchain`, `toolchain/software.json`, `tools/` (git-ignored). |
| 2026-10-15 | "begin", in reply to three open questions: (1) accept the proposed ADR defaults? (2) continue with stand-ins where the real environment is needed, or stop at each gate? (3) run SonarQube locally? | Treated as permission to **continue with stand-ins**. Work that needs the real environment is marked "Untested in target environment". **Not treated as ADR approval:** ADRs 0001–0008 stay **Proposed**, and the spikes use their defaults only as working assumptions. Spikes are throwaway, test-only work against local throwaway clusters. SQL execution stays disabled in any non-test configuration. SonarQube, if used locally, will be the project-local zip distribution under `tools\`, **not** Docker (D6). |

## Still needs explicit stakeholder approval

- ADRs 0001–0008 (decisions D0–D14). Gates G1 and later formally depend on them.
- Owners for the residual risks in `docs/threat-model.md` §3.
- All target-environment facts in `docs/environment-inventory.md` §2–§6.
