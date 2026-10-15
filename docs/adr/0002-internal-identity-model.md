# ADR-0002: Database identity (Kerberos delegation) and DataAdmin source

- **Status:** **Accepted**
- **Decision IDs:** D1, D3
- **Approval:** 2026-10-15, stakeholder. The defaults were approved, and then amended with: "Both the external and internal postgresql will be authenticated using Kerberos. The user is expected to login via the Windows Domain Account, which should be created in the AD. For internal postgres, it is expecting the user to be in DataAdmin group, which the external postgres have no restrictions, other than only allowing the user to use their own database, or shared with them." Further choices were made in the same session: Waffle impersonation, Kerberos-only in production, and DataAdmin as an AD group. See `docs/decision-log.md`.

## Context

- Users sign in with their Windows domain account, created in AD (Stated).
- Both PostgreSQL instances (internal 5433, external 5432) authenticate users with **Kerberos (SSPI/GSS); no PostgreSQL passwords exist** (Stated, 2026-10-15).
- Internal requires membership of the **DataAdmin AD group** (Stated).
- External is open to every authenticated user, limited by PostgreSQL privileges to databases they own or that are shared with them (Stated).

The earlier default (I-A, the user types their own PostgreSQL password) is therefore impossible.

## Decision

### Windows identity

- Comes **only** from Waffle's server-side security context. Client-supplied usernames, roles and flags are rejected.
- Production offers **Negotiate with Kerberos only**, because only a Kerberos logon can be delegated. NTLM is enabled only in test configuration, because the developer workstation has no domain.

### DataAdmin

- DataAdmin is an AD group, configured by **SID** (a name may be set as a display-only label).
- It is checked from the Waffle token's groups on every request to an internal resource and when an internal DB session is opened.
- Revocation takes effect at the next Windows authentication, so the delay is bounded by the absolute app session lifetime of 8 h (T28; accepted residual risk).

### Database authentication: Kerberos constrained delegation (option I-B)

1. Opening a DB session (internal or external) is a request that carries a **fresh Negotiate handshake**. The server answers `401 Negotiate` if the request has no `Authorization` header, and the browser repeats the request with a token.
2. The handshake's Windows identity must have the **same SID** as the app session's user, otherwise the request is refused and audited.
3. **Only while opening that session's two connections** (main and companion), the request thread impersonates that identity. pgJDBC then authenticates to PostgreSQL with Windows SSPI (`gsslib=sspi`, Kerberos).
4. Impersonation is reverted in a `finally` block and the token is disposed. **No token or credential is stored** in the app session.
5. **PostgreSQL role name** = configured mapping of the Windows account. The default is lowercase `sAMAccountName`, from configured domains only, plus an override table in server config. The server's `pg_ident.conf` must map the same principal to the same role.
6. After connecting, `session_user` must equal the mapped role and `rolsuper` must be false (D14c). Otherwise the connection is closed and audited.

### Infrastructure prerequisites (target environment)

- An `HTTP/<app-host>` SPN on the service account (gMSA or domain account).
- **Constrained delegation** from that account to `POSTGRES/<host>` for both PostgreSQL hosts. Protocol transition is not needed, because users log on with Kerberos.
- `pg_hba.conf`: `sspi` (or `gss`) for the app host, with `include_realm` and a `map` to roles.
- The browser GPO lists the app host for automatic Negotiate (S6 E3).

## Alternatives

| Option | Why not chosen |
|---|---|
| I-A user types PostgreSQL password | Accounts have no passwords |
| Java GSS S4U2Proxy with a keytab | Works without impersonation, but needs a keytab file on the server and more Kerberos configuration. Kept as fallback. |
| I-C per-session gateway roles | A privileged role-admin component |
| Shared login + `SET ROLE` | **Rejected:** `RESET ROLE` bypasses it |
| Keeping the user's token in the session for later connects | Long-lived token in memory; replaced by a fresh handshake per open |

## Consequences

- No user passwords pass through or are held by the application, so risk T10 and login throttling no longer apply (T11 becomes "failed opens are audited and rate-limited").
- **All user SQL runs on pgJDBC** (Vert.x has no Kerberos support); see ADR-0009.
- A user who logged on with NTLM, or whose service account lacks delegation, can't open DB sessions. They get a clear "Kerberos delegation required" error, which is audited.

## Validation

- **Workstation:** the impersonation and SSPI code path against a local PostgreSQL 18 cluster with `sspi` in `pg_hba.conf` (same machine, one account). Spike S7 and the P6 integration tests.
- **Untested in target environment:** Kerberos, cross-host delegation, real AD groups, and `pg_ident` mappings.
