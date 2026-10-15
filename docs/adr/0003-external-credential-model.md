# ADR-0003: External PostgreSQL access

- **Status:** **Accepted** (replaces the earlier username/password proposal)
- **Decision IDs:** D2
- **Approval:** 2026-10-15, stakeholder: external PostgreSQL is authenticated with Kerberos like internal, with "no restrictions, other than only allowing the user to use their own database, or shared with them". Enforcement by PostgreSQL privileges was chosen. See `docs/decision-log.md`.

## Context

Any Waffle-authenticated domain user may use external PostgreSQL (5432). Authentication is Kerberos (ADR-0002), and no passwords exist. Users may only use databases they own or that are shared with them.

## Decision

| Aspect | Decision |
|---|---|
| Authentication | Kerberos constrained delegation, exactly as for internal (ADR-0002). There is no password form. |
| Host, port, TLS mode | Server-side configuration only. Requests containing host or port are rejected, which prevents SSRF and pointing at 5433. |
| Which databases | **PostgreSQL privileges decide.** The app lists databases where `has_database_privilege(<user>, datname, 'CONNECT')` is true and `datallowconn` is set: databases the user owns or that are granted to them or their roles. PostgreSQL enforces CONNECT again at connect time. A database typed directly is validated as an identifier and still subject to CONNECT. |
| Database list connection | The list query runs on a short-lived delegated connection to a configured maintenance database (default `postgres`), as the user. |
| Failures | One generic message; every attempt is audited; failed opens are rate-limited per user (5 per 5 min). |
| TLS | `sslmode=verify-full` with the internal CA truststore when the server supports TLS (A10). Otherwise risk T12 applies. |

**Note on PUBLIC:** PostgreSQL grants `CONNECT` to `PUBLIC` on new databases by default. Databases "shared with everyone" this way will appear. Restricting that is a DBA action (`REVOKE CONNECT ... FROM PUBLIC`), not app logic.

## Consequences

- Least privilege comes from the user's own PostgreSQL role. The app adds no privilege.
- No credential handling, storage or clearing, and no password-guessing route through the app.

## Validation

P6 integration tests: CONNECT-filtered listing, host/port override rejected, cross-user session access returns 404, `session_user` check. The Kerberos path is **Untested in target environment**.
