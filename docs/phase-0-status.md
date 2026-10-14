# Phase 0 Status and Closure Checklist

> **Gate G0:** approved by the stakeholder on 2026-10-15 ("Please proceed").
> **Phase 0 is not complete.** All deliverables are drafted, but none of the acceptance criteria can be met without stakeholder decisions and environment information.

## 1. Deliverables

| Deliverable | File | State |
|---|---|---|
| Environment inventory | `docs/environment-inventory.md` | Drafted. Workstation section **Verified**; all target-environment rows **Unresolved**. |
| Compatibility matrix (draft) | `docs/compatibility-matrix.md` | Drafted; all rows Assumed/Unresolved |
| Threat model (STRIDE) | `docs/threat-model.md` | Drafted; all mitigations Planned; residual risks need owners |
| Audit policy | `docs/audit-policy.md` | Drafted; all choices awaiting approval |
| Requirements (MVP vs deferred) | `docs/requirements.md` | Drafted; traceable to phases and tests |
| ADRs 0001–0008 | `docs/adr/` | All **Proposed** |

## 2. Phase 0 acceptance criteria (from the plan)

| Criterion | Met? |
|---|---|
| Every decision D0–D14 approved or approved-with-default | **No.** All unresolved. |
| Every inventory row filled with a source | **No.** Only the workstation section. |
| Every threat has a mitigation phase or accepted-risk owner | **Partly.** Mitigation phases assigned; 5 residual risks need named owners. |
| Stakeholder sign-off | **No** |

## 3. What is needed from the stakeholder

### 3.1 Decisions (answer per ADR, or "accept proposed default")

| # | Decision | ADR | Needed for |
|---|---|---|---|
| 1 | D0: Java/Spring Boot backend with Waffle; D14a separate service vs module in existing app | 0001 | G1 |
| 2 | D1: DataAdmin source and Windows→PostgreSQL name mapping | 0002 | G1 |
| 3 | D3: internal DB authentication option (I-A / I-B) | 0002 | G1 |
| 4 | D5: supported PostgreSQL versions | 0006 | G1 |
| 5 | D7: Java/Node versions and dependency distribution | 0001, 0008 | G1 |
| 6 | D2: external credential handling details | 0003 | G3 |
| 7 | D4 + D13: audit text mode, location, fail mode, retention, auditors | 0005, audit-policy | G3 |
| 8 | D14b/c: companion connection; superuser refusal | 0004 | G3 |
| 9 | D8: SonarQube enforcement model | 0008 | Phase 2 |
| 10 | D9: MVP scope | requirements | Phase 0 sign-off |
| 11 | D10, D11: limits and deferral of inline editing | 0006 | Defaults usable |
| 12 | D6, D12: Docker stance; single-instance topology | 0007, 0004 | Defaults usable |
| 13 | Owners for residual risks | threat-model §3 | Phase 0 sign-off |

### 3.2 Environment information (fill in `docs/environment-inventory.md` §2–§6)

Highest priority, because they block gate G1 and the Phase 1 spikes:
1. PostgreSQL versions and `pg_hba.conf` auth methods for 5433 and 5432 (decides whether option I-A is possible).
2. Where DataAdmin is defined, and the internal account naming convention.
3. The existing application's Java, framework and Waffle versions and configuration.
4. Whether a domain-joined, target-like Windows host and a domain test account (DataAdmin and non-DataAdmin) are available for spike S1. **The planning workstation is not domain-joined, so S1 can't run here.**
5. Whether a clean, network-disabled Windows VM is available for spike S4.
6. GitLab, Runner and SonarQube versions/editions; whether an internal artifact repository exists.

The inventory lists read-only SQL queries the DBA can run to collect PostgreSQL facts. No changes to any server are needed for Phase 0.

## 4. Next gate

**G1 (Phase 1 spikes)** opens only when: the inventory is complete; D0, D1, D3, D5 and D7 are decided; and the spike dependency list is approved for intake. Until then, no packages are installed and no application code is written.
