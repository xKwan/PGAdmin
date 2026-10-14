# PostgreSQL Web Query Interface: Implementation Plan (v2)

> **Status:** Revised proposal (v2.1), awaiting review and approval. **Nothing in this plan has been verified against the target environment or tested.**
> **Date:** 2026-10-15 (v2.1); 2026-10-14 (v2)
> **Supersedes:** `docs/implementation-plan.md` (v1, kept for comparison)
> **Revision history:** tracked in git. v2.1 adds verification-status labels, approval gates G0–G5, cancellation resource-release criteria, indeterminate audit outcomes, the Waffle/Spring Security 7 risk, and GitLab constraints.

### Status labels used in this document

| Label | Meaning |
|---|---|
| **Verified** | Confirmed by direct observation of the target environment |
| **Tested** | Proven by a test or spike that actually ran, with output recorded |
| **Stated** | Told to us by the stakeholder; not independently checked |
| **Assumed** | A technical belief based on knowledge of the tools; not checked here |
| **Unresolved** | Needs a stakeholder decision or environment information |

A requirement counts as satisfied only when it is **Tested** or **Verified** with recorded evidence. Describing it in this document does not satisfy it.

### Current verification status

| # | Risk area | Status | Gate |
|---|---|---|---|
| 1 | Waffle ↔ Java 21 / Spring Boot | Assumed / Unresolved | Blocker: P0 inventory and spike S1 |
| 2 | Identity and DataAdmin source | Unresolved | Blocker: D1 and S1 |
| 3 | Internal PostgreSQL (5433) authentication | Unresolved | Blocker: D1, D3 and `pg_hba.conf` review |
| 4 | SQL execution safety | Assumed | Blocker: S2 and S3; execution stays disabled |
| 5 | Cancellation and resource control | Assumed | Blocker: S3 and P7 tests |
| 6 | Audit and privacy | Unresolved | Blocker: D4 and D13 |
| 7 | Air-gapped dependencies | Assumed | Blocker: S4 and the intake process |
| 8 | GitLab CI on Windows runners | Assumed | Blocker: P0 inventory, S4–S6 |
| 9 | External PostgreSQL (5432) connections | Unresolved | Blocker for P6: D2 |
| 10 | Approval gates | Proposed (§9.1) | Must be adopted by the stakeholder |

No item is currently Verified or Tested.

---

## Part A: What changed from v1, and why

1. **The backend is now Java (Spring Boot), not Node.js.** Waffle is a Java library. It calls Windows SSPI through JNA and plugs into servlet filters and Spring Security. A Node backend could not reuse it. It would need either another Windows-auth mechanism, which has been ruled out, or an extra trust hop through the existing app. The frontend stays React and JavaScript, built with Vite and tested with Vitest. **This changes the architecture approved for review in v1, so it needs approval (decision D0).**
2. **OIDC and the app's own login system are gone.** Waffle Negotiate (Kerberos, falling back to NTLM) is the only way into the app. A user needs the `DataAdmin` role for internal access. Any authenticated user can use external access.
3. **Shared tiered service roles are gone.** Every SQL session now runs as **the user's own PostgreSQL account**. The plan separates application authentication (Waffle) from database authentication, and defines each database separately:
   - **Internal (5433):** the backend works out the PostgreSQL username from the Windows identity. Three options are defined for how the user authenticates to the database.
   - **External (5432):** the user supplies their own credentials. Host and port are fixed on the server.
4. **The plan is built for an air-gapped Windows environment.**
   - PostgreSQL stays on the existing EDB Windows installers.
   - Docker and Testcontainers are no longer required.
   - Integration tests create **throwaway PostgreSQL clusters with `initdb`/`pg_ctl`** from the installed binaries.
   - Dependencies come in through a defined offline intake process.
   - CI moves from GitHub Actions to **GitLab CI on Windows runners**.
5. **A feasibility spike phase (Phase 1) now comes before any feature work.** It checks Waffle, the SQL parser, the PostgreSQL driver protocol, the offline toolchain, the test harness and the E2E browser. Each check has pass/fail criteria and a stated fallback. There is **no hand-written splitter fallback**. If no reliable parser is found, script mode is disabled.
6. **A driver problem v1 missed has been addressed.** pgJDBC splits multi-statement SQL on the client with its own lexer. Single-statement safety therefore can't simply be assumed. The plan now covers how it will be enforced and verified.
7. **Cancellation is now specified end to end.** It uses the PostgreSQL cancel protocol, escalates through a **companion connection**, and quarantines connections whose state is uncertain. It also covers what happens when cancellation itself fails.
8. **New sections:** concurrency, locking, isolation levels and result consistency. Inline editing is **deferred** to post-MVP.
9. **The audit policy is now explicit:**
   - parser-normalised text plus an HMAC of the full SQL;
   - write-ahead "intent" records;
   - a hash chain for tamper evidence;
   - monthly partitions;
   - fail-closed recommended, and put forward as a decision.
10. **SQL execution stays behind a feature flag** until its authorisation, isolation, limits and audit controls pass their acceptance tests.
11. **The MVP now includes metadata browsing and a sortable, paginated result table.** Scope is split three ways: MVP, post-MVP, and operational scaling.
12. **Quality gates now depend on the SonarQube edition.** Dependency vulnerability scanning moves to the dependency intake step, because `npm audit` can't run offline.
13. **The plan ends with a decision register** (facts, assumptions, decisions, deferrals) **and a checklist of prerequisites and compatibility checks.**

---

## Part B: Revised implementation plan

### 1. Understanding and established constraints

The goal is an internal web interface that feels like DBeaver for common workflows: connect, write SQL, run it, read results, manage transactions and browse objects. It runs on Windows Server in an **air-gapped** network. It authenticates users with the **existing Waffle/Windows mechanism** and connects to two PostgreSQL instances (EDB Windows installers, no containers) whose authentication rules are different and unrelated:

| | Internal PostgreSQL | External PostgreSQL |
|---|---|---|
| Port | 5433 | 5432 |
| Who may use it (app-level) | Waffle-authenticated **and** has `DataAdmin` | Any Waffle-authenticated user |
| Database identity | The user's **own** internal PostgreSQL account, derived from their Windows identity on the server | The user's **own** external PostgreSQL credentials, which the user enters |
| What they can do | Exactly what that PostgreSQL account's privileges allow | Exactly what that PostgreSQL account's privileges allow |
| Shared or unrestricted accounts | None | None |

The security boundary has three layers:
1. **Waffle identity plus server-side authorisation** decides who may use which instance.
2. **The user's own PostgreSQL privileges** decide what SQL can do.
3. **Controls the server enforces outside the SQL session** handle timeouts, limits, isolation, cancellation and audit.

The frontend, keyword filters and session settings (GUCs) the user can change are never the boundary.

---

### 2. Architecture

```
Domain-joined browser (Edge/Chrome, intranet zone → automatic Negotiate)
   │ HTTPS (internal CA), same origin; JSESSIONID (HttpOnly, Secure, SameSite=Strict) + CSRF token
   ▼
pgweb service — Spring Boot (single JAR, Windows service, domain service account / gMSA)
   ├─ Waffle Negotiate filter → Spring Security context (Windows user, SID, groups)
   ├─ Authorisation policy (DataAdmin for internal; ownership for every DB session)
   ├─ Identity mapper (Windows user → internal PG role name; server-side only)
   ├─ DB Session Manager (per-user pinned main connection + companion connection,
   │     state machine, reaper, heartbeat, quarantine)
   ├─ Execution engine (single-statement gate, limits, wall-clock timeout, cancel escalation)
   ├─ SQL parser adapter (libpg_query; feasibility-gated)
   ├─ Metadata service (parameterised catalog queries on the companion connection)
   ├─ Audit service (write-ahead intent + outcome, hash chain, local spool)
   └─ Static SPA (React build) served from the same JAR
   │                      │                         │
   ▼                      ▼                         ▼
Internal PG :5433    External PG :5432       Audit DB (pgweb_audit) — location per decision D4
(user's own role)    (user's own role)       (app-owned roles; no user-data access)
```

#### 2.1 Technology choices

