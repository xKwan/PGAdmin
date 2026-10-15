# ADR-0005: Audit storage, SQL text policy and fail mode

- **Status:** Proposed
- **Decision IDs:** D4, D13
- **Approval:** _pending_

## Context

Every SQL execution must be audited with identity, target, outcome and timing, while protecting sensitive values in SQL text. The full proposal is in `docs/audit-policy.md`.

## Proposed decision

1. **Store:** a dedicated `pgweb_audit` database on a **separate PostgreSQL cluster/port** not administered by DataAdmin superusers. Fallback, if not permitted: co-host on 5433 and accept tamper-evident-only.
2. **SQL text:** normalised text (literals and comments removed) plus `HMAC-SHA256` of the full text. Unparseable text and parser-unavailable cases store HMAC and metadata only. Raw text off.
3. **Write path:** intent committed before execution; outcome after; spool for outcome-write failures; `indeterminate` and `outcome_unknown` recorded.
4. **Fail mode:** **fail-closed** for both profiles.
5. **Integrity:** INSERT-only runtime role, UPDATE/DELETE trigger, hash chain, verified partition exports.
6. **Retention:** monthly partitions; 12 months online, 7 years archived (placeholders); two-person purge.
7. **Access:** named auditors via `pgweb_audit_reader`; no audit UI in the MVP.

## Consequences

- An audit DB outage stops SQL execution (fail-closed); the audit DB needs monitoring and a recovery runbook.
- Application logs and the spool are explicitly **not** audit records.

## Open items

All of `docs/audit-policy.md` §8.

- **S2 (Tested, PG 18, workstation):** normalised text is produced by scanner-based constant masking, because `pg_query_normalize` leaves literals in several utility statements. Comments are removed by the same scanner.
- For sessions with `standard_conforming_strings=off`, only the HMAC and metadata are stored.
- Evidence: `spikes/s2-parser/REPORT.md`.

## Validation

P3 tamper tests, P4 fail-closed and spool tests, P7 literal-absence and indeterminate-outcome tests. **Not run yet.**
