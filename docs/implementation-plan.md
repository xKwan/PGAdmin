# PostgreSQL Web Query Interface: Implementation Plan

> **Status:** Proposed, awaiting review and approval
> **Date:** 2026-10-14
> **Repository state at planning time:** empty git repository (`main`, no commits)

---

## 1. Understanding of the project

You want a browser-based PostgreSQL client that feels like DBeaver: a connection explorer, a SQL editor and a results grid. It must run as a governed service. The browser never sees credentials or talks to PostgreSQL directly. The backend handles identity, authorisation, execution limits and auditing.

The main architectural point is that **this is a remote SQL-execution service**. Any authorised user can run arbitrary SQL, so the security boundary cannot sit in the SQL text. It has to come from three places:

1. **Backend authorisation**: who may open which connection, and at which access level.
2. **PostgreSQL privileges**: which login role the session runs as, and what that role can do.
3. **Execution controls the backend enforces outside the SQL session**: wall-clock timeouts, row and byte limits, concurrency caps and audit.

Keyword filtering and regex checks are only there to help users avoid mistakes. They are not a security control.

---

## 2. Proposed architecture and technology choices

```
Browser (React SPA)
   │  HTTPS, same-origin, HttpOnly session cookie + CSRF token
   ▼
API server (Node.js + Fastify)
   ├─ AuthN (OIDC) / server-side sessions
   ├─ AuthZ policy (user → connection profile → access level)
   ├─ DB Session Manager (pinned pg connections, reaper, limits)
   ├─ Query Engine (single-statement execution, cursors, timeouts, cancel)
   ├─ Audit writer (append-only)
   └─ Secret resolver (env / secret store)
   │                                  │
   ▼                                  ▼
Target PostgreSQL DBs            App metadata DB (PostgreSQL)
(tiered login roles)             users/role mappings, sessions, audit log
```

### Frontend

| Concern | Choice | Reason |
|---|---|---|
| Framework | React (JavaScript, JSX only), Vite | As required |
| SQL editor | **CodeMirror 6** (`@codemirror/lang-sql`, PostgreSQL dialect), wrapped in a small in-house React component | ~5–10× smaller than Monaco. It is modular, needs no web-worker setup in Vite and includes a PostgreSQL dialect. It also works in jsdom, so Vitest + RTL can test the editor. Monaco doesn't run in jsdom and would push editor tests into E2E only. It handles selection ranges well (needed for "execute selection") and has good accessibility support. |
| Result grid | TanStack Table + TanStack Virtual | Headless, so we control rendering (NULLs, truncation markers, types). Virtualisation keeps 1,000+ row results smooth. |
| Server state | TanStack Query | Loading, error and retry states and cache invalidation without custom plumbing |
| Layout | Resizable split panes (`react-resizable-panels`) | Explorer / editor / results layout like DBeaver |
| Testing | Vitest, React Testing Library, `@testing-library/user-event`, MSW (Mock Service Worker) | MSW mocks at the network layer, so components are tested against realistic HTTP behaviour |

### Backend

