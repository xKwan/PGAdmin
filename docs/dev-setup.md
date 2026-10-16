# Developer Setup

How to build, configure and run pgweb on a Windows 11 or Windows Server development machine. Toolchain installation is in the [README](../README.md#setting-up-a-development-machine-windows-11-or-windows-server); offline intake is in [`offline/README.md`](../offline/README.md).

## 1. Build

```powershell
. .\scripts\toolchain\Use-Toolchain.ps1
cd backend
mvn -B -o -s ..\offline\maven-settings.xml verify      # compile (-Werror), unit + integration tests, coverage gates
mvn -B -o -s ..\offline\maven-settings.xml package -DskipTests   # just the JAR: target\pgweb-backend-<version>.jar + target\lib\
```

Backend tests and execution need PostgreSQL's parser as a DLL (ADR-0009). Build it once from the pinned libpg_query sources with the project-local Zig compiler. This takes 5–10 minutes, needs no network, and is reproducible: the result must match `native/libpg_query/pg_query.dll.sha256`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File native\libpg_query\Build-PgQuery.ps1   # -> native\libpg_query\build\pg_query.dll
```

The CI jobs and `Invoke-TestLoop.ps1` build it when it is missing and refuse a DLL with any other hash (`Assert-SqlParser`). On an air-gapped machine, copy `pg_query.dll` from the release bundle's `native\` folder instead, and check its SHA256 against the `.sha256` file next to it.

The integration tests create throwaway PostgreSQL 18 clusters under `%TEMP%\pgweb-it-*` from the installed binaries. They look in `%ProgramFiles%\PostgreSQL\18\bin`, unless `PGWEB_PG_BIN` or `-Dpgweb.test.pgBin` points elsewhere. The clusters listen on localhost only, and a safety guard refuses any server the harness didn't create.

## 2. A local audit database

```powershell
powershell -ExecutionPolicy Bypass -File scripts\dev-db.ps1 start
```

This creates and starts a throwaway cluster in `.dev-db\` (git-ignored) on port 55433. It then:
- runs the shipped `deploy\sql\audit-bootstrap.sql` (roles `pgweb_migrator`, `pgweb_app` and `pgweb_audit_reader`, and the `pgweb_audit` database);
- migrates the schema with `pgweb migrate`;
- writes `.dev-db\pgweb.properties` in **test mode** with generated passwords.

Other actions: `status`, `stop`, and `destroy` (which deletes `.dev-db\`). The cluster never points at a shared or production server.

## 3. Run

```powershell
$jar = (Get-ChildItem backend\target\pgweb-backend-*.jar)[0].FullName
java -jar $jar check-config --config .dev-db\pgweb.properties        # validate only
java -jar $jar serve --config .dev-db\pgweb.properties               # http://127.0.0.1:8080/api/health and /api/ready
java -jar $jar verify-audit-chain --config .dev-db\pgweb.properties  # exit 3 if the chain is broken
```

| Command | Purpose | Exit codes |
|---|---|---|
| `serve` (default) | Run the service. Starts even if the audit DB is down; `/api/ready` then answers 503 and audited operations are refused (fail-closed). | 0 after a clean shutdown, 1 if startup fails |
| `check-config` | Validate the configuration and print a summary | 0, or 2 with every problem listed |
| `migrate` | Apply audit schema migrations as `audit.migrator.user`. `clean` is disabled. | 0, 1, 2 |
| `verify-audit-chain` | Recompute the audit hash chain independently of the database | 0 intact, 3 broken, 1 error |
| `version` | Print the version | 0 |

The configuration path can also come from `PGWEB_CONFIG`.

## 4. Configuration reference (`pgweb.properties`)

Unknown keys are errors, so a misspelt setting can't silently fall back to a default. **Secrets** are references, never literal values:
- `dpapi:<base64>`: Windows DPAPI, machine or service-account scope;
- `file:<path>`: the first line of an ACL-restricted file;
- `env:<NAME>`: an environment variable;
- `plain:<value>`: a literal, accepted only with `pgweb.mode=test`.

| Key | Default | Notes |
|---|---|---|
| `pgweb.mode` | — (required) | `prod` or `test`. `prod` requires TLS, Kerberos-only Negotiate, no `plain:` secrets, DNS host names, and `verify-full` TLS when TLS is on. |
| `server.bind`, `server.port` | `0.0.0.0`/8443 (prod), `127.0.0.1`/8080 (test) | |
| `server.workDir` | `work` | Tomcat's working folder. Use a folder the service account owns, e.g. `C:\ProgramData\pgweb\work`. A fixed folder rather than `%TEMP%`: Tomcat keeps files open in it, so temp folders couldn't be removed on Windows. |
| `server.publicHost` | — (required) | The exact host name users open. It is used for the Origin check and must match the SPN `HTTP/<host>` and the browser allowlist (S6 E3). |
| `server.tls.keystore`, `server.tls.keystorePassword` | — (required in prod) | PKCS#12 from the internal CA; the password is a secret reference |
| `auth.protocols` | `Negotiate` | `NTLM` is allowed in test mode only |
| `auth.kerberosOnly` | `true` | `false` only in test mode (the workstation has no domain) |
| `auth.dataAdminSid` | — (required) | SID of the DataAdmin AD group |
| `auth.allowedDomains` | any | Comma-separated NetBIOS domain names |
| `auth.session.idleMinutes`, `auth.session.absoluteHours` | 30, 8 | |
| `audit.jdbcUrl` | — (required) | `jdbc:postgresql://<audit-host>:<port>/pgweb_audit` (separate cluster, D4) |
| `audit.user`, `audit.password` | `pgweb_app`, — | The runtime role; INSERT-only |
| `audit.hmacKey` | — (required) | At least 32 bytes; keys the SQL text HMAC |
| `audit.spoolDir` | — (required) | Local folder for audit outcomes that couldn't be written |
| `audit.migrator.user`, `audit.migrator.password` | — | Only for `migrate` and `verify-audit-chain` |
| `audit.failMode` | `closed` | `closed`: an operation whose audit record can't be written is refused (503 `AUDIT_UNAVAILABLE`). `open`: the record is spooled and the operation continues (not recommended, D4). |
| `profile.internal.*`, `profile.external.*` | — | `host` (a DNS name, never an IP: S7-3), `port` (5433/5432), `sslMode` (`verify-full` in prod, `disable` in test), `sslRootCert`, `maintenanceDb` (`postgres`), `requiresDataAdmin` (always true for internal), `displayName`, `statementTimeoutSeconds` (300/60), `confirmDestructive` (`true`: ask before DROP, TRUNCATE, GRANT/REVOKE, other DDL, UPDATE/DELETE without WHERE and DO; a safeguard, not a security control), `bannerText` (optional workspace banner, at most 80 characters, for example `PRODUCTION`) and `bannerTone` (`info`, `warn` or `danger`; default `warn`) |
| `identity.roleCase` | `lower` | `lower`: the PostgreSQL role is the lower-cased `sAMAccountName`; `asis`: keep its case |
| `identity.roleOverrides` | — | `DOMAIN\account=role` entries separated by `;`, for accounts whose role doesn't follow the rule. The server's `pg_ident.conf` must agree. |
| `execution.enabled` | `false` | SQL execution kill switch (EXE-01). When `true`, the parser library must load or pgweb doesn't start. Behaviour: [`sql-execution-behaviour.md`](sql-execution-behaviour.md) |
| `parser.library` | `native/pg_query.dll` | Path to the libpg_query DLL (relative to the working directory); loaded only when execution is enabled |
| `limits.*` | ADR-0006 | `maxRows` 1000, `maxResponseBytes` 10 MB, `maxCellBytes` 64 KB, `maxSqlBytes` 1 MB, `cancelGraceSeconds` 5, `idleSessionMinutes` 15, `idleInTransactionMinutes` 10 (warning at 8), `maxTransactionAgeMinutes` 60, `heartbeatAbandonSeconds` 120, `sessionsPerUser` 3, `sessionsGlobal` 50, `failedOpensPerWindow` 5 per `failedOpenWindowMinutes` 5 |
| `logging.dir` | `logs` | Rolling `pgweb.log` (50 MB × 30 days, 2 GB cap). Messages and stack traces are redacted. |

## 5. Signing in locally

The workstation has no domain, so test mode allows NTLM (`auth.protocols=Negotiate NTLM`, `auth.kerberosOnly=false`). Edge signs in automatically to `http://localhost:8080` with your Windows account. `GET /api/me` shows the account, SID, DataAdmin flag, mechanism and CSRF token. `.dev-db\pgweb.properties` sets `auth.dataAdminSid=S-1-5-32-545` (BUILTIN\Users), so you are DataAdmin locally; set any other SID to see the non-DataAdmin view.

State-changing requests (POST, PUT, DELETE) need the `X-CSRF-Token` header from `/api/me` and an own-origin `Origin` header.

## 6. End-to-end tests (real Edge, real database connection)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test\Invoke-E2E.ps1 -Tag <phase> -Repeat 5
```

The script builds the JAR with the SPA (skip with `-SkipBuild`). It creates a throwaway PostgreSQL 18 cluster under `%TEMP%\pgweb-e2e-*`, whose `pg_hba.conf` authenticates **your Windows account** with `sspi` as the role with your lower-cased user name, and seeds an `e2e_db` database. It then starts pgweb in test mode with execution enabled, and runs `e2e\` (Playwright 1.63, installed Edge only, headless, `--auth-server-allowlist=localhost`). Everything is removed afterwards (`-KeepEnvironment` keeps the folder). Requirements: Edge, PostgreSQL 18 binaries (as for the integration tests), and the parser DLL (built when missing). The report goes to `docs\evidence\<phase>\e2e-*.json`; logs to `ci-logs\e2e-<phase>\`.

This exercises the production connection path in a real browser: Windows sign-in, the fresh Negotiate handshake on the request that opens a session, impersonation and an SSPI logon. On a workstation this is NTLM loopback. Kerberos is a target-environment item.

## 7. Security notes for developers

- **Heap dumps:** the JVM default (`-XX:-HeapDumpOnOutOfMemoryError`) is kept. Don't enable heap dumps on servers: a dump can contain the audit HMAC key, the audit DB password and query results (threat T10/T27). The service wrapper configuration (Phase 12) leaves them off.
- **Logs** are not audit records. The redactor masks `password=`, `token`, `secret`, `hmacKey` and `Authorization` values, but code must still never log secrets on purpose.
- **Errors:** every error response uses the `{"error":{"code","message","requestId"}}` envelope, with no stack trace or server version. The `requestId` matches the log lines for that request.
- **JSON requests** are parsed strictly: unknown fields, duplicate keys, trailing data and type coercion give 400.
