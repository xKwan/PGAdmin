# Phase 1 Status: Feasibility Spikes

> Updated 2026-10-15. Evidence lives in each spike's `REPORT.md` and `results/` folder.
> Status labels follow the plan: **Tested** (run here, with evidence), **Untested in target environment** (needs the real domain, runner, VM or servers), **Unresolved** (needs a decision or a fact).
> Every result below is from the **developer workstation**: Windows 11 Pro, not domain-joined, not air-gapped, PostgreSQL 18.6. None of it proves the target environment.

## Spikes

| Spike | Workstation result | Untested in target environment | Report |
|---|---|---|---|
| S1 Waffle | **Partial pass.** NTLM/Negotiate with a local account on Spring Boot 4.1/Security 7 and 3.5/Security 6. SID and groups read; DataAdmin by group SID; rejection cases handled. Two wiring defects found and fixed (W3, W4). | Kerberos and SPN, domain groups, the real DataAdmin source (D1), the existing app's Waffle setup (A1) | `spikes/s1-waffle/REPORT.md` |
| S2 Parser | **Pass for PG 18.** libpg_query 18.1.0 built offline with Zig (reproducible DLL); 100% corpus agreement with the server; differential fuzzing clean; p99 ≈ 1 ms. Audit text must use scanner masking, not `pg_query_normalize`. | Production PostgreSQL major, clean air-gapped VM, runner | `spikes/s2-parser/REPORT.md` |
| S3 Driver | **Pass.** pgJDBC splits scripts on the client. Vert.x `preparedQuery()` makes the server reject multi-statement input. Cancel, row limits and terminate verified. Vert.x defects documented (DDL `rowCount`, empty-query hang). | Target PG majors, TLS, runner | `spikes/s3-driver/REPORT.md` |
| S4 Offline toolchain | **Partial.** JDK, Maven and Zig install offline from `tools\downloads`. Maven runs offline (`-o`) for all spikes. npm reinstalls with the registry unreachable. | A **clean, network-disabled VM**; SonarQube analysis; GitLab runner | Toolchain tests, `spikes/s6-e2e/REPORT.md` (E5) |
| S5 Throwaway PostgreSQL | **Pass.** `initdb`/`pg_ctl` clusters, concurrency, cancel, cleanup | GitLab runner service account, Windows Server | `spikes/s5-ephemeral-pg/REPORT.md` |
| S6 Edge E2E | **Partial pass.** Playwright drives the installed Edge 155 with no download. Automatic Windows logon through the allowlist; withheld for non-matching hosts. | GitLab runner, Kerberos, domain GPOs | `spikes/s6-e2e/REPORT.md` |

## New or changed design points from the spikes

| From | Point | Where recorded |
|---|---|---|
| S3, S2 | PostgreSQL itself enforces single statements: user SQL goes only through Vert.x `preparedQuery()`. pgJDBC is for app-owned SQL only. | ADR-0009 (Proposed) |
| S2 | Audit text uses scanner-based constant masking. `pg_query_normalize` leaves literals in 7 statement forms. | ADR-0005, `docs/audit-policy.md` |
| S2 | A native parser running in the backend's process is a new risk, **T31**, and needs an owner and a decision (accept, or isolate out of process / WASM) | `docs/threat-model.md` |
| S2, S3 | Statement-free input must be rejected before sending: Vert.x never completes it. | ADR-0009 |
| S2 | Script mode only when the session's `standard_conforming_strings=on` | S2 report P4 |
| S1 | Waffle must run on the `REQUEST` dispatch only; the security context must be saved to the session explicitly (Phase 4) | ADR-0001 |
| S6 | Users must open the app under the exact host name in the browser allowlist GPO | S6 report E3 |

## Gate position

- **G1 (spikes):** spike work went ahead on the stakeholder's instruction to continue with stand-ins (`docs/decision-log.md`). The ADRs it formally requires (D0, D1, D3, D5, D7) are still **Proposed**.
- **G2 (application code):** requires S1 and S4 to **pass in the target environment**. Both are only partial here. Starting Phase 2 needs either those environments or an explicit stakeholder decision to build on the workstation results while S1/S4 are completed in parallel.

## Needed from the stakeholder

1. **Approve or amend** ADRs 0001–0009, at least D0 (Java/Spring Boot 4.1 + Waffle), D7 (versions), D5 (PostgreSQL majors) and ADR-0009 (driver), before Phase 2.
2. **Decide on T31:** accept an in-process native parser, or require isolation.
3. **Environment access** for the open items: a domain-joined host and test accounts (S1/S6), a clean air-gapped VM (S4), the GitLab Windows runner (S4–S6), and the PostgreSQL versions and `pg_hba` methods of 5432/5433 (inventory §2).