| Concern | Choice | Reason |
|---|---|---|
| Runtime | **Node.js LTS (24.x), JavaScript (ESM)** | One language across the stack, and shared validation constants. Mature `pg` driver. Good SonarQube JS analysis. |
| Framework | **Fastify** | Built-in JSON-schema request/response validation (Ajv), so unvalidated input and accidental field leaks are rejected by default. Low overhead. Official security plugins (`@fastify/helmet`, `@fastify/rate-limit`, `@fastify/csrf-protection`, `@fastify/cookie`, `@fastify/session`). |
| PG driver | `pg` (node-postgres) + `pg-cursor` | Dedicated clients per session (needed for transactions). Cursors give bounded fetches. Extended-protocol mode (see §3). |
| Statement parsing | `libpg-query` (PostgreSQL's real parser, WASM build) | Used only for statement splitting, classification and UX warnings. Never used as a security boundary. |
| Auth | OIDC via `openid-client` (pending your decision) | Delegates identity, MFA and lifecycle to your IdP |
| Logging | Pino (Fastify default) with redaction paths | Structured logs; credentials redacted at the logger level |
| Migrations | `node-pg-migrate` | Plain SQL/JS migrations for the app metadata DB |
| Testing | **Vitest** (unit + integration), Fastify `inject()` for HTTP-level tests, **Testcontainers** (real PostgreSQL in Docker), **Playwright** (E2E) | Same runner and coverage format (lcov) as the frontend, so Sonar sees one consistent setup. Integration tests run against real PostgreSQL, not mocks. |

**Alternatives considered:** Java/Spring Boot or Python/FastAPI would both work. Node.js is recommended for a single-language codebase and shared tooling. If your organisation has a backend platform standard, that should win.

### Repository layout (npm workspaces monorepo)

```
apps/web/          React SPA
apps/api/          Fastify API
packages/shared/   error codes, API constants, shared validation schemas
infra/             docker-compose (dev PG targets, app DB, optional SonarQube), sample role SQL
docs/adr/          Architecture Decision Records
docs/              threat model, runbooks, API spec
.github/workflows/ (or your CI)  pipeline definitions
```

---

## 3. Key design decisions (connections, transactions, errors, concurrency)

### Which PostgreSQL identity runs the query (most important decision)

- **Recommended: tiered login roles per connection profile.** For example, `appq_reader`, `appq_writer` and optionally `appq_ddl` for each target database. Each is a distinct LOGIN role with least-privilege GRANTs, `NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS` and a `CONNECTION LIMIT`. The backend maps *(app user, connection profile)* to an access level, which maps to one login role. Credentials are held server-side only.
- **Rejected: one login role plus `SET ROLE <user>`.** Any user running arbitrary SQL can execute `RESET ROLE` and get the login role's privileges back. This is bypassable, so it won't be presented as a control.
- **Alternative: per-user PostgreSQL accounts.** This gives the best attribution inside PostgreSQL, but needs per-user credential provisioning (e.g. Vault dynamic secrets). It's possible later, and the design leaves room for it.
- **Attribution:** `application_name` will be set to `pgweb:<userId>:<sessionId>` for DBA visibility. Users can override it with `SET`, so **the app audit log is the authoritative record**, not `pg_stat_activity`.

### Database sessions and transaction state across requests

- A "DB session" is a **dedicated `pg.Client` pinned to (app session, connection profile, session ID)** and held in the API process. This is what makes `BEGIN … COMMIT` across HTTP requests, `SET`, and temp tables behave as they do in DBeaver.
- Session IDs are random, server-generated and **bound to the owning app user**. Every request re-checks ownership (cross-user access returns 404, not 403, so session IDs can't be probed).
- **Limits:** max DB sessions per user (e.g. 5), a global max, and a per-role `CONNECTION LIMIT` on the PostgreSQL side.
- **Reaper:** closes sessions after an idle timeout (e.g. 15 min), on logout, on app-session expiry, and on server shutdown. Idle-in-transaction sessions are rolled back and closed sooner (e.g. 5 min), backed by PostgreSQL's `idle_in_transaction_session_timeout`.
- **Consequence:** the API is **stateful**. That means one instance, or sticky routing by session for multiple instances. If the instance restarts, sessions are lost and the UI shows "session expired, reconnect" (open transactions are rolled back by PostgreSQL). This trade-off needs sign-off (Question 4).

### Execution modes

- **Auto-commit (default):** each statement commits on its own.
- **Manual transaction mode:** the UI shows transaction state (`IDLE` / `IN TRANSACTION` / `FAILED – rollback required`), read from the driver's transaction status. It also shows Commit/Rollback buttons and a pending-changes indicator. Statements that can't run inside a transaction block (`VACUUM`, `CREATE INDEX CONCURRENTLY`, etc.) show PostgreSQL's own error. They are not silently rewritten.

### Timeouts and cancellation (enforced outside the user's session)

- `statement_timeout`, `lock_timeout` and `idle_in_transaction_session_timeout` are set per role (`ALTER ROLE … SET`) as defence in depth. **Users can override these with `SET`**, so they aren't relied on alone.
- **The authoritative control is a backend wall-clock timer.** When it expires, the backend calls `pg_cancel_backend(pid)` over a separate control connection. A role may cancel its own backends. If cancel fails, it escalates to closing the connection. The same mechanism runs user-initiated **Cancel**.
- Connect timeout and TCP keepalive are configured on every client.

### Result bounding

- Rows are read through `pg-cursor` up to a `maxRows+1` limit (default 1,000, configurable per profile), then the cursor closes and the response is flagged `truncated: true`. **The SQL is never rewritten** (no injected `LIMIT`).
- There's also a byte budget per response (e.g. 10 MB) and per cell (e.g. 64 KB). Oversized cells are truncated during serialisation and marked as such.
- "Fetch more" or paginate-by-cursor is deferred, because holding a portal open across requests keeps a transaction open.

### Type representation

- `int8`, `numeric` and `money` are returned as **strings** (JavaScript numbers lose precision).
- Date/time types are returned as PostgreSQL text, with no timezone conversion.
- `bytea` is returned as truncated hex. `json`/`jsonb` keep their structure.
- Arrays and unknown or extension types come back as text.
- NULL is a distinct sentinel, shown visually differently from the string `'NULL'` and from an empty string.

### Concurrency

- **One in-flight statement per DB session.** A second request gets `409 SESSION_BUSY`. The `pg` client itself serialises queries; this rule just makes that explicit.
- Separate sessions, including for the same user, run in parallel.
- Request rate limits apply per user.

### Errors

- One error envelope for everything: `{ code, message, sqlState?, position?, hint?, correlationId }`.
- For **the user's own SQL**, the backend passes through PostgreSQL's message, SQLSTATE, position and hint. These are needed for debugging and only show what that role can see.
- **Connection, auth and infrastructure errors** (wrong password, DNS, TLS, pool exhaustion) are mapped to generic codes (`CONNECTION_FAILED`, `TARGET_UNAVAILABLE`). Details stay in server logs only.
- No stack traces, hostnames or connection strings ever reach the client.

---

## 4. Phased roadmap

**Ordering rationale:** security foundations (authN/authZ, audit, error model) come *before* any endpoint that can execute SQL. That way no phase ever ships an unauthenticated SQL-execution path. Backend capability lands before the UI that uses it, so the UI is built against a real contract. Frontend shell work (Phase 4) only depends on Phase 1 and can run in parallel with Phases 2–3 if wanted, using MSW mocks.

```
P0 Architecture ─► P1 Scaffolding/CI/Sonar ─► P2 Backend foundation ─► P3 AuthN/AuthZ/Audit ─┐
                              └──────────────► P4 Frontend shell (parallel-capable) ─────────┤
                                                                                             ▼
                                         P5 Connection management (full stack)
                                                         ▼
                                         P6 Query execution + editor + results (full stack)
                                                         ▼
                                         P7 Transactions & destructive-op safeguards
                                                         ▼
                                         P8 Security hardening + E2E + security test suite
                                                         ▼
                                         P9 Deployment readiness & documentation   ═══ MVP ═══
                                                         ▼
                                         P10+ Post-MVP enhancements
```

---

### Phase 0: Requirements clarification and architecture

1. **Objectives:** settle the open decisions in §7 and record them, so later phases don't make irreversible choices by accident.
2. **Scope:** answers to the §7 questions; a threat model (STRIDE over the browser → API → PG flow); a data-classification stance on audit SQL text.
3. **Artifacts:** `docs/adr/0001-backend-stack.md`, `0002-pg-identity-model.md`, `0003-db-session-model.md`, `0004-auth.md`, `0005-result-limits.md`; `docs/threat-model.md`; `docs/requirements.md` (MVP vs deferred).
4. **Dependencies:** stakeholder answers.
5. **Testing/static analysis:** none (documents only). This phase defines the quality-gate thresholds used later.
6. **Acceptance criteria:** every question in §7 has a recorded decision in an ADR; the threat model lists each identified threat with a mitigation phase or an accepted-risk note; stakeholder approval is recorded.
7. **Risks:** an undecided identity model would block Phase 3 and Phase 5.
8. **Definition of done:** ADRs committed and approved.

### Phase 1: Scaffolding, conventions, CI skeleton, SonarQube wiring

1. **Objectives:** get a reproducible dev environment and quality gates in place *before* feature code, so every later phase is measured from day one.
2. **Scope:**
   - npm workspaces monorepo with Vite React app and Fastify app skeletons (hello-world level).
   - ESLint (flat config, `eslint-plugin-react`, `react-hooks`, `jsx-a11y`, `security`) and Prettier.
   - `.editorconfig`, `.nvmrc`, `.gitattributes` (CRLF/LF handling on Windows).
   - `docker-compose` with a dev target PostgreSQL, the app metadata DB and optional local SonarQube.
   - CI pipeline skeleton; `sonar-project.properties`; commit conventions (Conventional Commits).
3. **Files:** root `package.json`, `eslint.config.js`, `.prettierrc`, `apps/web/{vite.config.js,vitest.config.js,src/main.jsx}`, `apps/api/{src/server.js,vitest.config.js}`, `infra/docker-compose.yml`, `sonar-project.properties`, CI workflow, `CONTRIBUTING.md`.
4. **Dependencies:** P0 decisions; Node LTS, Docker, CI platform and SonarQube access.
5. **Testing:** one trivial test per app to prove the pipeline. Coverage (V8 provider → lcov) is wired into Sonar. Sonar analysis runs with `sonar.qualitygate.wait=true`.
6. **Acceptance criteria:**
   - `npm ci && npm run lint && npm test` exits 0 on a clean clone.
   - `npm run dev` serves the SPA and `GET /api/health` returns 200.
   - The CI run is green, and the SonarQube project shows a passing gate with coverage reported for both apps.
   - A deliberately introduced lint error makes CI fail (verified once, then reverted).
7. **Risks:** SonarQube Community Build lacks branch/PR analysis, so gating is main-branch only unless Developer edition or Cloud is available.
8. **Definition of done:** pipeline green, gate passing, conventions documented.

### Phase 2: Backend foundation

1. **Objectives:** a secure-by-default API skeleton (config, logging, error model, validation, app DB, test harness) for every later feature to build on.
2. **Scope:**
   - Config loader that validates at startup and fails fast.
   - Secret resolver interface: env vars now, secret-store adapter later.
   - Pino with redaction; correlation IDs; global error handler and safe error envelope.
   - JSON-schema validation on all routes with `additionalProperties: false`.
   - App DB connection and migrations; health/readiness endpoints; graceful shutdown.
   - Testcontainers harness with a **production-safety guard**.
3. **Modules:** `apps/api/src/{config/,lib/logger.js,lib/errors.js,plugins/,db/appDb.js,db/migrations/,routes/health.js}`, `apps/api/test/{helpers/pgContainer.js,helpers/safetyGuard.js}`.
4. **Dependencies:** P1.
5. **Testing:**
   - Unit: config validation, error mapping, log redaction (assert that secrets never appear in serialized logs).
   - Integration: migrations up/down against Testcontainers PG.
   - **Safety guard:** the test runner refuses to start unless the DB host is a Testcontainers-managed container, or an explicitly allow-listed test host with a DB name ending in `_test`. The guard has its own tests.
6. **Acceptance criteria:**
   - Invalid config makes startup exit non-zero with a clear message.
   - Unknown request fields return 400.
   - An unhandled error returns the envelope with a `correlationId` and no stack trace (asserted by test).
   - Log output in tests contains no configured secret values.
   - Coverage ≥ 85% lines / 80% branches for `apps/api`; Sonar gate passes.
7. **Risks:** Testcontainers needs Docker in CI and on Windows dev machines (Docker Desktop/WSL2).
8. **Definition of done:** as above, plus `docs/dev-setup.md`.

### Phase 3: Authentication, authorisation, sessions and audit framework

1. **Objectives:** identity, authorisation policy and audit must exist before any endpoint touches a target database.
2. **Scope:**
   - OIDC login/logout (or the chosen alternative).
   - Server-side app sessions in the app DB: HttpOnly, Secure, `SameSite=Lax` cookie; rotated on login; idle and absolute timeouts.
   - CSRF token on all state-changing requests.
   - Role model (e.g. `viewer`, `reader`, `writer`, `admin`) and per-connection grants.
   - Authorisation middleware (deny by default).
   - Append-only audit table (app DB role has INSERT/SELECT only, no UPDATE/DELETE) and audit service.
   - Security headers via helmet (strict CSP); rate limiting.
3. **Modules:** `apps/api/src/{auth/,authz/policy.js,authz/middleware.js,audit/auditService.js,plugins/security.js}`, migrations for `users`, `role_bindings`, `app_sessions`, `audit_events`.
4. **Dependencies:** P2; IdP registration (client ID/secret, redirect URIs) or a decision on local accounts. A local mock OIDC provider is used for tests.
5. **Testing:**
   - Unit: policy decision table (every role × connection × action, including deny cases).
   - Integration: login flow against a mock OIDC server; session fixation (ID rotates on login); expired and tampered cookies rejected; CSRF missing or invalid → 403; every protected route returns 401 unauthenticated (an **auto-generated test iterates the route table**, so new routes can't skip auth).
   - Audit rows written for login, logout and authorisation denials.
6. **Acceptance criteria:**
   - 100% of non-public routes reject unauthenticated requests (route-table test).
   - Authorisation and session modules ≥ 95% branch coverage.
   - The response-headers test asserts CSP, `X-Content-Type-Options`, `frame-ancestors 'none'` and HSTS (in prod mode).
   - Attempts to UPDATE/DELETE an audit row with the app role fail (integration test).
   - Sonar: 0 vulnerabilities, all security hotspots reviewed.
7. **Risks:** IdP availability for CI (mitigated by the mock OIDC); deciding fail-closed vs fail-open when an audit write fails (Question 8).
8. **Definition of done:** as above, plus an ADR update if the auth approach changes.

### Phase 4: Frontend shell and reusable components

1. **Objectives:** the DBeaver-style layout and a reusable component library, built and tested against mocks.
2. **Scope:**
   - App shell: header with user menu, left connection-explorer pane, editor pane, results pane, resizable splits.
   - Reusable `Button`, `StatusBadge`, `Spinner`, `EmptyState`, `ErrorPanel`, `ConfirmDialog`, `Toast`.
   - API client (fetch wrapper that adds the CSRF header, handles 401 by redirecting to login, parses the error envelope).
   - TanStack Query setup; auth-aware routing; MSW handlers.
3. **Files:** `apps/web/src/{app/,components/common/,components/layout/,api/client.js,api/queryClient.js,auth/,mocks/handlers.js}`.
4. **Dependencies:** P1 (and the P3 API contract for auth endpoints, which can be mocked).
5. **Testing:**
   - Vitest + RTL for every component state (loading, empty, success, error).
   - API client: CSRF header attached, 401 handling, envelope parsing.
   - Accessibility checks with `vitest-axe`.
6. **Acceptance criteria:**
   - Every common component has tests for each documented state.
   - The layout renders without horizontal overflow at 1366×768 and 1920×1080 (Playwright visual check is added in P8; here, an RTL smoke test).
   - No `dangerouslySetInnerHTML` anywhere (lint rule enforced).
   - `apps/web` coverage ≥ 80% lines / 75% branches; Sonar gate passes.
7. **Risks:** design system choice (plain CSS modules recommended; no UI kit dependency unless an organisational standard exists).
8. **Definition of done:** as above.

### Phase 5: Database connection management (full stack)

1. **Objectives:** list authorised connection profiles; open and close DB sessions; show status and session info; handle failures and expiry.
2. **Scope:**
   - **Backend:**
     - Connection-profile registry (config file managed by ops in MVP; credentials referenced by secret ID, never stored inline).
     - `GET /connections` (filtered by authorisation).
     - `POST /sessions` (open), `DELETE /sessions/:id`, `GET /sessions` (mine).
     - DB Session Manager (pinned clients, ownership binding, per-user/global caps, idle reaper, shutdown cleanup); TLS to targets (`sslmode=verify-full` recommended).
     - `application_name` tagging.
     - Session info returned: server version, database, effective PostgreSQL role, backend PID, opened-at time.
     - Audit of open/close/failure.
   - **Frontend:** connection explorer listing profiles, a connect/disconnect action, status badges (connecting / connected / failed / expired), multiple concurrent sessions, and a session info panel.
3. **Modules:** `apps/api/src/{connections/profileRegistry.js,sessions/sessionManager.js,sessions/reaper.js,routes/connections.js,routes/sessions.js}`, `apps/web/src/features/connections/`.
4. **Dependencies:** P3 (authorisation), P4 (shell), and the decision on the PostgreSQL identity model. `infra/sql/roles.sql` holds sample least-privilege role scripts.
5. **Testing:**
   - Unit: session-manager limits and reaper timing (fake timers).
   - Integration (Testcontainers):
     - Connect succeeds as the mapped role.
     - Wrong credentials → generic `CONNECTION_FAILED`, and the response body contains neither password nor host (asserted).
     - Unreachable host → timeout within the configured bound.
     - **User B cannot see, use or close user A's session** (returns 404).
     - Per-user cap returns 429/409.
     - Server-side connection kill (`pg_terminate_backend`) is detected and reported as expired.
   - Frontend: all connection states, and the expired-session recovery flow.
6. **Acceptance criteria:**
   - Every bullet above has a passing test.
   - After the idle timeout, the PG backend is gone (`pg_stat_activity` assertion).
   - Session-manager branch coverage ≥ 95%.
   - Sonar gate passes.
7. **Risks:** stateful sessions limit horizontal scaling (Question 4); connection exhaustion on target DBs (mitigated by caps plus role `CONNECTION LIMIT`).
8. **Definition of done:** as above, plus an updated API spec.

### Phase 6: SQL editor, query execution and result rendering (full stack)

1. **Objectives:** the core loop: write SQL, run it, see bounded and correctly typed results or a clear error, and be able to cancel.
2. **Scope:**
   - **Backend:**
     - `POST /sessions/:id/execute` takes one statement. It runs in single-statement **extended-protocol mode**, so PostgreSQL itself rejects multi-statement payloads in one request. `pg`'s `queryMode: 'extended'` behaviour is confirmed in a spike at the start of this phase.
     - Cursor-bounded fetch with row, byte and cell limits; the type-serialisation layer; wall-clock timeout with `pg_cancel_backend` enforcement.
     - `POST /sessions/:id/cancel`; one-in-flight rule (409).
     - Result metadata: columns with type OIDs and names, rowCount / command tag, duration, truncated flag.
     - Audit of every execution (user, connection, session, statement text or hash per policy, classification, outcome, SQLSTATE, rows, duration, timestamps).
   - **Frontend:**
     - CodeMirror 6 editor with PostgreSQL highlighting. Ctrl+Enter executes the selection, or otherwise the statement under the cursor. "Execute script" splits server-side with `libpg-query` and runs statements sequentially, stopping on the first error.
     - Cancel button; status bar (running timer, duration, rows, affected count).
     - Virtualised results grid (NULL styling, type-aware alignment, truncated-cell marker, "results truncated at N rows" banner).
     - Error panel showing message, SQLSTATE, hint, and the error position highlighted in the editor.
3. **Modules:** `apps/api/src/{query/executor.js,query/serializer.js,query/limits.js,query/splitter.js,query/classifier.js,routes/execute.js}`, `apps/web/src/features/{editor/,results/,execution/}`.
4. **Dependencies:** P5. The `libpg-query` WASM build must install cleanly on Windows and CI (checked in the spike; fallback is a hand-written splitter that handles quotes, dollar-quoting and comments, with extensive tests).
5. **Testing:**
   - Integration (Testcontainers), covering:
     - SELECT/INSERT/UPDATE/DELETE/DDL as permitted by role, and permission-denied for disallowed ones (enforced by PostgreSQL privileges, asserted).
     - Multi-statement payload rejected.
     - `pg_sleep(60)` cancelled at the timeout even after `SET statement_timeout = 0` (this proves the backend control isn't bypassable via `SET`).
     - User cancel works.
     - 100k-row result is truncated at maxRows with bounded server memory.
     - Huge text cell truncated.
     - Type fidelity: int8 max, numeric with 30 digits, timestamptz, bytea, jsonb, arrays, NULL vs `''`.
     - Concurrent execute on the same session → 409.
     - Audit row exists for success, failure and cancel.
   - Frontend (RTL):
     - Execute selection vs. current statement.
     - Running, cancel, success, empty-result, affected-rows-only and error states.
     - NULL rendering.
     - Results never rendered as HTML: a cell containing `<img src=x onerror=…>` is shown as literal text (asserted).
6. **Acceptance criteria:**
   - All tests above pass.
   - Executor, serializer and limits modules ≥ 95% branch coverage.
   - The editor bundle-size increase is recorded and stays under a budget agreed in P0.
   - Sonar gate passes.
7. **Risks:**
   - Statement splitting edge cases (dollar quotes, `$$` in function bodies). Using PostgreSQL's real parser mitigates this.
   - `COPY … FROM STDIN` and `LISTEN` are unsupported in MVP and return a clear error.
8. **Definition of done:** as above.

### Phase 7: Transaction handling and destructive-operation safeguards

1. **Objectives:** explicit transaction workflows, plus honest, clearly labelled guard rails against accidental damage.
2. **Scope:**
   - Auto-commit / manual mode toggle per session; Commit/Rollback controls.
   - Transaction status from the driver (idle / in-transaction / failed). Typing `BEGIN` manually is also reflected correctly.
   - Warning when closing a tab or session with an open transaction.
   - Idle-in-transaction timeout surfaced in the UI.
   - **Safeguards (UX only, labelled as such):** parser-based classification (DDL / DCL / DML without WHERE / TRUNCATE / DROP). A confirmation dialog appears for high-risk classes. The configured profile can mark a connection as "production" (coloured banner, confirmation required for any write).
   - **Enforcement still comes from PostgreSQL role privileges.** A `reader` profile's role physically can't write.
3. **Modules:** `apps/api/src/sessions/transactionState.js`, `apps/api/src/query/classifier.js` (extended), `apps/web/src/features/transactions/`.
4. **Dependencies:** P6.
5. **Testing:**
   - Integration:
     - BEGIN in one request, INSERT in a second and ROLLBACK in a third leaves no row.
     - COMMIT persists.
     - A failed statement puts the transaction into FAILED state and the next statement returns 25P02 until rollback.
     - Session close mid-transaction rolls back.
     - Idle-in-transaction timeout fires.
   - Classifier unit tests over a corpus, including obfuscated forms (comments, mixed case, CTE-wrapped DELETE). Where the classifier can't classify, it **defaults to "requires confirmation"**.
   - RTL: confirmation flows, the production banner, open-transaction warning.
6. **Acceptance criteria:**
   - All the above pass.
   - The docs explicitly state the classifier isn't a security control (reviewed in PR).
   - Sonar gate passes.
7. **Risks:** users may treat confirmations as protection. This is mitigated by docs and by the privilege model.
8. **Definition of done:** as above.

### Phase 8: Security hardening, end-to-end and security test suite

1. **Objectives:** verify the system as a whole against the threat model, and close any gaps.
2. **Scope:**
   - Playwright E2E for key journeys: login → connect → query → results → transaction → logout; session expiry; cancel.
   - Security regression suite:
     - Cross-user session access.
     - CSRF.
     - XSS payloads in results, errors and connection names.
     - Auth bypass across the route table.
     - Rate limits.
     - Oversized request bodies.
     - Header and CSP checks.
     - Error responses contain no secrets (secret values are grep-asserted against all captured responses and logs in the E2E run).
   - Dependency audit (`npm audit --audit-level=high`, optionally OWASP Dependency-Check or Trivy).
   - Optional OWASP ZAP baseline scan.
   - Least-privilege checklist for target DBs. It verifies: no superuser; no `pg_read_server_files` / `pg_write_server_files` / `pg_execute_server_program`; no risky extensions (`dblink`, `postgres_fdw`, `plpython3u`) reachable by app roles; review of SECURITY DEFINER functions.
3. **Files:** `e2e/`, `apps/api/test/security/`, `docs/security/least-privilege-checklist.md`, `infra/sql/verify-role-privileges.sql`.
4. **Dependencies:** P7.
5. **Testing:** as listed. Playwright runs against docker-compose with the mock OIDC.
6. **Acceptance criteria:**
   - All E2E and security tests pass in CI.
   - 0 high/critical dependency vulnerabilities, or documented accepted exceptions.
   - Every threat-model item is mitigated or recorded as accepted risk.
   - The privilege-verification SQL passes against the reference role setup.
   - Sonar: 0 open vulnerabilities, 100% hotspots reviewed.
7. **Risks:** E2E flakiness, mitigated by deterministic fixtures and no arbitrary waits.
8. **Definition of done:** as above.

### Phase 9: Deployment readiness and documentation

1. **Objectives:** something operable and supportable.
2. **Scope:**
   - Production build: SPA served by the API or a reverse proxy, same origin.
   - Container image (non-root, read-only filesystem).
   - Config reference; secret-store integration.
   - Runbooks: rotate DB credentials, add a connection profile, grant user access, investigate the audit log, incident kill-switch (disable a profile or all execution via config).
   - Audit retention and export.
   - Health/readiness and graceful-drain behaviour.
   - Backup expectations for the app DB.
   - User guide.
3. **Files:** `Dockerfile`, `docs/{deployment.md,operations/,user-guide.md,api.md}`.
4. **Dependencies:** P8; target hosting decision.
5. **Testing:** container smoke test in CI (image boots and health passes); graceful shutdown closes all PG sessions (integration).
6. **Acceptance criteria:**
   - A fresh environment can be deployed from the docs alone (dry run by someone other than the author, if possible).
   - The image scan has no critical vulnerabilities.
   - The full pipeline is green.
7. **Definition of done:** MVP release candidate tagged.

### Phase 10+: Post-MVP enhancements (each a separate approval)

- Multiple editor tabs, each with its own session or sharing one.
- Query history (per user, stored server-side, subject to the audit and data policy).
- Schema/object browser. It uses app-managed **parameterised** catalog queries; this is where SQL-injection controls on application-managed queries matter most.
- "Fetch more" / cursor pagination within transactions.
- Result export to CSV/JSON (with spreadsheet formula-injection escaping and export audit).
- Saved queries/snippets; autocomplete from the schema.
- EXPLAIN visualisation.
- Admin UI for connection profiles and role bindings.
- Per-user PostgreSQL credentials (Vault dynamic secrets).
- Multi-instance HA with sticky routing.
- Approval workflow ("four-eyes") for production writes.

---

## 5. Testing and SonarQube strategy

### Tooling per layer

| Layer | Tooling | What it covers |
|---|---|---|
| Frontend unit and component | Vitest + RTL + user-event + MSW + vitest-axe | Component states, interactions, API client, a11y basics |
| Backend unit | Vitest | Policy, limits, serializer, classifier, config, error mapping |
| Backend HTTP | Vitest + Fastify `inject()` | Validation, authN/authZ, CSRF, error envelope |
| Backend ↔ PostgreSQL | Vitest + Testcontainers (PostgreSQL image pinned to the target major version) | Real connections, privileges, transactions, timeouts, cancel, types |
| End-to-end | Playwright | Whole user journeys and security regressions |
| Static analysis | ESLint (lint gate) + SonarQube | Bugs, vulnerabilities, hotspots, smells, duplication, coverage |
| Supply chain | `npm audit`; optional Trivy for image | Known vulnerable dependencies |

### Coverage thresholds

Coverage is measured by Vitest's V8 provider, which outputs lcov for Sonar. It is enforced in two places: the `vitest.config.js` thresholds fail the run locally and in CI, and the Sonar new-code gate.

| Scope | Lines | Branches |
|---|---|---|
| `apps/web` overall | ≥ 80% | ≥ 75% |
| `apps/api` overall | ≥ 85% | ≥ 80% |
| Security-critical modules (`auth/`, `authz/`, `sessions/`, `query/executor.js`, `query/limits.js`, `audit/`) | ≥ 95% | ≥ 95% |

### SonarQube quality gate (on new code)

- Coverage ≥ 80%.
- Duplicated lines ≤ 3%.
- 0 new bugs, 0 new vulnerabilities.
- 100% of security hotspots reviewed.
- Maintainability, reliability and security ratings all A.

### Blocking rules

The CI jobs are lint, unit-web, unit-api, integration-api, e2e (from P8), dependency-audit, sonar (with `qualitygate.wait=true`) and build. Every job is required. A phase is complete only when all jobs pass on the phase's final commit and the actual command output is reported. Any exception to a finding (e.g. a false-positive hotspot) needs a written justification in Sonar and stakeholder acknowledgement.

### Commands (expected once implementation begins)

```
npm ci
npm run lint
npm run test -w apps/web -- --coverage
npm run test -w apps/api -- --coverage            # unit
npm run test:integration -w apps/api              # Testcontainers, needs Docker
npx playwright test                               # from P8
npm audit --audit-level=high
npx sonar-scanner -Dsonar.qualitygate.wait=true   # or CI Sonar action
```

### Production safety for tests

- The test DB guard from Phase 2 applies.
- CI never holds production credentials.
- Integration tests create and destroy their own containers.
- Test roles and data come from fixtures checked into `apps/api/test/fixtures`.

---

## 6. Security approach and key risks

### Controls that can be reliably enforced

- **Authentication:** the backend authenticates every request; deny by default; a route-table test enforces it.
- **Authorisation:** server-side role → connection → access-level mapping, checked on every request.
- **Credentials:** server-side only; never in API responses or logs (asserted by tests).
- **Session isolation:** cross-user session isolation through ownership checks.
- **Database privileges:** PostgreSQL GRANTs and role attributes on distinct login roles. This is the real limit on what SQL can do.
- **Time, size and concurrency limits:** wall-clock timeout with cancel; row, byte and cell limits; per-user and global session caps; role `CONNECTION LIMIT`; rate limits.
- **Audit:** append-only audit written by the backend, independent of anything the user can change in SQL.
- **Web protections:** CSRF tokens plus SameSite cookies; strict CSP; React escaping with no raw HTML rendering; HttpOnly/Secure cookies; helmet headers.
- **Application-managed queries** (catalog browsing, metadata): always parameterised. Identifiers are only interpolated through `pg-format`-style identifier quoting from an allow-list.

### Things that are not boundaries, or need further design

- Keyword and parser classification of user SQL is only a UX safeguard. Anything allowed by the role's privileges can still be done (e.g. via functions or DO blocks).
- Session GUCs (`statement_timeout`, `default_transaction_read_only`, `application_name`, `search_path`) can be changed by the user. Read-only behaviour must come from privileges, not `default_transaction_read_only`.
- Within one PostgreSQL role, all users at that access level are indistinguishable to PostgreSQL. Per-user attribution relies on the app audit log unless per-user PG credentials are adopted later.
- **Data exfiltration:** an authorised reader can read everything their role can read. Row-level security or column privileges are database-design decisions for the data owners.
- **Audit SQL text may contain sensitive literals** such as PII or passwords in `ALTER ROLE … PASSWORD`. This needs a storage and retention policy (Question 8).
- **Stateful sessions:** an API restart drops open transactions (they are rolled back, so it's safe but disruptive).

### Top risks

1. A misconfigured PostgreSQL role grants more than intended. Mitigation: the privilege-verification script plus the checklist in P8.
2. Connection exhaustion on target DBs. Mitigation: layered caps.
3. Long-held transactions causing lock contention. Mitigation: idle-in-transaction timeouts plus UI warnings.
4. Dependency risk for `libpg-query` on Windows/CI. Mitigation: spike plus fallback.
5. Audit write failures. Mitigation: a fail-closed option.

---

## 7. Dependencies, assumptions and decisions needed

### Assumptions

- Single-tenant internal deployment.
- Users are employees authenticated through a corporate IdP.
- Target PostgreSQL is version 14 or later (`idle_session_timeout` needs 14+).
- Docker is available on dev machines and CI.
- Desktop browsers only (Chrome, Edge, Firefox; latest 2 versions).
- English UI.

### Open questions

1. **Backend stack:** is Node.js + Fastify (JavaScript) acceptable, or is there a mandated backend platform?
2. **Identity provider:** OIDC with which provider (Entra ID, Okta, Keycloak…)? Or are local accounts acceptable for MVP? (OIDC is recommended.)
3. **PostgreSQL identity model:** accept tiered service login roles per connection profile (recommended)? Or are per-user PostgreSQL accounts needed from day one?
4. **Deployment and scaling:** is a single API instance (or sticky sessions) acceptable for MVP given stateful DB sessions? What is the hosting target (VM, Kubernetes, Azure/AWS…)?
5. **Write and DDL access in MVP:** which app roles may run DML? DDL? Will any connection be marked "production"? Should production writes require confirmation (planned) or an approval workflow (post-MVP)?
6. **SonarQube:** self-hosted (which edition/version?) or SonarQube Cloud? Which CI platform (GitHub Actions assumed)?
7. **Secret storage:** env vars for MVP, or integrate a secret store now (Vault, Azure Key Vault, AWS Secrets Manager)?
8. **Audit policy:**
   - Store full SQL text, redacted text, or a hash?
   - What is the retention period?
   - Should execution **fail closed** if the audit write fails? (Recommended: yes.)
9. **Connection profile management:** config file managed by ops for MVP (recommended), or an admin UI in MVP?
10. **Default limits:** max rows (1,000), timeout (60 s), idle session (15 min), idle-in-transaction (5 min), sessions per user (5). Should any of these change?

---

## 8. Recommended MVP and completion criteria

**MVP = Phases 0–9.** That covers authenticated users connecting to authorised PostgreSQL profiles, running single statements or scripts in a CodeMirror editor, viewing bounded and correctly typed results, cancelling queries, using explicit transactions, and having every action audited, all behind least-privilege PostgreSQL roles.

Multiple editor tabs, query history, schema browser, export and other features are **post-MVP (Phase 10+)**.

### MVP completion criteria

- All P0–P9 acceptance criteria are met, with the CI evidence linked.
- The Sonar quality gate passes on `main`, with 0 open vulnerabilities and 100% of hotspots reviewed.
- Coverage thresholds in §5 are met, and enforced by failing builds.
- The E2E and security regression suites are green.
- Every threat-model item is mitigated or formally accepted.
- The least-privilege verification passes for each configured target DB role.
- Deployment from the docs is verified on a clean environment.

---

## 9. Next step

Review this plan and the open questions in §7. Once it's approved, with any changes, implementation begins with **Phase 0 only**, and the decisions are recorded as ADRs.
