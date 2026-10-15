# Target-Environment Checklist

Everything that was built and tested on the developer workstation but still has to be shown in the real environment: a domain, the real PostgreSQL 18 servers, the GitLab Windows runner, Windows Server, and the air gap. The stakeholder chose, on 2026-10-15, to pass gates G2–G5 on workstation evidence. **These checks must pass before production enablement (G5).**

Each item gives the claim, how to check it, and where the workstation evidence is.

## 1. Active Directory and Kerberos

| # | Check | How | Workstation evidence |
|---|---|---|---|
| K1 | Users log on to the app with **Kerberos** (not NTLM) from a domain-joined Edge | Open the app; `klist` on the client shows an `HTTP/<app-host>` ticket; the app's `/api/me` reports mechanism `Kerberos` | NTLM only (S1, S6) |
| K2 | `HTTP/<app-host>` SPN is registered on the service account (gMSA or domain account), with no duplicate | `setspn -Q HTTP/<app-host>` | — |
| K3 | Constrained delegation from the service account to `POSTGRES/<internal-host>` and `POSTGRES/<external-host>` | AD account properties → Delegation; or `Get-ADServiceAccount -Properties msDS-AllowedToDelegateTo` | — |
| K4 | Opening an internal and an external DB session connects **as the user** | In the app, open both; the session panel shows `session_user` = the user's role | Same-machine SSPI with one account (S7, P6 tests) |
| K5 | A user **not** in DataAdmin can't open internal sessions, including via the API | P11 authorisation matrix against the real group | Local group SIDs (S1) |
| K6 | DataAdmin revocation takes effect by the next logon, within the 8 h cap | Remove a test user from the group and re-test after a new logon | Session-cap tests (P4) |

## 2. PostgreSQL 18 servers (5433 internal, 5432 external)

| # | Check | How |
|---|---|---|
| P1 | `pg_hba.conf` has `sspi` (or `gss`) for the app host, with `include_realm` and a `map` | `SELECT * FROM pg_hba_file_rules;` |
| P2 | `pg_ident.conf` maps each user principal to the same role name the app's mapper computes (default: lowercase `sAMAccountName`) | Compare for two test users |
| P3 | Login roles used through the app are not superusers | `SELECT rolname FROM pg_roles WHERE rolsuper AND rolcanlogin;` |
| P4 | `max_connections` and role `CONNECTION LIMIT` allow 2 connections per DB session | Inventory §2 |
| P5 | TLS: if `ssl=on`, the app connects with `verify-full` using the internal CA; if not, risk T12 is accepted | `SHOW ssl;` |
| P6 | External: databases a user owns or was granted appear; others don't. Note that `PUBLIC` has CONNECT by default. | Two test users, each with an owned database |
| P7 | DBA backstops: role-level `statement_timeout`, `idle_in_transaction_session_timeout`, `client_connection_check_interval` | Recommended, not relied on |

## 3. Build and CI

| # | Check | How |
|---|---|---|
| C1 | The GitLab Windows runner (PowerShell executor) runs the pipeline offline | Run `.gitlab-ci.yml` |
| C2 | The runner account may run `initdb`/`pg_ctl` and write to a temp directory | Integration-test job |
| C3 | Edge is installed on the runner; E2E runs headless under the runner's account | E2E job |
| C4 | The organisation's SonarQube runs both projects and the quality gate | Sonar job |
| C5 | Offline bundle: `mvn -o verify` and `npm ci --offline` pass on a **clean, network-disabled VM** (spike S4) | Offline-install rehearsal (P11) |

## 4. Deployment host (Windows Server)

| # | Check | How |
|---|---|---|
| D1 | The service installs under WinSW and runs as the service account | Installation guide (P12) |
| D2 | HTTPS with a certificate from the internal CA; HSTS on | Browser + header check |
| D3 | The app host can reach 5432 and 5433, and the audit DB cluster | `Test-NetConnection` |
| D4 | Browser GPO `AuthServerAllowlist` / intranet zone contains the exact app host name (S6 E3) | Silent logon from a user desktop |
| D5 | Audit-failure alerts reach monitoring (Windows Event Log) | Stop the audit DB; confirm the alert and that execution is refused (fail-closed) |
