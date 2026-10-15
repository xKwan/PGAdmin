# Architecture Decision Records

Each ADR records one decision: context, options, the proposed decision and its consequences.

**Status values:** `Proposed` (awaiting approval) → `Accepted` / `Rejected` → `Superseded by ADR-nnnn`.
An ADR moves to `Accepted` only on explicit stakeholder approval, recorded in the ADR's "Approval" line with date and approver. Evidence from Phase 1 spikes is linked in "Validation".

| ADR | Title | Decision ID | Status |
|---|---|---|---|
| [0001](0001-backend-stack.md) | Backend stack: Java, Jakarta Servlet and Waffle (no Spring) | D0, D7, D14a | **Accepted** 2026-10-15 (stack); servlet container sub-decision and D7 pin Proposed |
| [0002](0002-internal-identity-model.md) | Internal PostgreSQL identity and DataAdmin source | D1, D3 | Proposed |
| [0003](0003-external-credential-model.md) | External PostgreSQL credential handling | D2 | Proposed |
| [0004](0004-db-session-model.md) | DB session model and companion connection | D12, D14b, D14c | Proposed |
| [0005](0005-audit-and-fail-mode.md) | Audit storage, SQL text policy and fail mode | D4, D13 | Proposed |
| [0006](0006-execution-limits.md) | Execution limits, timeouts and transaction behaviour | D5, D10, D11 | Proposed |
| [0007](0007-test-database-strategy.md) | Integration-test database strategy without Docker | D6 | Proposed |
| [0008](0008-offline-dependency-process.md) | Offline dependency intake and CI platform | D7, D8 | Proposed |
| [0009](0009-driver-choice.md) | PostgreSQL driver and single-statement enforcement | S3 outcome | Proposed |