| Concern | Choice | Reason |
|---|---|---|
| Backend runtime | **Java 21 LTS** (Java 25 LTS acceptable; see D7) | Waffle requires the JVM. Spring Boot, pgJDBC and the SonarQube Java analyser all support it. |
| Framework | **Spring Boot** (3.5.x or 4.0.x, chosen by the Waffle compatibility spike) | Waffle has Spring Security integration. Spring Security provides CSRF, session fixation protection, headers and method security. **Open risk (Assumed/Unresolved):** it is not known whether Waffle provides a module for Spring Security 7, which Spring Boot 4.x uses. Spring Boot 3.5.x (Spring Security 6) is the likely fallback. Spike S1 decides. Waffle will not be replaced without stakeholder approval. |
| Windows auth | **Waffle** (`waffle-jna` plus the Spring Security module matching the chosen Spring Security major) | Reuses the existing mechanism, as required |
| PostgreSQL driver | **pgJDBC 42.7.x**. Vert.x PG Client is the alternative if spike S3 requires it. | Standard driver. Supports native cancel (`Statement.cancel()`), `setMaxRows`, `setFetchSize`, transaction-state access, SCRAM and TLS. |
| SQL parser | **libpg_query** (PostgreSQL's own parser extracted as a library), pinned to the server's major version, reached through JNA (already present via Waffle) or a WASM runtime in the JVM. **Feasibility-gated (S2).** | Uses the real PostgreSQL grammar for splitting and audit normalisation. No hand-written splitter. |
| App DB migrations | **Flyway** (Community; PostgreSQL version support to be verified) | Versioned SQL migrations for the audit and app schema |
| Backend tests | JUnit 5, Spring Boot Test, MockMvc + `spring-security-test`, AssertJ, Mockito, Awaitility, Maven Surefire (unit) and Failsafe (integration), **JaCoCo** coverage | Standard, works offline once the libraries are in a Maven mirror, and integrates directly with SonarQube |
| Build | **Maven** | More predictable offline (`-o`) than Gradle; native SonarQube scanner plugin |
| Frontend | React (JavaScript/JSX), Vite, **CodeMirror 6** (`@codemirror/lang-sql`, PostgreSQL dialect), TanStack Table + Virtual, TanStack Query, `react-resizable-panels` | Same reasoning as v1. CodeMirror also supports a CSP nonce, so no `unsafe-inline` styles are needed (to verify). |
| Frontend tests | **Vitest**, React Testing Library, user-event, MSW, vitest-axe | As required. Runs in jsdom. |
| E2E | **Playwright (JS)** driving the **system-installed Microsoft Edge** (`channel: 'msedge'`) | Avoids downloading Playwright's own browsers. Real Negotiate works on a domain-joined runner (S6). |
| Static analysis | ESLint, **SonarQube** (backend and frontend projects) | As required |

#### 2.2 Infrastructure: what's included, and what's deliberately left out

| Component | Included? | Justification |
|---|---|---|
| Spring Boot JAR serving both SPA and API | Yes | One process, same origin, nothing extra to run |
| Windows service wrapper (WinSW or Apache Procrun) | Yes | Needed to run the JAR as a service on Windows. One small offline binary. |
| Audit database (PostgreSQL) | Yes | Audit records need durable, access-controlled storage. Reuses the PostgreSQL technology already in use. |
| Local audit spool directory | Yes | Holds outcome records if the audit DB fails after a statement has executed (§6) |
| Reverse proxy (IIS/nginx) | **No** | Not needed. It would also break NTLM, which is connection-bound, unless Kerberos is guaranteed. |
| Redis, session store, message queue | **No** | Single instance. Sessions live in memory. |
| Separate identity provider | **No** | Waffle is the identity mechanism |
| Docker, Testcontainers | **No** (future option only) | Not available. The test harness uses `initdb`/`pg_ctl`. |
| Internal artifact repository (Nexus/Artifactory) | **Use it if one exists** | Otherwise use the file-based offline bundle (§7.1) |
| Vault or secret manager | **Use it if one exists** | Otherwise use an NTFS-ACL-restricted config file plus DPAPI-encrypted values |

#### 2.3 Authentication layers (kept explicitly separate)

| Layer | Mechanism | Proves | Does **not** prove |
|---|---|---|---|
| **App authentication** | Waffle Negotiate (Kerberos/NTLM) through Windows SSPI | The browser user is Windows account `DOMAIN\user` (SID, groups) | Anything about PostgreSQL access |
| **App authorisation** | Spring Security policy on every request | Whether this Windows user may use the internal or external feature, and owns the DB session being used | What the SQL may do |
| **Internal DB authentication** | Per decision D1/D3 (see §2.4) | That the session runs as the mapped internal PostgreSQL role | — |
| **External DB authentication** | The user's own username and password (SCRAM/md5, whichever pg_hba allows) | That the session runs as the PostgreSQL role the user authenticated as | — |
| **DB authorisation** | PostgreSQL GRANTs, RLS, role attributes | What each statement may do | — |

Windows authentication **does not** log the user in to PostgreSQL. The plan never assumes it does.

#### 2.4 Internal PostgreSQL (5433): identity mapping

**Mapping rule (server-side only; the user can't supply or override it):**
- Waffle gives the principal `DOMAIN\sAMAccountName`.
- Only configured domains are accepted.
- The PostgreSQL role name comes from a configured rule. The default is the lowercase `sAMAccountName`. An explicit override table in server config handles exceptions.
- The naming convention used by existing internal accounts must be confirmed (D1).

**Trusted identity source:** the Windows identity comes only from the server-side security context that Waffle establishes after Negotiate succeeds. No API accepts a username, role or authorisation flag from the client. Request fields such as `user`, `role` or `isDataAdmin` are **rejected**, not ignored. `GET /api/me` capabilities only drive the display; the server re-checks every request.

**`DataAdmin` check:** the source must be confirmed (D1). It is either an AD group, which Waffle exposes as a granted authority, or a role stored by the existing application. It is checked **on every internal-scoped request**, not only at connect time. Group membership from AD is fixed in the logon token when the user authenticates. Removing someone from the group therefore takes effect at their next authentication, so the absolute app-session lifetime caps the revocation delay (default 8 h; D10).

**How the user authenticates to internal PostgreSQL. Options:**

| Option | How it works | Pros | Cons | Recommendation |
|---|---|---|---|---|
| **I-A: user enters their own internal PostgreSQL password** | The backend fixes `user=<mapped role>`. The user types only the password. It is handled exactly like external credentials (§2.5). | Real PostgreSQL identity. No privileged app component. Simple. Easy to test. | Users enter a password a second time. Only works if their internal accounts allow password/SCRAM in `pg_hba.conf`. | **MVP default**, if pg_hba allows it |
| **I-B: Kerberos constrained delegation / SSPI impersonation** | Waffle impersonates the caller. The backend gets a delegated ticket to `postgres/host`. pgJDBC connects with GSS/SSPI as the user. | Seamless single sign-on. True identity. No password typed. | Needs AD configuration (SPNs, constrained delegation on the service account), an `sspi`/`gss` pg_hba entry, and a spike. Hard to test in CI. | **Target state.** Evaluate in Phase 1 (S1b) if AD admins can support it. |
| **I-C: per-session gateway login roles with dynamically granted membership** | A pool of zero-privilege login roles. The backend grants the user's role to one of them (`WITH SET TRUE, INHERIT FALSE`, PostgreSQL 16+) for the session and revokes it afterwards. | No password typed | Needs a role-admin account in the app, which is a high-value target. Catalog churn. Crash-cleanup complexity. | **Not recommended** unless I-A and I-B are both rejected |
| Rejected: shared login plus `SET ROLE <user>` | — | — | The user can run `RESET ROLE` and get the shared login's privileges back. Not a boundary. | Rejected |

**Checks after connecting (all options):**
- The backend runs `SELECT session_user, current_setting('server_version_num')` and checks that `session_user` equals the mapped role.
- It checks the role's `rolsuper` flag. **Superuser sessions are refused by default (D14c).** The app cannot meaningfully limit a superuser: `COPY … TO PROGRAM`, file access and audit-table tampering are all possible.

#### 2.5 External PostgreSQL (5432): credential handling

| Stage | Handling |
|---|---|
| **Connection details** | Host, port, TLS mode and allowed database list come from **server-side profile config**. The user cannot enter a host or port. This prevents SSRF and port scanning from the app server, and stops anyone pointing the "external" flow at 5433. Users choose a database from the allow-list, or type a name validated as a PostgreSQL identifier if free entry is approved (D2). |
| **Entry** | A form with username, password and database. The password field uses `autocomplete="off"` (password-manager policy is part of D2). Nothing goes into `localStorage`, `sessionStorage`, URLs or query strings. |
| **Transmission** | HTTPS POST with a CSRF token. The body is never logged: request logging excludes `/api/db-sessions` bodies, and redaction rules cover `password`. |
| **Use** | Used for the main connection and the companion connection, opened at the same time. **It is not kept afterwards**: no copy in the session, cache or database. A dropped connection means the user re-enters the password. |
| **Clearing** | The form field is cleared after submit, whether it succeeds or fails. On the backend the password is held as `char[]` where the code path allows and zeroed after use. **Limitation:** pgJDBC takes the password as a Java `String` (immutable), so heap clearing is best-effort. Heap dumps are disabled in production and dump directories are ACL-restricted. |
| **TLS to PostgreSQL** | `sslmode=verify-full` with the internal CA truststore if the server supports TLS (to verify; D2). Without TLS, SCRAM still protects the password, but query data travels in plaintext. That is recorded as a risk to accept or fix. |
| **Failures** | All authentication failures (SQLSTATE 28P01/28000) produce one generic message: "Database authentication failed." No host, no hint about whether the username exists. Failed attempts are rate-limited per Windows user (default 5 in 5 minutes, then a 5-minute block) so the app can't be used as a brute-force proxy. Every attempt is audited without the password. |
| **Isolation** | Credentials and connections are tied to the Windows identity and app session that created them. No other user's request can reach them. |

Saving credentials ("remember me", DPAPI-encrypted) is **post-MVP** and needs its own approval.

#### 2.6 App-owned database accounts and the audit store

| Role | Purpose | Privileges |
|---|---|---|
| `pgweb_migrator` | Used only at deploy time (Flyway) | Owns the `pgweb_audit` schema objects. Never used at runtime. |
| `pgweb_app` | Runtime audit writer | `INSERT` on audit tables and `USAGE` on sequences only. No `UPDATE`, `DELETE` or `TRUNCATE`. **No access to any user data database.** |
| `pgweb_audit_reader` | Auditors | `SELECT` on audit tables. Granted to named auditor accounts. |

No app-owned account ever runs user SQL. Where the audit DB lives is decision D4: DataAdmin users who are superusers on 5433 could alter audit rows there.

---

### 3. SQL execution design

#### 3.1 Parser and statement splitting (feasibility-gated)

- **Candidate:** libpg_query, pinned to the major version of the target servers. It is accessed either as a native DLL built with MSVC on a controlled staging machine, loaded via JNA, or as the WASM build running inside the JVM (e.g. the Chicory runtime). Spike S2 decides.
- **Uses:**
  1. splitting scripts into statements;
  2. checking that "single statement" text really is one statement;
  3. classifying statements (UX confirmations and audit metadata);
  4. normalising for audit, replacing literals with `$n`.
- **Not used as:** a security boundary. Privileges are the boundary.
- **Test corpus (S2, later kept as regression tests):**
  - single, double and escaped quotes (`''`);
  - `E'\''` strings; `U&''` strings;
  - `standard_conforming_strings` on and off;
  - line comments and nested block comments containing `;`;
  - `$$` and `$tag$` dollar quotes, including nested different tags;
  - `DO` blocks; `CREATE FUNCTION … LANGUAGE plpgsql`;
  - SQL-standard `BEGIN ATOMIC … END` bodies (PostgreSQL 14+);
  - `CREATE RULE` with multiple actions;
  - psql meta-commands (rejected as not SQL);
  - empty statements and trailing semicolons;
  - Unicode identifiers.
- **If no candidate passes 100% of the corpus:** multi-statement script execution is **disabled for MVP**. Audit stores the HMAC plus metadata, with **no** SQL text (§6). Single-statement execution goes ahead only if §3.2 provides parser-independent enforcement. Otherwise SQL execution is **escalated for a decision** before Phase 7.

#### 3.2 Driver behaviour and single-statement enforcement

- **The problem:** pgJDBC splits multi-statement strings on the client with its own lexer, before the server sees them. This is expected, and S3 will confirm it. pgJDBC alone therefore can't guarantee single-statement execution, and its lexer is effectively a hand-written splitter.
- **Option A (default, if S2 passes):**
  - The parser must report exactly one statement; otherwise the request is rejected.
  - Execution goes through pgJDBC in extended-protocol mode.
  - S3 confirms, using `log_statement=all` on the test cluster, that pgJDBC sends each parser-approved corpus statement as **one** Parse/Execute. Any mismatch fails the gate.
- **Option B (if S2 fails, or pgJDBC mangles any corpus case):** use the Vert.x Reactive PostgreSQL Client's prepared (extended) queries. It does not split on the client, so **the server rejects** multiple commands ("cannot insert multiple commands into a prepared statement"). That makes single-statement mode safe without a parser.
- **Statement under the cursor:** the frontend uses CodeMirror's Lezer SQL tree to pick it, **for convenience only**. A wrong boundary produces either a syntax error or a multi-statement rejection. Neither is unsafe, and the backend gate decides.

#### 3.3 How each kind of statement outcome is handled

| Category | Detection | API result | UI |
|---|---|---|---|
| Row-returning (`SELECT`, `VALUES`, `SHOW`, `EXPLAIN`, DML with `RETURNING`) | The driver returns a result set | `{kind:"rows", columns[], rows[], truncated, rowLimit, durationMs, retrievedAt}` | Results grid. "N rows (truncated at limit)". |
| DML with `RETURNING`, truncated | Result set plus the truncation flag | As above, plus `note:"statement completed; all matching rows were affected; only first N returned"` | Banner. The server runs DML to completion even when it returns fewer rows (to verify in S3). |
| DML without result set (`INSERT/UPDATE/DELETE/MERGE`) | Update count | `{kind:"command", commandType, affectedRows, durationMs}` | "UPDATE: 42 rows affected" |
| DDL and utility (`CREATE`, `ALTER`, `GRANT`, `VACUUM`, `SET`…) | Parser classification, or driver behaviour when there's no parser (S3 checks the two can be told apart) | `{kind:"command", commandType, affectedRows:null}` | "CREATE TABLE completed". **No** misleading "0 rows". |
| Zero rows | Result set with 0 rows | `kind:"rows"`, `rows:[]` | "Query returned no rows" (shown differently from a command) |
| Error | SQLException / SQLSTATE | `{kind:"error", sqlState, message, position, hint, detail?}` | Error panel with a friendly title for known classes (§4) and the error location marked in the editor |
| Timeout | App timer fired → cancel | `{kind:"timeout", limitMs, cancelOutcome}` | "Stopped after 60 s (limit)" |
| User cancel | Cancel endpoint | `{kind:"cancelled", cancelOutcome}` | "Cancelled" |
| Unsupported (`COPY … STDIN/STDOUT`, `LISTEN`, psql meta-commands) | Driver error or parser classification | Error `UNSUPPORTED_STATEMENT` | Clear message |
| Script (only if S2 passes) | Parser split | Array of per-statement results. Stops at the first error. | Per-statement result tabs or list |

`cancelOutcome` is one of `confirmed`, `escalated_terminated` or `unconfirmed` (§3.5).

#### 3.4 Limits and bounded retrieval

| Limit | Default (D10) | Enforced by |
|---|---|---|
| Max rows fetched per statement | 1,000 | `Statement.setMaxRows(limit+1)`, so the server stops sending. The +1 row detects truncation. **The SQL is not rewritten.** |
| Max response size | 10 MB | Serializer byte budget. Stops adding rows and sets `truncated`. |
| Max cell display | 64 KB | Serializer truncation with a marker. The full value is not sent. |
| Statement wall-clock limit | 60 s external / 300 s internal | App timer → cancel (§3.5) |
| Max SQL text | 1 MB | Request validation |
| DB sessions per user | 3 (each = 2 connections) | Session manager |
| Global DB sessions | 50 (adjust to `max_connections`) | Session manager |
| In-flight statements per session | 1 | Session lock. A second request gets `409 SESSION_BUSY`. |

**Pagination and sorting:** the fetched rows (≤1,000) are paged in the browser, 100 per page. Sorting is **client-side over the loaded rows only**, and labelled "Sorted locally — N loaded rows". Server-side sorting would mean rewriting SQL, so it isn't offered. "Fetch more" (server cursor) is post-MVP.

**Types:**
- `int8`, `numeric` and `money` returned as strings (JavaScript numbers lose precision);
- date and time as server text, with no timezone conversion;
- `bytea` as truncated hex;
- `json`/`jsonb` structured;
- arrays and unknown types as text;
- NULL as a distinct sentinel, shown differently from the string `'NULL'` and from an empty string.

#### 3.5 Timeouts and cancellation: the full path to PostgreSQL

1. **Trigger:** the user presses Cancel (`POST /db-sessions/{id}/cancel`, owner-checked) or the app's wall-clock timer expires. User SQL can't influence either. `SET statement_timeout = 0` has no effect on the app timer.
2. **Cancel request:** `Statement.cancel()` → pgJDBC opens a new socket and sends PostgreSQL's **CancelRequest** with the backend PID and secret key. No credentials are needed.
3. **Grace period (5 s):** wait for the statement to end with SQLSTATE `57014`. If it does, the connection is still healthy and its transaction state is read from ReadyForQuery (`IDLE`, `IN_TRANSACTION` or `FAILED`). Result: `cancelOutcome=confirmed`.
4. **Escalation:** the session's **companion connection**, running as the same role, calls `pg_terminate_backend(<main pid>)`. A non-superuser is allowed to do this for its own backends. The main connection is closed and the session is marked `TERMINATED`. Any open transaction is rolled back by PostgreSQL. Result: `cancelOutcome=escalated_terminated`.
5. **Escalation failed** (companion unavailable, or the terminate call fails or times out):
   - close the main socket;
   - mark the session `BROKEN`;
   - audit `cancel_unconfirmed`;
   - tell the user: "The query may still be running on the server."

   Server-side backstops, recommended to DBAs but **not relied on** (D10):
   - `client_connection_check_interval` (PostgreSQL 14+);
   - role-level `statement_timeout` and `idle_in_transaction_session_timeout`.
6. **No unsafe reuse:** a connection runs another statement only if its last exchange ended in a ReadyForQuery the app saw and its state is known. `BROKEN`, `TERMINATED` and `UNKNOWN` connections are closed, never pooled or reused. The user has to open a new session.

**Limits of cancellation:** a cancel only takes effect when the backend reaches an interrupt check. Some work may not respond quickly, such as extension functions or waits inside external calls. After a terminate, rolling back a large transaction still takes time on the server. Whether resources were actually released is therefore **measured** (P7 acceptance criteria), never inferred from the cancel call returning.

The app also sets `statement_timeout`, `lock_timeout` and `idle_in_transaction_session_timeout` at the start of each session as **backstops only**. Users can change them. Tests prove the app's controls still hold after users change them.

#### 3.6 DB session state machine and cleanup

States: `CONNECTING → READY ⇄ EXECUTING → READY | TX_OPEN | TX_FAILED | BROKEN | TERMINATED | CLOSED`.

| Event | Action |
|---|---|
| Success | Read the transaction status. Update state. Record the audit outcome. |
| SQL error | Same. In a transaction the state becomes `TX_FAILED`, and only `ROLLBACK` is allowed. |
| Timeout or cancel | §3.5 |
| Socket error, server restart | `BROKEN` → close both connections. API returns `SESSION_LOST`. Audit. |
| User disconnect / logout / app-session expiry | `ROLLBACK` if a transaction is open, then close both connections |
| Idle timeout (15 min) / idle-in-transaction timeout (10 min, warning at 8) | Roll back and close. Audit `reaped`. |
| Heartbeat missing (browser closed): no heartbeat for 120 s | If a transaction is open: roll back and close. Otherwise leave it until the idle timeout. |
| Application shutdown | Graceful: roll back and close everything, then audit. On a crash the OS closes the sockets and PostgreSQL rolls back. |

#### 3.7 Database unavailable

- **Connect timeout:** 10 s. TCP keepalive on.
- **Server unavailable:** API returns `TARGET_UNAVAILABLE`. The detail goes to server logs only.
- **No automatic reconnect** into a previous session, because transaction state would be silently lost. The user reconnects explicitly. The UI says that any uncommitted work was rolled back.

#### 3.8 Error envelope

- One error envelope for everything: `{ code, message, sqlState?, position?, hint?, correlationId }`.
- For **the user's own SQL**, PostgreSQL's message, SQLSTATE, position and hint are passed through. They only show what that role can see.
- **Connection, auth and infrastructure errors** are mapped to generic codes (`CONNECTION_FAILED`, `DB_AUTH_FAILED`, `TARGET_UNAVAILABLE`, `AUDIT_UNAVAILABLE`). Details stay in server logs only.
- No stack traces, hostnames, connection strings or credentials ever reach the client.

---

### 4. Concurrency, race conditions and result consistency

**Isolation between users and sessions:**
- Every DB session has its own physical connections, tied to one Windows identity, one app session and one profile.
- Nothing is shared across users: no pooling, no session reuse, no shared GUCs, temp tables or prepared statements.
- Session IDs are 128-bit random values. Every request checks ownership; an unknown or foreign ID returns 404.

**PostgreSQL is the authority on data integrity.** Constraints, row and table locks, MVCC and isolation levels decide outcomes. The app **adds no application-level data locking**. App-level locks can't cover changes made outside the app (other clients, jobs), and they would give a false sense of protection. The only serialisation the app adds is one in-flight statement per session, which protects the connection's protocol, not the data.

**How concurrent writes behave (and how the app explains it):**

| Situation | PostgreSQL behaviour | App reporting |
|---|---|---|
| Two sessions update the same row | The second **waits** for the first to commit or roll back. Under READ COMMITTED it then re-checks the row and proceeds. | While waiting: elapsed timer and a "waiting for lock" indicator. The companion connection polls `pg_blocking_pids(main_pid)` every 2 s and shows "Blocked by another session (PID n)". Blocker identity is shown only if the user's privileges let them see it. |
| Lock wait exceeds `lock_timeout` | `55P03 lock_not_available` | "Could not obtain lock in time; no changes made by this statement" |
| Deadlock | `40P01`. PostgreSQL aborts one transaction. | "Deadlock detected; your transaction was rolled back" (manual mode: state `TX_FAILED` → rollback required) |
| Concurrent update under REPEATABLE READ / SERIALIZABLE | `40001 serialization_failure` | "Transaction conflicted with a concurrent change; roll back and retry". **No automatic retry.** |
| Constraint violation (`23xxx`) | Statement fails | Constraint name plus PostgreSQL message |
| Statement in an aborted transaction | `25P02` | "Transaction is aborted; ROLLBACK required". Execute stays disabled until rollback. |

**Isolation levels:**
- The default is the server default, normally READ COMMITTED.
- In manual-transaction mode the user can pick READ COMMITTED, REPEATABLE READ or SERIALIZABLE, plus a read-only toggle, when the transaction starts. The app runs `BEGIN ISOLATION LEVEL …`. This is an explicit user choice, not a rewrite of user SQL.
- Typing `SET TRANSACTION …` or `BEGIN …` directly also works. The UI shows the transaction state reported by the driver.
- The read-only toggle is **a convenience, not a control**. Write prevention comes from privileges.

**Long or abandoned transactions:** idle-in-transaction timeout, heartbeat-based abandonment, and a configurable **maximum transaction age** (default 60 min, D10). When it's reached the user is warned, then the transaction is rolled back and the session closed.

**Disconnects and failures:**
- *Cancel* ends the statement, not the transaction. In manual mode the transaction becomes `TX_FAILED`.
- *Browser closed:* the heartbeat rule applies.
- *App crash:* sockets close and PostgreSQL rolls back.
- *DB restart:* `SESSION_LOST`.

**Result consistency (what the UI promises):**
- Each result is a **snapshot**: as of statement start under READ COMMITTED, or transaction start under REPEATABLE READ/SERIALIZABLE. The result header shows "Retrieved at 14:03:22".
- **Refresh** re-runs the same statement. Inside a REPEATABLE READ transaction it will show the same snapshot, and the UI says so.
- The UI never suggests that refreshing prevents race conditions.
- Metadata trees show "Loaded at …" and have a manual refresh. DDL run in the same session invalidates the tree, as a convenience only. Metadata uses the companion connection, so it shows *committed* state and won't show uncommitted DDL from the main session's open transaction. The UI documents this.

**Inline editing:** **deferred to post-MVP.** Doing it safely needs primary-key detection, optimistic concurrency (`WHERE pk = … AND xmin = …` or a check that every column is unchanged), conflict UX and audit. That is too much for MVP. In the MVP, data changes are made with SQL and reported with explicit transaction state, affected-row counts and errors.

---

### 5. Security architecture

**Controls that are reliably enforced:**

| Control | Mechanism |
|---|---|
| No unauthenticated access | The Waffle filter covers all paths except static login-error pages. Spring Security denies by default. A route-inventory test checks that every endpoint rejects anonymous requests. |
| Internal access requires DataAdmin | URL and method security on all `/api/internal/**` and internal-profile operations, plus a check in the domain service. Tested with direct API calls. |
| DB session isolation | Ownership check (Windows SID plus app session) on every session-scoped call. 404 for foreign IDs. |
| No shared or unrestricted DB accounts | Users' own credentials. Superuser sessions refused by default. App accounts have no data access. |
| What SQL can do | PostgreSQL privileges of the user's own role |
| Time, size and concurrency limits | Server-side, outside the SQL session (§3.4, §3.5) |
| SSRF / profile switching | Host and port come only from server config |
| App-generated SQL (metadata, audit) | Always parameterised (`PreparedStatement`). Identifiers are only quoted via pgJDBC's escaping, using values taken from catalog results or an allow-list. Static analysis rule: no string-concatenated SQL in `metadata/` or `audit/`, backed by an ArchUnit test. |
| Credentials | Never logged, persisted, echoed back or stored in the browser. Tested by grepping all captured logs and responses for known test secrets. |
| Errors | Safe error envelope with a correlation ID (§3.8) |
| CSRF | Negotiate credentials are **ambient**: the browser sends them automatically, even on cross-site requests to intranet hosts. So CSRF protection is mandatory: a Spring Security CSRF token (cookie-to-header for the SPA), an `Origin`/`Sec-Fetch-Site` check, `JSESSIONID` with `SameSite=Strict`, and side-effect-free GET requests. |
| XSS | React escaping. `dangerouslySetInnerHTML` is banned by lint. Strict CSP (`default-src 'self'`; CodeMirror styles via nonce). No CDN or external assets, which suits an air-gapped network. E2E asserts there are no requests to other origins. |
| Clickjacking and headers | `frame-ancestors 'none'`, HSTS, `nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on API responses |
| Session handling | Session ID rotated after authentication. Idle timeout 30 min, absolute 8 h. DB sessions close when the app session ends. |
| Audit | §6 |

**Limits and items still to decide:**
- An authorised user can read everything their PostgreSQL role can read. Data minimisation (RLS, column grants) belongs to the data owners.
- Classifier confirmations ("DELETE without WHERE") are UX safeguards only.
- Users can change session GUCs, so these are never treated as controls.
- If the audit DB is on 5433, superusers there can change audit rows. Tamper-*evident* is achievable; tamper-*proof* is not (D4).
- Clearing passwords from the Java heap is best-effort (§2.5).
- NTLM fallback is weaker than Kerberos. Registering the SPN makes Kerberos the norm (prerequisite).
- Waffle needs a Windows host. Any future move to Linux containers would require replacing Waffle, which needs approval (§7.7).

---

### 6. SQL audit policy (proposal)

**Events recorded:**
- `app_login`, `app_logout`, `authz_denied`;
- `db_connect_attempt` and its outcome;
- `db_disconnect`, `session_reaped`, `session_lost`;
- `exec_intent`, `exec_outcome`;
- `cancel_requested` and its outcome;
- `tx_commit`, `tx_rollback`;
- `metadata_browse` (object path only);
- `audit_spool_replayed`, `audit_failure`.

**Fields:**
- **Identity:** Windows username, SID, whether DataAdmin.
- **Target:** profile (`internal`/`external`), database name, PostgreSQL role (session_user), backend PID.
- **Correlation:** `requestId`, `executionId`, `dbSessionId`, and an app-session *hash* (never the raw ID). `application_name` is set to `pgweb:<dbSessionId>`. Users can change it, so it is informational only.
- **Statement:**
  - statement type or class;
  - normalised SQL text;
  - `HMAC-SHA256(full text)`;
  - text length;
  - parse status.
- **Outcome:**
  - success, error, timeout or cancelled;
  - cancel outcome;
  - SQLSTATE;
  - affected or returned row count;
  - truncated flag;
  - duration;
  - transaction state before and after.
- **Time and source:** UTC timestamps for intent, start and end; client IP.

**SQL text handling (recommended: normalised text plus keyed hash):**
- Literals are replaced by `$1…$n` using libpg_query normalisation, the same approach as `pg_stat_statements`. This covers passwords in `CREATE/ALTER ROLE … PASSWORD`, tokens and personal data held in literals. Comments are dropped; S2 verifies this.
- The **HMAC** of the full text, keyed by a secret held on the server, lets an investigator confirm "was this exact statement run?" when they have the text. The keyed hash defeats dictionary attacks on short literals.
- **If the text can't be parsed** (syntax errors), only the HMAC, length and SQLSTATE are stored, never the raw text.
- **If the parser is unavailable (S2 fails),** only the HMAC and metadata are stored.
- **Full raw text** is an opt-in, per-profile setting, for example internal only, and **needs explicit approval.** Its downside: it will capture secrets and personal data.
- Identifiers (table and column names) remain visible. This is assumed acceptable and needs confirmation.

**Write path and failure behaviour:**
1. An `exec_intent` record must **commit** to the audit DB *before* the SQL is sent.
2. `exec_outcome` is written after execution. If that write fails, the record goes to a **local spool** (append-only JSONL file; NTFS ACL: service account write, auditors read) and an alert goes to the log or Windows Event Log. A reconciler replays the spool when the DB is back. An intent with no outcome means "outcome unknown", which is still evidence the statement was attempted.

**Uncertain outcomes:**
- If execution is cut off by a socket loss or an unconfirmed cancel, an autocommit statement may have committed just before. The outcome is recorded as **`indeterminate` (may have committed)**, never as a failure.
- The reconciler reports every intent record that has no outcome as **`outcome_unknown`**.

**What is and isn't the audit record:**
- The **audit DB is the only record of truth.**
- The local spool is temporary storage. Spool entries are hash-chained, marked "spooled", and raise an alert.
- Application logs (Logback) are for operations only. They are **never** treated as audit records or as tamper-proof.

| Policy | Behaviour when the intent can't be persisted | Security consequence | Operational consequence |
|---|---|---|---|
| **Fail-closed (recommended)** | Execution is refused with `503 AUDIT_UNAVAILABLE`. Connect/disconnect still works. | No database change happens without an audit record. This matches DataAdmin-level access. | An audit DB outage stops all SQL execution. The audit DB becomes a critical dependency and needs monitoring and a recovery runbook. |
| Fail-open | Execution continues. The intent is spooled locally. | Unaudited changes are possible during an outage. The local spool is easier to tamper with. Detection only after the fact. | The app stays usable during audit outages |
| Hybrid | Fail-closed for internal, fail-open with spool for external | Mixed | Mixed |

**Decision D4 requires approval.** Unless approved otherwise, fail-closed will be built and tested. The policy is controlled by one config switch whose behaviour is tested both ways.

**Tamper resistance:**
- The runtime role is INSERT-only, and a trigger rejects UPDATE and DELETE.
- Each row stores `prev_hash` and `row_hash`, forming a hash chain. A verification job/CLI detects deletions or edits.
- A daily export of closed partitions with a SHA-256 manifest goes to an ACL-restricted share.
- Superusers on the audit instance can still bypass the trigger. The chain makes that **detectable**. Hosting the audit DB on a separate cluster or port, administered by someone other than the DataAdmins, prevents it (D4).

**Retention and rotation:** monthly declarative partitions. Default **12 months online, 7 years archived**, as placeholders to align with organisational policy (D13). Old partitions are purged by a documented procedure that needs two-person approval. Partition exports keep their manifests.

**Access:** no audit UI in the MVP. Auditors use SQL through `pgweb_audit_reader`. The application's logs never include SQL text beyond the audit record.

---

### 7. Environment, offline dependencies, CI/CD and deployment

#### 7.1 Offline dependency procedure

| Dependency group | Needs internet when acquired? | Offline approach |
|---|---|---|
| JDK (Temurin / Microsoft OpenJDK MSI) | Yes | Download on the staging side, verify the vendor SHA-256/signature, transfer, install from MSI |
| Maven distribution | Yes | Same |
| Maven artifacts (Spring Boot, Waffle, JNA, pgJDBC, Flyway, test libraries, plugins incl. sonar-maven-plugin, JaCoCo, Surefire/Failsafe, CycloneDX) | Yes | **Preferred:** import into an internal Nexus/Artifactory, if one exists. **Otherwise:** on a clean staging machine, run the full build (`mvn -B verify` plus every plugin goal the pipeline uses) with an empty local repository, then archive the populated `.m2/repository`. `dependency:go-offline` alone misses some plugin dependencies. In the air gap, use `settings.xml` with an offline/mirror config and `checksumPolicy=fail`. |
| Node.js (for building and testing only; **not needed on production servers**) | Yes | MSI plus SHA-256 verification against the published `SHASUMS256.txt` |
| npm packages (React, Vite, CodeMirror, TanStack, Vitest, RTL, MSW, ESLint, Playwright library) | Yes | **Preferred:** internal npm registry. **Otherwise:** on a Windows x64 staging machine, `npm ci --cache <bundle>/npm-cache` from the committed `package-lock.json`, transfer the cache, then `npm ci --offline --cache <path>` in the air gap. Lockfile `integrity` (sha512) is checked on install. **Note:** esbuild/Rollup/Rolldown include **platform-specific native packages** (`*-win32-x64*`), so the bundle must be produced on Windows x64. |
| Playwright browsers | Yes (CDN) | **Avoided:** use the installed Edge (`channel:'msedge'`, `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`). Edge's version must fall within Playwright's supported range (S6). |
| libpg_query (if chosen) | Source download | Pin the release tag and source SHA-256. Build on a controlled Windows machine with MSVC (or use the WASM artifact). Record the build recipe and binary hash. Store the binary in the artifact store, **not in git**. |
| SonarScanner | Maven plugin: via Maven mirror. CLI: zip. | Recent SonarQube servers deliver the scanner engine/JRE **from the SonarQube server**, which works in the air gap (to verify for the installed version). Analysing JS needs Node.js on the CI runner. |
| WinSW / Procrun | Yes | Single binary, hash-verified |
| PostgreSQL binaries for tests | No (already installed) | Use the existing EDB installation's `bin` on the runners (same major version as production) |

**Intake controls, every time dependencies are brought in or updated:**
1. A dependency change request lists the new or changed packages.
2. On the staging side, run vulnerability scans: `npm audit`, OWASP Dependency-Check or Trivy against the generated **CycloneDX SBOMs**, plus any malware scanning the organisational process requires.
3. Produce a bundle manifest with a SHA-256 for each file, signed or checked by a second person.
4. Transfer through the approved media process.
5. Verify the manifest in the air gap.
6. Import into the mirror or bundle store.
7. Run the CI job `deps:verify`: a clean offline install must succeed.

`npm audit` and online CVE feeds **do not work in the air gap**. Vulnerability status is assessed at intake. If a mirrored NVD feed or Dependency-Track exists internally, it can be added.

**Version pinning:** exact versions everywhere (no `^`/`~`), with `package-lock.json` and Maven dependency management via the Spring Boot BOM plus explicit pins. The `.nvmrc`/`engines` and Maven Enforcer `requireJavaVersion`/`requireMavenVersion` rules fail the build on mismatched toolchains.

**Updates:** a quarterly update window plus out-of-band security updates, each going through the same intake. The compatibility matrix (§12) is re-verified with every update.

#### 7.2 Development environment (Windows)

- **Prerequisites:** JDK, Maven, Node.js, Git for Windows, PostgreSQL (existing EDB install; only `bin` is needed for the harness), Edge, and VS Code or IntelliJ with offline plugins.
- **Running locally:** run the backend, then `npm run dev` for the SPA.
  - Negotiate through the Vite dev proxy is unreliable because NTLM is bound to the connection. Two options:
    - (a) build the SPA and let Spring Boot serve it; or
    - (b) use a `dev` profile with a **mock Waffle principal**. That profile binds to `localhost` only, lives in a **test/dev-only module that isn't packaged in the production JAR**, and the app refuses to start if a mock provider is on the classpath under the `prod` profile (tested).
- **Development databases:** local throwaway clusters created by `scripts/dev-db.ps1` (`initdb`, two ports simulating 5433/5432, seeded roles from `deploy/sql/dev-seed.sql`). Developers never point at shared or production instances. The safety guard (§7.4) applies.
- **Repository hygiene:** `.gitattributes` (LF in repo; CRLF for `*.ps1`/`*.cmd`), `.editorconfig`, PowerShell scripts signed if execution policy requires it.

#### 7.3 Proposed repository structure

```
/backend                 Maven project (Spring Boot)
  pom.xml
  src/main/java/<org>/pgweb/
     config/  security/ (waffle, csrf, headers)  authz/  identity/ (mapper)
     dbsession/ (manager, state machine, reaper, heartbeat)  execution/ (engine, limits,
     cancel, serializer)  sqlparse/ (libpg_query adapter)  metadata/  audit/ (service,
     spool, chain)  web/ (controllers, error envelope)
  src/main/resources/  application.yml  db/migration/ (Flyway)
  src/test/java/          unit tests (Surefire)
  src/it/java/            integration tests against ephemeral PG (Failsafe)
  src/testFixtures/       ephemeral cluster harness, mock Waffle provider (test scope only)
/frontend                Vite React (JS/JSX)
  src/app  src/components/common  src/features/{landing,connections,editor,results,
       transactions,metadata}  src/api  src/mocks
/e2e                     Playwright (JS), msedge channel
/native/libpg_query      build recipe, pinned source hash (no binaries)
/offline                 dependency manifests, SBOM outputs (generated), intake scripts
/deploy/windows          WinSW/Procrun config, install/upgrade/rollback .ps1, config templates
/deploy/sql              audit DB roles & bootstrap, dev seed, privilege verification
/deploy/container        README only (future-compatibility notes)
/scripts                 dev-db.ps1, verify-offline.ps1
/docs                    adr/, environment-inventory.md, threat-model.md, audit-policy.md,
                         ops/ (runbooks), user-guide.md, compatibility-matrix.md
.gitlab-ci.yml
```

#### 7.4 Integration tests against real PostgreSQL (no Docker)

- **Default: a throwaway cluster for each test run.**
  - The test harness calls the installed `initdb` and `pg_ctl`: a temp data directory, a random free port, `listen_addresses=localhost`, a generated superuser password, and `log_statement=all` where needed.
  - It creates two databases simulating internal and external, test roles (normal, read-only, superuser for refusal tests, roles with no CONNECT), and the audit DB with the production migrations.
  - At the end it stops the cluster and deletes the directory, including after test failures, using a JVM shutdown hook plus a CI `after_script` cleanup.
- **Isolation:**
  - Each Failsafe run gets its own cluster.
  - Tests within a run use unique schemas or databases per test class.
  - Concurrency tests use dedicated tables.
- **Safety guard:** the harness only connects to clusters **it created**, identified by a marker file in the data directory plus the port and a per-run token checked with `SELECT current_setting('pgweb.test_run_token')`. Any other target makes the run abort. Production connection settings can't be loaded under the `it` profile (tested).
- **Fallback (if runners can't run `initdb`):** a dedicated test PostgreSQL service on the runner. Each pipeline creates `pgweb_it_<pipelineId>` databases and roles prefixed with `it_<pipelineId>_`, then drops them. A nightly job removes leftovers older than 24 h. Same guard.
- **Docker/Testcontainers later:** the harness sits behind an interface (`PgTestCluster`), so a Testcontainers implementation can be added without changing tests.

#### 7.5 GitLab CI/CD design

**Runners:** self-hosted GitLab Runner on Windows Server, **shell executor (PowerShell)**, domain-joined (for E2E Negotiate), with the JDK, Maven, Node.js, the PostgreSQL `bin` directory, Edge, the offline Maven and npm stores, and SonarScanner prerequisites. A `pgweb-ci` tag routes jobs.

| Stage | Jobs | Fails the pipeline when… |
|---|---|---|
| `verify` | `deps:verify` (offline `npm ci --offline`, `mvn -o -B validate`), `toolchain:check` (Java/Node/Maven/Edge versions vs matrix) | Any network fetch attempted, checksum mismatch, version drift |
| `lint` | `lint:frontend` (ESLint, 0 errors, 0 warnings in CI), `lint:backend` (compiler `-Werror` for selected warnings, Enforcer, ArchUnit rules) | Any violation |
| `test` | `test:backend-unit` (Surefire + JaCoCo), `test:frontend` (Vitest + coverage thresholds) | Test failure or coverage below thresholds |
| `integration` | `test:backend-it` (Failsafe, ephemeral PG; includes security, concurrency, audit-failure suites) | Any failure |
| `e2e` | `test:e2e` (Playwright, msedge, against packaged app + ephemeral PG; real Negotiate with a test domain account, or mock-auth profile if S6 fails, plus manual real-auth smoke in staging) | Any failure |
| `analysis` | `sonar:backend`, `sonar:frontend` with `sonar.qualitygate.wait=true` | Quality gate red (see §8.3 for edition-specific behaviour) |
| `package` | Build SPA → copy into JAR → `mvn package`, CycloneDX SBOMs, SHA-256 manifest; release bundle zip (JAR, service wrapper, config templates, scripts, docs) | Build failure |
| `deploy` (manual) | `deploy:test` via PowerShell to the test server; `deploy:prod` manual and protected | Smoke test failure triggers documented rollback |

**Pipeline constraints:**
- No remote `include:` (no gitlab.com templates or external URLs).
- No `image:` or `services:` keywords (no Docker).
- No external SaaS. The `analysis` jobs reach only the internal SonarQube.
- GitLab Runner itself must be installable offline (P0 inventory).
- No GitHub Actions.

Merge requests to `main` run every stage up to `analysis`. `main` is protected and requires the pipeline to pass. Nightly: the concurrency suite repeated ×20 to catch flaky tests, plus audit chain verification against the test DB.

**Expected commands (once implementation begins):**

```
npm ci --offline --cache <bundle>/npm-cache        # frontend, e2e
npm run lint
npm run test -- --coverage                          # Vitest
mvn -o -B verify                                    # unit + integration + JaCoCo check
mvn -o -B sonar:sonar -Dsonar.qualitygate.wait=true
npx sonar-scanner -Dsonar.qualitygate.wait=true     # frontend project
npx playwright test                                 # from P11
```

#### 7.6 Deployment (current Windows environment)

- **Server:** Windows Server, domain-joined. A dedicated **gMSA**, or a domain service account, runs the service.
- **SPN:** `HTTP/<pgweb-host-fqdn>` registered to that account, so Kerberos is used rather than NTLM.
- **Browser policy:** the site must be in the intranet zone / `AuthServerAllowlist` (GPO) for automatic Negotiate.
- **HTTPS:** Spring Boot with a PKCS#12 keystore from the internal CA. Truststore for PostgreSQL TLS.
- **Layout:**
  - `C:\Program Files\pgweb\releases\<version>\`, with a `current` junction pointing to the active release;
  - config in `C:\ProgramData\pgweb\config\` (ACL: service account and admins);
  - logs and audit spool in `C:\ProgramData\pgweb\{logs,spool}`.
- **Secrets:** the `pgweb_app` password and the HMAC key live in the ACL-restricted config, DPAPI-encrypted (machine or service-account scope). They are never in the repo.
- **Production servers need only the JRE.** No Node.js, no Maven.

**Rollback:**
- Releases sit side by side, so rolling back means `stop service → repoint junction → start → smoke test`. A script does this.
- Flyway migrations are **expand-only** within a release line, so the previous binary keeps working against the newer schema. A destructive "contract" migration only ships in a later release, after the rollback window closes.
- Before upgrading: `pg_dump` the audit DB and verify the audit chain.
- **Audit data is never dropped** during a rollback.
- Rollback is rehearsed in Phase 12.

#### 7.7 Future container compatibility (no current dependency)

- Config comes from external files or environment variables. No registry dependence. Local state is limited to logs and spool, which can be mounted as volumes.
- **Hard constraint:** Waffle uses Windows SSPI, so the backend must run on **Windows** (a host or Windows containers with a gMSA credential spec). Linux containers would mean replacing Waffle with Kerberos SPNEGO, which needs approval.
- The service is **stateful** because DB sessions are pinned, so it runs as a single replica unless the operational scaling work (§10.3) is done.

---

### 8. Testing strategy and quality gates

#### 8.1 Required test suites

| Area | Suite | Tool |
|---|---|---|
| App logic, mapping, policy, limits, serializer, classifier, audit chain, error mapping | Unit | JUnit 5 / Mockito; Vitest |
| Authentication flow (mock Waffle provider), session fixation, timeouts, CSRF, headers | Integration (MockMvc) | Spring Boot Test + spring-security-test |
| **Direct-API authorisation matrix:** every endpoint × {anonymous, authenticated, DataAdmin} × {internal, external, foreign session} | Integration | Generated from the endpoint inventory, so new endpoints are covered automatically |
| External PostgreSQL respects the user's actual privileges (SELECT allowed/denied, DML denied for read-only role, no CONNECT → failure, cannot see other user's session) | Integration (ephemeral PG) | Failsafe |
| Internal identity mapping (mapped role used; supplied username ignored; superuser refused) | Integration | Failsafe |
| Execution categories (§3.3), oversized results, cell truncation, type fidelity | Integration | Failsafe |
| Timeouts and cancellation, including after `SET statement_timeout=0`, `SET lock_timeout=0`, `RESET ALL`, and while blocked on a lock; escalation path; unconfirmed path (companion killed) | Integration | Failsafe + Awaitility |
| Parser corpus (§3.1) and the single-statement gate | Unit + integration | JUnit; server log assertion |
| Concurrency and race conditions (§4): same-row update, lock timeout, deadlock (barrier-coordinated), serialization failure, rollback, cancel while blocked, terminate backend, cluster restart, abandoned transaction, app-process kill | Integration (real PG) | Failsafe. Coordination through `pg_locks`/`pg_stat_activity` polling, never fixed sleeps. Nightly ×20. |
| Audit: intent before execution, outcome after; **audit DB unavailable → fail-closed refusal**; outcome failure → spool and replay; chain verification detects edit or delete; no secrets in records | Integration | Failsafe |
| Frontend components: loading, empty, success, error, timeout and cancel states; landing-page capability display; credential form clearing; NULL and truncation rendering; XSS payloads shown as text | Component | Vitest + RTL + MSW |
| Authenticated and unauthenticated journeys; DataAdmin vs non-DataAdmin landing; connect/execute/cancel/commit/rollback; session expiry; no requests to other origins | E2E | Playwright (msedge) |
| Secret leakage: known test passwords and keys grepped across all logs, responses and audit rows after IT and E2E runs | Integration + E2E | Custom assertion |

#### 8.2 Coverage thresholds (enforced by the build, measured on unit + integration)

| Scope | Lines | Branches | Measured by |
|---|---|---|---|
| Backend overall | ≥ 85% | ≥ 75% | JaCoCo (Surefire + Failsafe merged), `jacoco:check` |
| Security-critical packages: `security`, `authz`, `identity`, `dbsession`, `execution`, `audit` | ≥ 90% | ≥ 85% | JaCoCo per-package rules |
| Frontend overall | ≥ 80% | ≥ 75% | Vitest V8 provider (`coverage.thresholds`) |
| Frontend `features/{connections,editor,results,transactions}` | ≥ 85% | ≥ 80% | Vitest per-glob thresholds |

E2E coverage isn't counted. Thresholds only go up, never down without an ADR.

#### 8.3 SonarQube quality gates

**Projects:** `pgweb-backend` (Maven scanner, JaCoCo XML) and `pgweb-frontend` (scanner with `sonar.javascript.lcov.reportPaths`).

**"pgweb gate", conditions on new code:**
- Coverage ≥ 80%.
- Duplicated lines ≤ 3%.
- 0 new issues of severity Blocker or High/Critical. On older servers using the legacy model: Bugs = 0, Vulnerabilities = 0.
- Reliability rating A, Security rating A, Maintainability rating A.
- Security Hotspots reviewed = 100%.

**Overall code, checked at each phase gate:**
- 0 open Vulnerabilities and Blocker/Critical bugs;
- Security rating A;
- coverage matches §8.2.

**How this depends on the edition:**
- **Community Build:** analyses only the main branch. There is no merge request analysis or decoration. In that case:
  - merge request pipelines enforce lint, tests and coverage locally;
  - Sonar runs on `main` after merge;
  - **a red gate on `main` blocks the next merge and phase sign-off** (enforced by a pipeline check of the Sonar gate status API).
- **Developer edition or higher:** merge request analysis and GitLab decoration, with the gate enforced *before* merge.
- **Still to verify:** server version; whether the Java and JS/TS analysers are installed; the Node.js version the JS analyser needs on the runner; scanner JRE provisioning in the air gap; GitLab integration settings. No third-party branch plugins.

**Blocking rule:** a phase is complete only when every required job passes on its final `main` commit and both gates are green. The actual commands and output are reported. Overriding a finding (false positive) needs a written justification in SonarQube and stakeholder acknowledgement.

---

### 9. Phased roadmap

**Ordering rationale:**
- Environment facts and feasibility come first (P0–P1), so nothing irreversible is built on assumptions.
- Supply chain and CI come next (P2), so every later phase is measured.
- Authentication, authorisation, isolation, limits and audit (P4, P6, P7) come before **any** SQL execution reaches users (P8, behind a feature flag).
- Transactions/concurrency and metadata build on the proven execution engine.
- Hardening and deployment close out the MVP.

```
P0 Discovery & decisions ─► P1 Feasibility spikes ─► P2 Offline toolchain/scaffold/CI/Sonar
   ─► P3 Backend foundation ─► P4 Waffle authN/authZ + audit ─┐
                      └─► P5 Frontend shell + landing (parallel from P2; integrates P4) ┤
                                                                                        ▼
       P6 DB connections & session isolation (no SQL execution)
          ─► P7 Execution engine (flag OFF; all controls) ─► P8 Editor & results UI (flag ON in test)
          ─► P9 Transactions & concurrency ─► P10 Metadata browser
          ─► P11 Hardening, E2E, security suite, offline rehearsal ─► P12 Deployment readiness ═ MVP
```

#### 9.1 Approval gates (blocking)

| Gate | Opens | Requires (all must hold) |
|---|---|---|
| **G0** | Phase 0 work | Stakeholder approves this plan |
| **G1** | Phase 1 spikes | P0 inventory complete; D0, D1, D3, D5 and D7 decided; spike dependency list approved for intake |
| **G2** | Phase 2 scaffolding and app code | S1 and S4 PASS; S5 PASS or fallback approved; S2/S3 outcomes recorded; compatibility matrix rows marked **Tested** with evidence |
| **G3** | Phase 6 DB connections | P4 authorisation and audit tests pass; D2, D4 and D14b/c decided |
| **G4** | Turning on execution (P8) | P7 acceptance tests pass with evidence (gate, limits, cancel and resource release, isolation, fail-closed audit); written stakeholder sign-off |
| **G5** | Production enablement | P11 and P12 complete; threat-model items mitigated or formally accepted |

**A failed spike or an undecided security item is a blocker.** It is reported with evidence and options, and never routed around. No implementation, package installation or application code change happens without the relevant gate's explicit approval.

---

#### Phase 0: Environment discovery, requirements and architecture decisions

- **Objective:** turn assumptions into verified facts and approved decisions. Documents only. Nothing is installed.
- **Scope:**
  - Environment inventory (§12):
    - PostgreSQL versions and `pg_hba.conf` methods for 5432 and 5433;
    - internal account naming;
    - the DataAdmin source;
    - the existing app's Waffle, Java and Spring versions and configuration;
    - SonarQube version and edition;
    - GitLab and runner versions;
    - Windows Server version;
    - whether an artifact repository exists;
    - browser versions and GPO.
  - Threat model (STRIDE).
  - Audit policy document.
  - Decision register resolved.
  - Draft compatibility matrix.
- **Artifacts:**
  - `docs/environment-inventory.md`, `docs/compatibility-matrix.md` (draft), `docs/threat-model.md`, `docs/audit-policy.md`, `docs/requirements.md`;
  - ADRs:
    - 0001 backend stack (Java/Spring + Waffle);
    - 0002 internal identity model;
    - 0003 external credential model;
    - 0004 DB session and companion connection;
    - 0005 audit and fail mode;
    - 0006 limits;
    - 0007 test-DB strategy;
    - 0008 offline dependency process.
- **Depends on:** stakeholder answers and access to environment information.
- **Tests/gates:** none (documents). Defines the gates used later.
- **Acceptance criteria:**
  - Every decision D0–D14 is marked approved, or approved-with-default.
  - Every row in the inventory is filled with a source ("confirmed by X / observed in config Y").
  - Every threat has a mitigation phase or accepted-risk owner.
  - Stakeholder sign-off recorded.
- **Security considerations:** don't copy production secrets or real `pg_hba` addresses into the repo. Use redacted inventory values.
- **Risks:** slow access to AD or DBA information blocks P1 S1/S1b.
- **Definition of done:** ADRs and inventory committed (on approval) and approved.

#### Phase 1: Feasibility spikes (throwaway code on a non-`main` spike branch, never merged)

- **Objective:** prove or disprove the risky assumptions before building on them.
- **Spikes and pass/fail criteria:**
  - **S1, Waffle:**
    - A minimal Spring Boot app on a target-like Windows server authenticates a domain user through Negotiate in Edge.
    - It reads the SID and groups.
    - It resolves DataAdmin from the confirmed source.
    - It confirms Kerberos rather than NTLM when the SPN is set.
    - It confirms the Waffle module works with the chosen Spring Security major version.
    - *Fail →* escalate. Options include matching the existing app's Spring version.
  - **S1b (optional), delegation:** constrained delegation to internal PostgreSQL via SSPI/GSS. *Pass →* I-B available as a target state.
  - **S2, parser:**
    - libpg_query (native DLL via JNA, or WASM in the JVM) loads offline on Windows.
    - It passes **100%** of the corpus (§3.1) for the confirmed PostgreSQL major versions.
    - Normalisation strips literals and comments.
    - Performance under 5 ms per typical statement.
    - *Fail →* script mode disabled; audit uses HMAC only; the single-statement path depends on S3 Option B.
  - **S3, driver protocol:**
    - Confirm whether pgJDBC splits on the client (expected).
    - Parser-approved statements are sent as one Parse (server log).
    - `setMaxRows` stops the server from sending more rows.
    - DML with `RETURNING` plus maxRows affects all rows.
    - Command type and affected rows can be told apart from DDL.
    - Transaction status is readable.
    - `cancel()` works against Windows PostgreSQL.
    - Companion `pg_terminate_backend` works for the same role.
    - User `BEGIN` in autocommit is tracked.
    - Evaluate Vert.x Option B equally.
    - *Outcome:* the driver choice ADR.
  - **S4, offline toolchain:** on a **clean** Windows VM with no network, install the JDK, Maven, Node.js and dependency bundles. `mvn -o verify` and `npm ci --offline && npm test` succeed on a skeleton. SonarScanner analyses against the installed SonarQube.
  - **S5, ephemeral PostgreSQL harness:** `initdb`/`pg_ctl` cluster lifecycle on the GitLab Windows runner under the runner's service account. Cleanup verified after a forced failure. *Fail →* use the dedicated test-instance fallback.
  - **S6, E2E browser:** Playwright drives system Edge on the runner. Real Negotiate with a test domain account works, or the fallback is recorded.
- **Depends on:** P0. Dependencies for the spikes are brought in through the intake process (the list is approved first).
- **Tests/gates:** each spike produces a short report with exact commands, versions and outputs. No coverage gate (throwaway).
- **Acceptance criteria:** every spike has a report marked PASS, FAIL-with-fallback-adopted, or ESCALATED. The compatibility matrix is promoted from draft to verified for every row the spikes covered.
- **Security considerations:** spikes use test accounts and throwaway clusters only. No production connections.
- **Definition of done:** reports and updated ADRs approved. The spike branch is archived, not merged.

#### Phase 2: Offline dependency baseline, scaffolding, GitLab CI, SonarQube wiring

- **Objective:** a reproducible offline build with every gate active before any feature code.
- **Scope:**
  - Maven backend skeleton and Vite frontend skeleton.
  - Pinned versions; Enforcer rules; lint configs; `.gitattributes`; dev scripts.
  - `offline/` intake scripts and manifest format; SBOM generation.
  - `.gitlab-ci.yml` with all stages (E2E as a placeholder until P11).
  - Two Sonar projects and the "pgweb gate".
  - `CONTRIBUTING.md` (branching, MR rules, commit conventions).
- **Depends on:** P1 (S4 especially).
- **Tests/gates:** one trivial test per side; coverage reports reach Sonar.
- **Acceptance criteria:**
  - A clean offline runner checkout passes every CI stage.
  - `deps:verify` fails if a network fetch is attempted (verified by an induced test).
  - A deliberately introduced lint error and a coverage drop each fail the pipeline (verified once, then reverted).
  - Both Sonar gates are green, with coverage visible.
  - SBOMs and the SHA-256 manifest are produced.
- **Security considerations:** no secrets in CI variables except masked and protected ones. Sonar token scoped to analysis.
- **Definition of done:** pipeline green on `main`. The intake procedure is documented and exercised once.

#### Phase 3: Backend foundation

- **Objective:** a secure-by-default service skeleton.
- **Scope:**
  - Validated config with fail-fast startup.
  - Secret resolver (DPAPI/config file).
  - Logback with redaction; correlation IDs.
  - Global error envelope; request validation (Bean Validation, unknown JSON properties rejected).
  - Health and readiness endpoints; graceful shutdown.
  - **Audit DB schema and roles** (Flyway, partitions, append-only trigger, hash-chain columns).
  - Ephemeral-cluster harness and safety guard (`PgTestCluster`).
- **Depends on:** P2.
- **Tests/gates:**
  - Unit: config, redaction, error mapping.
  - Integration: migrations on an ephemeral cluster; `pgweb_app` can't UPDATE or DELETE audit rows; the guard refuses a non-harness target.
  - Coverage per §8.2; Sonar gate green.
- **Acceptance criteria:**
  - Invalid config → non-zero exit with a clear message.
  - Unknown fields → 400.
  - Unhandled error → envelope without a stack trace.
  - No test secrets in logs.
  - Audit tamper test passes.
  - The guard test passes.
- **Security considerations:** the migrator and runtime roles are separate. Heap-dump settings are documented.
- **Definition of done:** as above, plus `docs/dev-setup.md`.

#### Phase 4: Waffle authentication, authorisation, sessions, CSRF, audit service

- **Objective:** identity, policy and audit exist before any database feature.
- **Scope:**
  - Waffle Negotiate filter chain (production) and the mock provider (test/dev module only, with the prod startup refusal).
  - Spring Security configuration: deny by default; session ID rotation; timeouts; CSRF cookie-to-header; Origin check; headers and CSP with nonce.
  - DataAdmin resolver.
  - `GET /api/me` (identity plus capabilities).
  - Authorisation policy service.
  - Rate-limit framework.
  - Audit service: intent/outcome API, hash chain, spool and replay, fail-closed switch.
  - Endpoint-inventory authorisation test framework.
- **Depends on:** P3, D1 (DataAdmin source).
- **Tests/gates:**
  - Auth matrix, generated from the endpoint inventory.
  - Session fixation; expiry; CSRF missing or invalid → 403; cross-origin request rejected.
  - Header assertions.
  - Audit DB down → `AUDIT_UNAVAILABLE` on an audited operation (using a test-only audited endpoint).
  - Spool replay.
  - Chain verification detects an edit or delete.
  - Mock auth refused under `prod`.
  - Security-critical coverage ≥ 90/85.
- **Acceptance criteria:**
  - 100% of non-public endpoints reject anonymous requests.
  - Non-DataAdmin → 403 on every internal endpoint via direct API.
  - Audit records include every field in §6 for login, logout and denial.
  - Sonar: 0 vulnerabilities, 100% hotspots reviewed.
- **Security considerations:**
  - The browser sends Negotiate credentials automatically, so CSRF tokens on every state-changing route are mandatory.
  - DataAdmin is re-checked on every request.
- **Risks:** NTLM fallback if the SPN is missing; group-membership revocation delay (§2.4).
- **Definition of done:** as above. The ADR is updated if S1 required changes.

#### Phase 5: Frontend shell, landing page, reusable components (can start after P2 using mocks)

- **Objective:** the DBeaver-style layout and the post-login landing page.
- **Scope:**
  - Landing page with **Internal PostgreSQL** (shown only if `capabilities.internal`) and **External PostgreSQL** cards.
  - App shell: explorer, editor and results panes.
  - Common components: StatusBadge, Spinner, EmptyState, ErrorPanel, ConfirmDialog, Toast.
  - API client: CSRF header; 401 → full reload to re-trigger Negotiate; 403 → "insufficient privileges" page; error envelope parsing.
  - TanStack Query; MSW handlers.
  - Session-expired and unauthenticated pages.
- **Depends on:** P2 (P4 contract for integration).
- **Tests/gates:**
  - RTL tests for each component state.
  - Landing page for DataAdmin and non-DataAdmin.
  - Client behaviour on 401, 403 and 503 (audit unavailable).
  - vitest-axe checks.
  - Lint ban on `dangerouslySetInnerHTML`.
  - Coverage per §8.2.
- **Acceptance criteria:**
  - All states tested.
  - No horizontal overflow at 1366×768 and 1920×1080 (RTL smoke now; Playwright check in P11).
  - No external URLs in the build output (a build check greps `dist/`).
- **Security considerations:** hiding the internal card is cosmetic only. The P4 tests prove the API enforces access.
- **Definition of done:** as above.

#### Phase 6: Database connection and session management (internal and external). No SQL execution.

- **Objective:** authenticated, isolated, cleanly managed DB sessions using users' own identities.
- **Scope:**
  - Server-side profile registry (fixed host, port, TLS, database allow-list).
  - Identity mapper; internal connect flow (per D1/D3; I-A in MVP).
  - External connect flow and credential handling (§2.5); failed-login throttling.
  - Main plus companion connections.
  - Post-connect checks (`session_user`, `rolsuper`, version).
  - Session state machine; ownership binding; per-user and global caps.
  - Reaper; heartbeat endpoint; app-session-expiry cascade; graceful shutdown.
  - Audit for connect, disconnect, reap and loss.
  - Execution feature flag `pgweb.execution.enabled=false`.
  - Frontend: connection dialogs (credential form clears itself), session list and status, session-info panel, expired and lost handling.
- **Depends on:** P4, P5, decisions D1–D3.
- **Tests/gates (integration, ephemeral PostgreSQL):**
  - Internal connect uses the mapped role; a username supplied in the request is ignored or rejected.
  - Non-DataAdmin direct API → 403.
  - Superuser refused.
  - External: correct credentials → session as that role; wrong credentials → generic message (host, port and username existence not revealed).
  - Throttling kicks in.
  - Host and port can't be overridden (request fields rejected).
  - User B gets 404 for every operation on user A's session.
  - Caps enforced.
  - Idle, idle-in-transaction and heartbeat reaping remove the backend from `pg_stat_activity`.
  - App-session expiry closes DB sessions.
  - Cluster restart → `SESSION_LOST`, no reuse.
  - Test passwords absent from logs and responses.
  - Frontend RTL for every connection state.
- **Acceptance criteria:** all of the above pass. `dbsession` coverage ≥ 90/85. Gates green.
- **Security considerations:** credentials aren't kept after connecting. TLS settings come only from the server profile.
- **Risks:** a user's `rolconnlimit` may block the companion connection. Fallback: degrade with no metadata or escalation, shown in the UI and audited.
- **Definition of done:** as above. The API spec is updated.

#### Phase 7: Execution engine (backend only, flag OFF outside tests)

- **Objective:** build and prove every execution control before users can reach execution.
- **Scope:**
  - Single-statement gate (per the S2/S3 decision); script mode only if S2 passed.
  - Outcome categories (§3.3); limits (§3.4); serializer and type mapping.
  - Wall-clock timer; cancel endpoint; escalation and quarantine (§3.5).
  - Transaction-state tracking.
  - Audit intent and outcome with normalisation and HMAC; fail-closed.
  - Classifier (UX metadata only).
- **Depends on:** P6, S2, S3.
- **Tests/gates (integration):**
  - Every row in §3.3.
  - Multi-statement payload rejected in single mode.
  - Parser corpus regression.
  - 100k-row query truncated with bounded heap (measured with JMX).
  - Oversized cells.
  - Type fidelity.
  - Timeout enforced after `SET statement_timeout=0` / `RESET ALL`.
  - Cancel confirmed, escalated and unconfirmed paths.
  - **Resource release, measured:**
    - after a confirmed cancel, that PID in `pg_stat_activity` is no longer `active` within N seconds, and it holds no rows in `pg_locks`;
    - after escalation, the PID is gone from `pg_stat_activity`;
    - another session can then take the locks the cancelled session held;
    - JVM heap stays below a stated bound during a 100k-row query (JMX);
    - all of the above still hold after the user runs `SET statement_timeout = 0`, `SET lock_timeout = 0` and `RESET ALL`.
  - Interrupted execution is audited as `indeterminate`; intents without outcomes are reported as `outcome_unknown`.
  - Quarantined connection never reused.
  - Concurrent execute on one session → 409.
  - Audit DB down → no SQL reaches PostgreSQL (verified via server log) and `503`.
  - Outcome-write failure → spool.
  - Normalised text contains no literal from the test corpus (secret-literal assertions).
  - `execution` and `audit` coverage ≥ 90/85.
- **Acceptance criteria:** all pass. Sonar green. **Explicit sign-off that the execution controls passed, which is required before P8 turns the flag on.**
- **Security considerations:** no SQL rewriting. The flag defaults to OFF in production config until P12.
- **Definition of done:** as above. `docs/sql-execution-behaviour.md` lists the supported behaviour and limitations.

#### Phase 8: SQL editor and result UI (execution enabled in test environments)

- **Objective:** the main workflow in the browser.
- **Scope:**
  - CodeMirror 6 editor: PostgreSQL highlighting, bracket matching, search, comment toggle, keyword autocomplete (schema-aware completion is post-MVP).
  - Execute selection or current statement (Ctrl+Enter); script execution only if enabled.
  - Running timer and Cancel.
  - Result grid: virtualised, column headers with type names, NULL rendering, truncation markers, client-side sort labelled as local, pagination.
  - Command result view (affected rows or "completed").
  - Error panel with position highlighted; timeout and cancel views showing `cancelOutcome`.
  - "Retrieved at" and a Refresh (re-run) button.
  - Lock-wait indicator.
- **Depends on:** P7 sign-off.
- **Tests/gates:**
  - RTL tests for every §3.3 category, cancel flow, timeout display, sorting and pagination, NULL vs empty string vs `'NULL'`.
  - XSS payload in a cell renders as text.
  - Refresh re-runs the statement.
  - Coverage per §8.2.
- **Acceptance criteria:**
  - All pass.
  - Editor bundle size recorded against a budget (e.g. ≤ 400 KB gzip for the editor chunk; confirmed in P0).
  - Gates green.
- **Security considerations:** results are always rendered as text. No result data is persisted in the browser.
- **Definition of done:** as above.

#### Phase 9: Transactions, isolation and concurrency

- **Objective:** safe multi-request transactions and honest reporting of concurrency.
- **Scope:**
  - Auto-commit / manual mode; Begin (isolation level, read-only toggle), Commit, Rollback.
  - Transaction state badge; `TX_FAILED` lockout until rollback.
  - Idle-in-transaction warning; maximum transaction age.
  - Warning on closing or disconnecting with an open transaction.
  - Friendly mapping of 55P03, 40P01, 40001, 25P02 and 23xxx.
  - Destructive-operation confirmations (DDL, DCL, TRUNCATE, DROP, DML without WHERE; unparseable statements → confirmation required). Production-style banner per profile.
- **Depends on:** P8.
- **Tests/gates:** the full concurrency suite from §8.1 (real PostgreSQL), in CI and nightly ×20, plus:
  - BEGIN/INSERT/ROLLBACK across three requests leaves no row; COMMIT persists.
  - Statements that can't run in a transaction block surface PostgreSQL's error.
  - RTL tests for every transaction state and confirmation flow.
- **Acceptance criteria:**
  - All pass.
  - Zero flaky failures across 20 nightly repetitions before sign-off.
  - Docs state that confirmations aren't security controls.
- **Security considerations:** the read-only toggle is labelled as a convenience.
- **Definition of done:** as above.

#### Phase 10: Metadata browser

- **Objective:** browse the objects the user can reach.
- **Scope:**
  - Tree: databases (allow-listed) → schemas → tables/views/materialised views → columns (type, nullability, default), indexes, constraints; functions/procedures (signatures); sequences.
  - Lazy loading on the companion connection; parameterised catalog queries.
  - Filters to objects the user has privileges on (`has_schema_privilege`, `has_table_privilege` …), with an option to show everything visible in the catalog. Catalog visibility is PostgreSQL's own behaviour; the app reveals nothing beyond what the role can query.
  - "Loaded at" and refresh; insert object name into the editor (quoted identifier).
  - Audit `metadata_browse`.
- **Depends on:** P6 (companion connection); P8 for editor insertion.
- **Tests/gates:**
  - Integration: objects without privileges are hidden or marked.
  - Identifiers containing quotes, spaces, Unicode or injection payloads (`a"; DROP …`) are handled safely. An ArchUnit rule forbids string-built SQL.
  - Busy companion connection → graceful.
  - RTL tests for tree states.
- **Acceptance criteria:** all pass. 0 Sonar injection findings. Gates green.
- **Security considerations:** this is the main area of app-generated SQL, so parameterisation is mandatory.
- **Definition of done:** as above.

#### Phase 11: Security hardening, E2E, security suite, offline-install rehearsal

- **Objective:** verify the whole system against the threat model.
- **Scope:**
  - Playwright E2E (msedge):
    - DataAdmin and non-DataAdmin journeys;
    - unauthenticated access;
    - external connect, execute, cancel, transaction, logout;
    - session expiry;
    - audit outage UX;
    - layout at 1366×768 and 1920×1080;
    - no requests to other origins.
  - Security regression suite:
    - authorisation matrix;
    - CSRF and cross-origin;
    - XSS payloads in results, errors, object names and usernames;
    - oversized bodies;
    - throttling;
    - headers and CSP;
    - secret-leak grep across all artifacts.
  - Dependency findings reviewed from intake SBOM scans.
  - Least-privilege checklist and `deploy/sql/verify-privileges.sql` for the app roles and the audit DB.
  - **Offline install rehearsal** of the full release bundle on a clean VM.
- **Depends on:** P10.
- **Acceptance criteria:**
  - All E2E and security tests pass in CI.
  - Every threat-model item is mitigated or formally accepted.
  - No unresolved High/Critical dependency findings, or approved documented exceptions.
  - Sonar: 0 vulnerabilities, 100% hotspots reviewed.
  - The rehearsal completes using only the docs.
- **Definition of done:** as above.

#### Phase 12: Deployment readiness and documentation. MVP release.

- **Objective:** something operable in the Windows environment.
- **Scope:**
  - Release bundle: JAR with SPA, service wrapper, config templates, PowerShell install/upgrade/rollback scripts, SBOMs, manifest.
  - Service account / gMSA, SPN and HTTPS setup guide; GPO/browser prerequisites.
  - Audit DB bootstrap.
  - Runbooks:
    - credential and key rotation (HMAC key, `pgweb_app`);
    - adding or changing profiles;
    - granting DataAdmin;
    - audit investigation and chain verification;
    - spool recovery;
    - audit DB outage;
    - kill switch (`execution.enabled=false`);
    - partition archive and purge;
    - dependency update intake.
  - User guide covering supported SQL behaviour and limitations.
  - Rollback rehearsal.
  - Production config turns execution on only after sign-off.
- **Depends on:** P11; D12 topology.
- **Tests/gates:**
  - Packaged-service smoke test in CI.
  - Install → upgrade → rollback rehearsal on a test server with audit data preserved and the chain verified.
  - Graceful shutdown closes all PostgreSQL sessions.
- **Acceptance criteria:**
  - Someone other than the author deploys from the docs alone.
  - Rollback completes within the documented time (e.g. ≤ 15 min).
  - All gates green.
  - Stakeholder sign-off.
- **Definition of done:** MVP release tagged.

---

### 10. Scope tiers

#### 10.1 Mandatory MVP (P0–P12)

- Waffle-authenticated access.
- Landing page with internal and external choices.
- DataAdmin enforced on the server for internal access.
- Internal access via the mapped identity (I-A unless I-B is proven).
- External access with the user's own credentials.
- CodeMirror editor with highlighting and practical editing.
- Single-statement execution (scripts only if the parser is proven).
- Success, error, timeout and cancel states.
- Bounded, sortable (locally), paginated results.
- Command results with affected rows.
- Auto-commit and manual transactions with isolation-level choice.
- Concurrency error reporting and lock-wait indicator.
- Metadata browser.
- Limits, isolation, cancellation with escalation.
- Audit (normalised + HMAC, intent/outcome, hash chain, fail-closed pending D4).
- Full test suites and gates.
- Air-gapped Windows install and operations docs.

#### 10.2 Post-MVP enhancements (each needs separate approval)

- Inline data editing with optimistic concurrency.
- Result export (CSV/XLSX with formula-injection escaping, audited).
- "Fetch more" with server-side cursors.
- Multiple editor tabs.
- Query history and saved queries (data-retention review needed).
- Schema-aware autocomplete.
- EXPLAIN visualiser.
- Advanced schema management (DDL designers).
- Audit viewer UI for auditors.
- Saved external credentials (DPAPI).
- Kerberos delegation (I-B) if not in MVP.
- Two-person approval for high-risk statements.
- Dark mode and keyboard customisation.

#### 10.3 Operational scaling (only when measured load or availability requirements justify it)

- Multiple backend instances with sticky routing, plus a session directory.
- Distributed session coordination.
- Hot-standby failover for the app. In-flight DB sessions would still be lost, and failover can't preserve them.
- Connection pooling for metadata. Pooling is not appropriate for user sessions.
- Audit forwarding to a SIEM.
- Windows containers with gMSA.
- Linux containers (requires replacing Waffle, with approval).

---

### 11. Decision register

#### 11.1 Facts established by the environment (as stated by the stakeholder)

| # | Fact | Status |
|---|---|---|
| F1 | The environment is Windows-based and air-gapped. Nothing is downloaded at install time or runtime. | Stated |
| F2 | PostgreSQL is installed with the Windows EDB installer. No containers. | Stated |
| F3 | Internal PostgreSQL is on port 5433. Access requires Waffle authentication **and** the `DataAdmin` role, plus an existing matching PostgreSQL account. | Stated |
| F4 | External PostgreSQL is on port 5432. Any Waffle-authenticated user may use it, with their own credentials. Users currently connect through DBeaver with host, port, username and password. | Stated |
| F5 | The existing application uses Waffle. It must remain the entry point. No OIDC or other identity provider. | Stated |
| F6 | CI/CD is GitLab. SonarQube and Vitest are mandatory. The frontend is React in JavaScript only, built with Vite. | Stated |
| F7 | Docker, Testcontainers and online registries can't be assumed. | Stated |

#### 11.2 Technical assumptions still to verify (verified in P0/P1)

| # | Assumption | Status | Verified in |
|---|---|---|---|
| A1 | The existing app is Java. Its Waffle version (`javax` 1.x/2.x or `jakarta` 3.x), framework (filter, Spring Security or Tomcat valve) and Spring version can be identified, and its setup reused for the new service. | Assumed | P0, S1 |
| A2 | A Waffle module exists for the chosen Spring Security major version. Support for Spring Security 7 (Spring Boot 4.x) is **unknown**; Boot 3.5.x is the likely fallback. | Assumed / Unresolved | S1 |
| A3 | An SPN can be registered, so Kerberos is used rather than NTLM. Browsers are configured for automatic Negotiate. | Assumed | P0, S1 |
| A4 | Internal PostgreSQL accounts can authenticate with password/SCRAM (needed for I-A). If they are SSPI-only, I-A is impossible. | Unresolved | P0 (`pg_hba.conf`) |
| A5 | The PostgreSQL major versions on 5432 and 5433 are known and ≥ 14 (`client_connection_check_interval`, `BEGIN ATOMIC`) | Unresolved | P0 |
| A6 | libpg_query matching those versions can be built or loaded on Windows offline, and its normalisation strips literals and comments | Assumed | S2 |
| A7 | pgJDBC behaviour (client-side splitting, maxRows, cancel, transaction state, command type) is as described | Assumed | S3 |
| A8 | The GitLab Windows runner can run `initdb`/`pg_ctl` and Edge, and is domain-joined | Assumed | S5, S6 |
| A9 | The SonarQube server's version and edition support the Java and JS analysers, scanner JRE provisioning and the needed gate conditions | Assumed | P0, S4 |
| A10 | The external server supports TLS | Unresolved | P0 |
| A11 | Users' PostgreSQL connection limits allow 2 connections per session | Assumed | P0 |
| A12 | An internal artifact repository exists, or the file-bundle process is acceptable | Unresolved | P0 |
| A13 | Cancellation reaches PostgreSQL on Windows and measurably releases locks and backend activity | Assumed | S3, P7 |
| A14 | GitLab Runner can be installed and updated offline | Unresolved | P0 |

All decisions in §11.3 are currently **Unresolved**.

#### 11.3 Decisions requiring explicit approval

Format for each: recommended default · reasoning · effect of choosing differently · can work proceed without it?

- **D0: Backend stack change to Java 21 + Spring Boot + Waffle** (replaces Node/Fastify).
  - *Reasoning:* the only way to reuse Waffle directly.
  - *Alternatives:* Node plus a token issued by the existing Java app adds a trust hop and new code in the existing app. Node with its own SSPI library replaces Waffle, which has been ruled out.
  - *Blocks:* **P0 sign-off and everything after.**
- **D1: Internal identity mapping and the DataAdmin source.**
  - *Default:* PostgreSQL role = lowercase `sAMAccountName`, from configured domains only, plus an override table. DataAdmin = AD group `<DOMAIN>\DataAdmin` (or the existing app's role store if that's where it lives). Checked on every request. App session max 8 h.
  - *Effect of alternatives:* a different naming convention changes only the mapper config. If DataAdmin is a role stored by the existing application, the new service needs a read-only, server-side integration with that store. **That design does not exist yet** and becomes a P0 design item.
  - *Blocks:* P4 (DataAdmin) and P6 (mapping).
- **D2: External credential handling.**
  - *Default:* §2.5. Server-fixed host and port, database allow-list, username/password per session, not kept after connecting, no "remember me", `autocomplete="off"`, throttling 5/5 min, TLS `verify-full` if available.
  - *Effect of alternatives:* free host entry creates SSRF risk (not recommended). Saving credentials needs DPAPI storage and a policy review. Free database entry is acceptable because CONNECT privilege still applies.
  - *Blocks:* P6.
- **D3: Identity model for backend → PostgreSQL connections.**
  - *Default:* users' own credentials for both instances. Internal uses I-A in the MVP, with I-B (delegation) evaluated as the target. I-C is rejected unless I-A and I-B both fail. No shared or unrestricted accounts.
  - *Effect of alternatives:* I-B removes the second password prompt but needs AD changes. I-C adds a privileged component.
  - *Blocks:* P6.
- **D4: SQL audit policy.**
  - *Default:*
    - normalised text plus an HMAC of the full text (HMAC only if the parser fails);
    - full raw text off;
    - intent before execution, outcome after, with spool;
    - **fail-closed** for both instances;
    - hash chain;
    - audit DB on a **separate PostgreSQL cluster/port** not administered by DataAdmin superusers (or on 5433 with tamper evidence only, if accepted).
  - *Effect of alternatives:* full text captures secrets and personal data. Fail-open allows unaudited changes during outages. Co-hosting on 5433 means superusers could tamper without being stopped (it would still be detected).
  - *Blocks:* P3 (schema location) and P4/P7 (behaviour). Fail mode is a config switch, so building can start with fail-closed.
- **D5: Supported PostgreSQL versions and SQL limitations.**
  - *Default:* the exact majors found in P0. Single-statement execution. Scripts only if S2 passes. No `COPY` via stdin/stdout, `LISTEN`, or psql meta-commands. Server-side cursors and "fetch more" post-MVP.
  - *Effect of alternatives:* supporting extra majors means a parser build per major and extra test clusters.
  - *Blocks:* P1 S2/S3.
- **D6: Docker.**
  - *Default:* not used now. The design stays compatible (§7.7). Note the Windows-host constraint from Waffle.
  - *Effect of alternatives:* allowing Docker later only adds a Testcontainers harness implementation.
  - *Blocks:* nothing.
- **D7: Toolchain versions and distribution.**
  - *Default:* Java 21 LTS (or 25 LTS if that's the organisational standard), Node 24 LTS for build and test only, Maven 3.9.x, versions pinned exactly. Internal artifact repository if one exists, otherwise the file-bundle intake (§7.1), with quarterly updates. Exact patch versions are fixed in P2 after S4.
  - *Effect of alternatives:* an older Node or Java may restrict Vite/Vitest/Spring versions.
  - *Blocks:* P1 S4.
- **D8: SonarQube.**
  - *Default:* use the installed server. Gate as in §8.3. If it's Community Build, enforce on `main` after merge plus a blocking status check. MR decoration if Developer edition or higher.
  - *Effect of alternatives:* none on the design. Only where enforcement happens changes.
  - *Blocks:* P2.
- **D9: MVP scope.**
  - *Default:* §10.1.
  - *Effect of alternatives:* dropping metadata browsing or transactions shortens P9–P10 without weakening security. Adding inline editing adds a phase of roughly P9's size.
  - *Blocks:* P0 sign-off.
- **D10: Transaction behaviour and limits.**
  - *Default:*
    - auto-commit by default; manual mode with RC/RR/SERIALIZABLE;
    - max rows 1,000; response 10 MB; cell 64 KB;
    - statement limit 60 s external / 300 s internal;
    - idle 15 min; idle-in-transaction 10 min; max transaction age 60 min;
    - heartbeat abandonment 120 s;
    - 3 sessions per user, 50 globally;
    - app session idle 30 min / absolute 8 h;
    - DBAs asked to set role-level backstops and `client_connection_check_interval`.
  - *Effect of alternatives:* all values are configuration, so changing them has no architectural effect.
  - *Blocks:* nothing. Implementation can proceed with the defaults.
- **D11: Inline editing.**
  - *Default:* **deferred** (§4).
  - *Effect of alternatives:* including it adds optimistic-concurrency design and testing to the MVP.
  - *Blocks:* nothing.
- **D12: Deployment topology.**
  - *Default:* single backend instance on Windows Server as a service, gMSA, HTTPS from the internal CA, SPA in the same JAR, no reverse proxy.
  - *Effect of alternatives:* multiple instances require the scaling work in §10.3. A reverse proxy requires guaranteed Kerberos (NTLM breaks through proxies).
  - *Blocks:* P12 only.
- **D13: Audit retention, access and recovery.**
  - *Default:* 12 months online in monthly partitions, 7 years archived with manifests, purge needs two-person approval, `pgweb_audit_reader` for named auditors, outage and spool runbooks, pre-upgrade dump.
  - *Effect of alternatives:* only configuration and operations change.
  - *Blocks:* P12 (P3 can proceed).
- **D14: Other decisions.**
  - **(a) Separate service or module inside the existing app.**
    - *Default:* a separate Spring Boot service, reusing the same Waffle setup.
    - *Reasoning:* independent release cycle and a smaller blast radius.
    - *Alternative:* embedding shares the session and role store but couples the releases.
    - *Blocks:* P2.
  - **(b) Companion connection per session.**
    - *Default:* yes.
    - *Reasoning:* needed for cancel escalation, lock diagnostics and metadata.
    - *Alternative:* without it, cancel escalation needs a privileged monitor account and metadata is blocked while a query runs.
    - *Blocks:* P6.
  - **(c) Refuse superuser PostgreSQL sessions through the web app.**
    - *Default:* yes on both instances.
    - *Alternative:* allowing them means the app can't enforce timeouts or protect the audit trail against that user.
    - *Blocks:* P6.
  - **(d) Supported browser.**
    - *Default:* Microsoft Edge (managed version) and Chrome if it's deployed. Desktop only.
    - *Blocks:* nothing.
  - **(e) Service wrapper.**
    - *Default:* WinSW, or Procrun if that's already the organisational standard.
    - *Blocks:* P12.

#### 11.4 Safely deferred until after the MVP

- Inline editing.
- Export.
- Fetch-more cursors.
- Tabs and history.
- Saved credentials.
- Kerberos delegation (if I-A works).
- Audit UI.
- Multi-instance and HA.
- Container deployment.
- SIEM forwarding.
- Approval workflows.

---

### 12. Prerequisites and compatibility checks before implementation

**Information to collect (Phase 0):**
1. PostgreSQL major/minor version, `pg_hba.conf` auth methods, TLS status, `max_connections` and role connection limits for **5432** and **5433**.
2. Internal PostgreSQL account naming convention; whether those accounts have passwords.
3. Where `DataAdmin` is defined (AD group name, or the existing app's role store).
4. The existing app's Java version, Spring/Spring Security version, Waffle version and configuration (filter vs Spring Security, NTLM/Kerberos settings).
5. Windows Server version for the app host and runners; domain functional level; whether a gMSA and SPN registration are allowed.
6. Browser versions and GPO for intranet-zone/automatic Negotiate.
7. SonarQube version, edition, installed language analysers and plugins, existing quality gates/profiles, scanner JRE provisioning, GitLab integration.
8. GitLab version, Runner version and executor options, whether a Windows runner can be domain-joined.
9. Whether an internal artifact repository (Nexus/Artifactory/npm or Maven mirror) exists, and how dependency transfer is approved.
10. Organisational standards for Java, Node and the service wrapper; any secret store.
11. Audit retention and legal requirements; who the auditors are.
12. Where the audit DB can be hosted (separate cluster/port allowed?).

**Compatibility matrix to verify (Phase 1, recorded in `docs/compatibility-matrix.md`):**

| Pair | Check |
|---|---|
| Java ↔ Spring Boot ↔ Spring Security ↔ Waffle module ↔ JNA | S1: works end to end on target Windows |
| pgJDBC ↔ PostgreSQL majors (5432, 5433) | S3: SCRAM, TLS, cancel, maxRows, transaction state |
| libpg_query release ↔ PostgreSQL majors ↔ Windows build (MSVC/WASM) ↔ JNA | S2 |
| Flyway Community ↔ audit DB PostgreSQL version | S4/P3 |
| Node LTS ↔ Vite ↔ Vitest ↔ React ↔ CodeMirror ↔ ESLint | S4 (native packages for win32-x64 present offline) |
| Playwright ↔ installed Edge version | S6 |
| SonarQube server ↔ sonar-maven-plugin / scanner ↔ JS analyser's Node requirement ↔ JaCoCo/lcov import | S4 |
| GitLab Runner (Windows, shell/PowerShell) ↔ toolchain ↔ `initdb`/`pg_ctl` permissions | S5 |
| WinSW/Procrun ↔ Windows Server version ↔ Java | P12 (early smoke in S4) |

**Environment prerequisites before Phase 2:**
- A clean, offline Windows VM for the install rehearsal.
- A domain-joined Windows GitLab runner with the toolchain installed.
- A test domain account (DataAdmin and non-DataAdmin variants).
- A SonarQube project and token.
- Approval of the first dependency intake list.

---

## Next step

Review this plan, especially **D0** (the Java backend), **D1–D4** (identity, credentials and audit, including fail-closed) and **D14a–c**. Once it is explicitly approved, with any changes, implementation begins with **Phase 0 only**.
