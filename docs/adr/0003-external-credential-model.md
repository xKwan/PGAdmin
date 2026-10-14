# ADR-0003: External PostgreSQL credential handling

- **Status:** Proposed
- **Decision IDs:** D2
- **Approval:** _pending_

## Context

Any Waffle-authenticated user may use external PostgreSQL (5432) with their own credentials; today they enter host, port, username and password in DBeaver (Stated, F4). The app must not grant extra privileges, expose other users' credentials, or open a route around app authorisation.

## Proposed decision

| Aspect | Decision |
|---|---|
| Host, port, TLS mode | Server-side profile config only. Requests containing host or port are rejected (prevents SSRF and pointing at 5433). |
| Database | From a server-side allow-list (free entry validated as an identifier only if approved) |
| Credentials | Username and password entered per DB session; HTTPS POST with CSRF token |
| Retention | Used to open the main and companion connections, then discarded. Not stored in session, cache, DB or browser storage. Reconnect means re-entering. |
| Clearing | Form cleared after every submit; `char[]` zeroed where possible. Clearing Java `String` copies is best-effort; heap dumps disabled. |
| Logging | Request bodies for this endpoint never logged; `password` redacted everywhere |
| Failures | One generic message; per-Windows-user throttling (5 failures / 5 min → 5-min block); every attempt audited without the password |
| TLS | `sslmode=verify-full` with internal CA truststore, if the server supports TLS |
| Saved credentials | Not in MVP |

## Consequences

- Least privilege comes from the user's own PostgreSQL role.
- Users re-enter passwords after session loss.

## Open items

TLS availability (A10); allow-list vs free database entry; password-manager policy (`autocomplete`); whether there is more than one external host; connection limits allowing two connections per session (A11).

## Validation

P6 integration tests: privileges enforced per role, host/port override rejected, cross-user access returns 404, secrets absent from logs, responses and audit. **Not run yet.**
