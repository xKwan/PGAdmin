# ADR-0002: Internal PostgreSQL identity and DataAdmin source

- **Status:** Proposed
- **Decision IDs:** D1, D3
- **Approval:** _pending_

## Context

Internal PostgreSQL (5433) may be used only by Waffle-authenticated users with `DataAdmin`, as their own existing PostgreSQL account (Stated, F3). Windows authentication does not authenticate anyone to PostgreSQL. How internal accounts currently authenticate is unknown (A4).

## Identity source (proposed)

- The Windows identity comes **only** from the server-side Waffle security context. Client-supplied usernames, roles and flags are rejected.
- PostgreSQL role name = configured mapping rule applied to `DOMAIN\sAMAccountName` (default: lowercase `sAMAccountName`), configured domains only, plus an explicit override table in server config. The convention must be confirmed against real accounts.
- After connecting, `session_user` must equal the mapped role and `rolsuper` must be false (D14c).

## DataAdmin source (unresolved)

| If DataAdmin is… | Then |
|---|---|
| An AD group | Waffle exposes it as a granted authority; checked on every request. Revocation applies at next authentication (cap: absolute session lifetime). |
| A role in the existing app's store | A read-only, server-side integration with that store is needed. **This design doesn't exist yet**; it becomes a Phase 0 design item once the store is identified. |

## Database authentication options

| Option | Summary | Proposed |
|---|---|---|
| I-A | User enters their own internal password; username fixed by the mapper | **MVP default**, only if `pg_hba.conf` allows password/SCRAM for these accounts |
| I-B | Kerberos constrained delegation / SSPI impersonation | Target state if AD supports it (spike S1b) |
| I-C | Per-session gateway roles with dynamic membership | Not recommended (privileged role-admin component) |
| — | Shared login + `SET ROLE` | **Rejected:** `RESET ROLE` bypasses it |

## Consequences

- No shared or unrestricted account touches user data.
- If accounts are SSPI-only, I-A is impossible and I-B becomes mandatory, or a new decision is needed.

## Validation

Requires inventory §2 (`pg_hba.conf`, naming) and §3 (DataAdmin source), spike S1 (and S1b if pursued), and P6 integration tests. **None done yet.**
