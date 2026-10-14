# Compatibility Matrix (draft)

> **Phase:** 0 draft; rows are promoted to **Tested** only by Phase 1 spikes with recorded evidence.
> **Plan reference:** `docs/implementation-plan-v2.md` §12. No versions are pinned yet; exact patch versions are fixed in Phase 2.

| # | Components | Candidate versions | Check | Spike | Status | Evidence |
|---|---|---|---|---|---|---|
| C1 | Java ↔ Spring Boot ↔ Spring Security ↔ Waffle Spring module ↔ JNA | Java 21 LTS; Boot 3.5.x (Security 6) or 4.0.x (Security 7); Waffle 3.x | A domain user authenticates via Negotiate (Kerberos) in Edge; SID and groups readable; DataAdmin resolved. Whether Waffle supports Spring Security 7 is unknown. | S1 | Assumed / Unresolved | — |
| C2 | Existing app's Waffle setup ↔ new service | Unknown (inventory §3) | The same configuration approach works in the new service | S1 | Unresolved | — |
| C3 | pgJDBC ↔ target PostgreSQL majors | pgJDBC 42.7.x; PG version unknown | SCRAM, TLS, `cancel()`, `setMaxRows`, transaction state, command type, client-side splitting behaviour | S3 | Assumed | — |
| C4 | Vert.x PG Client (alternative) ↔ target PostgreSQL | Current 4.x/5.x | Prepared queries leave splitting to the server; cancel; bounded fetch | S3 | Assumed | — |
| C5 | libpg_query ↔ PostgreSQL major ↔ Windows (MSVC DLL or WASM in JVM) ↔ JNA | Release matching the target major | Loads offline on Windows; passes 100% of the corpus; normalisation strips literals and comments | S2 | Assumed | — |
| C6 | Flyway Community ↔ audit DB PostgreSQL version | Current | Migrations run; the PostgreSQL version is supported by the Community edition | S4 / P3 | Assumed | — |
| C7 | Node.js ↔ Vite ↔ Vitest ↔ React ↔ CodeMirror 6 ↔ ESLint | Node 24 LTS (workstation has 24.20.0) | Offline install with win32-x64 native packages; build and tests pass | S4 | Assumed | — |
| C8 | Playwright ↔ installed Edge | Current Playwright; Edge version per environment (workstation: 155.0.4283.45) | `channel: 'msedge'` launches without downloading browsers; Negotiate works on a domain-joined runner | S6 | Assumed | — |
| C9 | SonarQube server ↔ sonar-maven-plugin / scanner ↔ JS analyser's Node requirement ↔ JaCoCo XML / lcov import | Server version unknown | Both projects analyse offline; coverage imported; gate status readable from CI | S4 | Assumed | — |
| C10 | GitLab Runner (Windows, PowerShell shell executor) ↔ toolchain ↔ `initdb`/`pg_ctl` permissions | Unknown | Throwaway cluster lifecycle including cleanup after a forced failure | S5 | Assumed | — |
| C11 | Service wrapper (WinSW or Procrun) ↔ Windows Server ↔ Java | Unknown | Service installs, starts, stops gracefully and runs under a gMSA | S4 smoke / P12 | Assumed | — |
| C12 | Test PostgreSQL binaries ↔ target PostgreSQL major | Workstation has 18.6; targets unknown | The test harness uses the same major version as production | P0 / S5 | Unresolved | — |
