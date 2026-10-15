# Phase 3 Status: Backend Foundation

> Updated 2026-10-16. Evidence: `docs/evidence/phase-3/`.
> All results are from the developer workstation (Windows 11 Pro, not domain-joined) against throwaway PostgreSQL 18.6 clusters.

## Delivered

| Item | Where |
|---|---|
| Validated configuration with fail-fast startup. Unknown keys are errors, every problem is reported at once, and production rules apply: TLS, Kerberos only, no literal secrets, DNS host names, `verify-full`. | `pgweb.config.ConfigLoader`, `docs/dev-setup.md` §4 |
| Secret references: `dpapi:`, `file:`, `env:`; `plain:` in test mode only. `Secret.toString()` never shows the value. | `pgweb.config.SecretResolver` |
| Logging with redaction of messages and stack traces (passwords, tokens, HMAC key, `Authorization` values), request IDs in every line | `pgweb.logging`, `logback.xml` |
| Error envelope for every error, including unmapped paths and connector-level rejections. No stack trace, no server version. | `pgweb.web.ErrorEnvelope`, `JsonErrorReportValve` |
| Strict JSON request parsing: unknown fields, duplicate keys, trailing data, coercion, wrong content type and oversized bodies are all refused | `pgweb.web.Json`, `ApiServlet` |
| Health (`/api/health`) and readiness (`/api/ready`: audit DB reachable) endpoints; graceful shutdown | `HealthServlet`, `Application` |
| Embedded Tomcat 11: `HttpOnly` + `SameSite=Strict` cookies, filters on `REQUEST` dispatch only, fixed working folder | `pgweb.server.WebServer` |
| **Audit DB schema:** monthly partitions; server-set timestamps; hash chain set by a trigger; UPDATE, DELETE and TRUNCATE rejected; the runtime role holds column-level INSERT only | `db/migration/V1__audit_schema.sql` |
| DBA bootstrap script: migrator, runtime and reader roles, plus the audit database | `deploy/sql/audit-bootstrap.sql` |
| Commands: `serve`, `check-config`, `migrate`, `verify-audit-chain`, `version` | `pgweb.Main` |
| Independent chain verifier: recomputes every hash in Java from a fixed query | `pgweb.audit.ChainVerifier` |
| Throwaway-cluster harness with a safety guard (marker file + per-run server token) | `PgTestCluster` (test scope) |
| Local development database | `scripts/dev-db.ps1` |
| Architecture rules: no Spring, Vert.x or `javax.servlet`; no JDBC in the web layer; no console output outside `Main`; no dynamic `Statement` SQL outside the database packages | `ArchitectureTest` |

## Acceptance criteria (plan Phase 3)

| Criterion | Result | Test |
|---|---|---|
| Invalid config gives a non-zero exit and a clear message | **Tested:** exit 2, with every problem listed; 25 random fault combinations per run | `MainIT`, `ConfigLoaderTest` |
| Unknown fields give 400 | **Tested** (`UNKNOWN_FIELD`), plus malformed JSON, duplicates, coercion, 413, 415 | `ServerIT.strictRequestParsing` |
| An unhandled error gives an envelope without a stack trace | **Tested:** a 500 envelope; the secret in the exception message is in neither the response nor the log | `ServerIT.unhandledErrorsGiveAGenericEnvelopeAndARedactedLog` |
| No test secrets in logs | **Tested:** random secrets in 10 formats, plus `Authorization` headers and the Logback converters | `RedactorTest`, `ServerIT` |
| Audit tamper test passes | **Tested:** `pgweb_app` can't SELECT, UPDATE, DELETE or TRUNCATE, can't set `seq`, `occurred_at` or the hashes, and can't create or drop objects. Even the owner can't change records. Edits, deletions, tail removal, broken links and a forged chain head are each detected by the verifier, even when a superuser disables the triggers. | `AuditSchemaIT` (16 tests) |
| The guard test passes | **Tested:** a wrong marker or a server without this run's token is refused | `PgTestClusterGuardIT` |
| Migrator and runtime roles are separate | **Tested** | `AuditSchemaIT`, `MainIT` |
| Heap-dump settings are documented | **Done** | `docs/dev-setup.md` §5 |

## Test evidence

| Check | Result | File |
|---|---|---|
| Randomised loop: frontend lint + Vitest; backend 34 unit + 34 integration tests, JaCoCo thresholds (security-critical `audit` package 90/85) | **PASS 5/5** on the final code, master seed `1057605090` | `docs/evidence/phase-3/loop-20261016-011026.json` |
| Earlier loops | FAIL (a Tomcat temp folder left behind, P3-2), then PASS 5/5 (master seed `197908091`) before the Sonar fixes changed the code | `loop-20261015-225538.json`, `loop-20261015-230339.json` |
| SonarQube "pgweb gate", both projects | **OK** after the fixes in P3-4; backend coverage 86%, frontend 100%; 0 blocker/high issues | `docs/evidence/phase-3/sonar-20261016-011027.json` |

## Findings

| # | Finding | Fix |
|---|---|---|
| P3-1 | Tomcat's host installs its own HTML `ErrorReportValve`, which shows "Apache Tomcat/11.0.26". Adding a second valve doesn't replace it. Unmapped paths also skip the filter chain, so they got no request ID. | `StandardHost.setErrorReportValveClass(JsonErrorReportValve)`, plus a default servlet on `/` that answers 404 through the filters |
| P3-2 | A Tomcat base folder in `%TEMP%` can't be deleted on Windows while Tomcat holds files in it, and a killed service leaves one behind every time | Fixed working folder `server.workDir` |
| P3-3 | In Windows PowerShell 5.1, `Start-Process -Wait`, or a pipe on `pg_ctl start`, waits for the PostgreSQL server too (the same handle inheritance as S5) | `dev-db.ps1` waits on the `pg_ctl` process object only, with output to files |
| P3-4 | SonarQube's first analysis of Phase 3 failed the gate: one blocker (a resource the analyser couldn't see closed), five high (duplicated literals, two over-complex methods), then medium reliability issues (unchecked `Optional.get`, two regexes with unbounded repetition, arrays in records) | All fixed; no finding was suppressed |
| P3-5 | Tomcat logs through `java.util.logging`, bypassing SLF4J and redaction | Tomcat's JUL logging is limited to warnings. Its messages contain no request data. |
