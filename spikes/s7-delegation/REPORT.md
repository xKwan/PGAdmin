# Spike S7 (partial): pgJDBC SSPI logon under Waffle impersonation — Report

**Result: PARTIAL PASS on a workgroup Windows 11 workstation (one local account, no domain).**

The mechanism ADR-0002 relies on works end-to-end on one machine:
1. A Waffle server-side Negotiate handshake (`WindowsAuthProviderImpl`, the API Waffle's filter uses) yields the caller's `IWindowsIdentity`.
2. The request thread impersonates that identity.
3. pgJDBC 42.7.14 logs on to PostgreSQL 18.6 with `gsslib=sspi` and **no password**, and the server authenticates it with `sspi` and a `pg_ident` map.
4. Impersonation is reverted and the token disposed. The connection keeps working.

**Kerberos and cross-host constrained delegation are not exercised here.** On one machine, SSPI uses NTLM loopback, and the only account available is the one running the test. That part is in `docs/target-environment-checklist.md` (K1–K4).

## Environment

| Item | Value |
|---|---|
| Machine | Windows 11 Pro 10.0.26200, **not domain-joined**, local account |
| Java / Maven | Temurin 21.0.12.1+1 / Maven 3.9.16, offline |
| Driver | pgJDBC 42.7.14, `gsslib=sspi`, SSPI through `waffle-jna-jakarta` 3.7.0 |
| Server | Throwaway PostgreSQL 18.6 cluster; `pg_hba.conf`: `host all all 127.0.0.1/32 sspi include_realm=1 map=win` (and `::1/128`); `pg_ident.conf`: `win "/(?i)^<user>@.*$" <user>` |

## Loop history (2026-10-15)

| Run | Result | Cause | Fix |
|---|---|---|---|
| first manual run | Compile error | Lambda returning void passed as `Callable` | Fixed |
| manual, seed 1 | FAIL: 4 of 5 | pgJDBC builds the SPN with `DsMakeSpn`, which fails with error 87 when the host is an **IP address** (`127.0.0.1`) (**S7-3**). The anonymous-token test passed, but for the wrong reason. | Connect by host name (`localhost`; the cluster listens on IPv4 and IPv6). The anonymous test now needs a successful positive control first. |
| manual, seed 2 | PASS 5/5 | — | — |
| `s7-20261015-205935.json` | **PASS 5/5** (5 tests per run) | Final code | Master seed `1280369289` |

## Findings

| # | Finding | Status | Plan impact |
|---|---|---|---|
| S7-1 | An identity from a Waffle server-side handshake, impersonated on the request thread, logs on to PostgreSQL through pgJDBC SSPI with no password. `session_user` is the mapped role and the server logs `method=sspi`. | **Tested** (NTLM loopback, same account) | Confirms the ADR-0002 code path. Kerberos delegation to another host: **Untested in target environment**. |
| S7-2 | pgJDBC's SSPI logon uses the **calling thread's** token. Under `ImpersonateAnonymousToken` it fails with `28000 SSPI authentication failed`, while other threads connect normally at the same moment (4–8 threads, random mix). | **Tested** | Impersonation is per-thread and confined to the open request. The positive result is not the process identity leaking through. |
| S7-3 | pgJDBC's `DsMakeSpn` fails with error 87 if the host is an IP address | **Tested** | Server config must name PostgreSQL hosts by **DNS name** (the SPN is `POSTGRES/<host>`). The app's config validation should reject IP literals for Kerberos profiles. |
| S7-4 | A connection opened under impersonation keeps working after `revertToSelf()` and after the identity is disposed | **Tested** | The app holds no token after connecting (ADR-0002 step 4) |
| S7-5 | A `pg_ident` regex must cope with case: Windows reports the account name with its own case, and PostgreSQL matches case-sensitively. `(?i)` works as a regex option. | **Tested** | DBA note for `pg_ident.conf` (checklist P2) |

## Still open

| Item | Needed |
|---|---|
| Kerberos logon to the app and constrained delegation to `POSTGRES/<host>` on another machine | Domain, service account with delegation, two hosts (checklist K1–K4) |
| A second, different user: proving that the session user switches with the impersonated identity | A second account. Not created here, because that would change the workstation's accounts. |
