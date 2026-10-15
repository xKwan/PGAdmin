# Spike S2: Report

**Result: PASS for PostgreSQL 18, on the developer workstation, with one change to the planned approach.**

**Planned approach that doesn't hold:** libpg_query's own normaliser can't be used for audit text. Audit text uses PostgreSQL's scanner instead (N1–N3).

**Not yet done:**
- the target PostgreSQL major versions (Unresolved in the inventory);
- a clean, air-gapped Windows VM;
- Windows Server;
- the GitLab runner.

## Environment

| Item | Value |
|---|---|
| Machine | Windows 11 Pro 10.0.26200 workstation (not air-gapped, not domain-joined) |
| Parser | libpg_query **18.1.0** (PostgreSQL 18 grammar), source pinned by SHA256 `05CA8A63…46D0A1` |
| Compiler | Zig **0.17.0** (`zig cc`, clang-based, target `x86_64-windows-gnu`, `-O2`), project-local |
| Artefact | `pg_query.dll`, 2,872 KB, SHA256 `3A8BFED51A557C5276E29298EB1273AE18EDD6FECBF0510EE98F2697A7557527` (**reproducible**, see B2) |
| Java binding | JNA 5.19.1 on Temurin 21.0.12.1+1 |
| Reference server | PostgreSQL 18.6 throwaway cluster (EDB binaries) |
| Maven | Offline (`mvn -o`), project-local repository |

## Plan S2 criteria

| Criterion (plan Phase 1) | Result |
|---|---|
| libpg_query loads offline on Windows | **PASS.** Built from pinned source with project-local Zig in about 3.5 minutes, no network, no Visual Studio. Loaded through JNA. **Deviation:** the plan said MSVC on a staging machine; Zig/MinGW was used instead (see B1). |
| Passes 100% of the §3.1 corpus for the confirmed PostgreSQL major | **PASS for PG 18:** 40/40 corpus items, every run. **Open:** the production major is Unresolved. If it isn't 18, rebuild from the matching libpg_query release (e.g. `17-6.2.5`) and rerun. |
| Normalisation strips literals and comments | **PASS, but not with libpg_query's normaliser**, which leaves literals in 7 statement forms (N2). Scanner-based masking leaves none of 28 forms (N3). |
| Under 5 ms per typical statement | **PASS:** parse plus audit masking p50 ≈ 0.02 ms and p99 ≈ 0.9–1.2 ms in every run. Worst single observation 5.0 ms (a 5.4 KB statement, one sample in about 4,000, in an earlier loop); 3.3–3.7 ms in the final loops. |

## Loop history (2026-10-15)

| Run | Result | Cause | Fix |
|---|---|---|---|
| manual, seed 777 | FAIL: 7 of 49 | (a) `auditText` used `pg_query_normalize_utility`, which skips DML (**N1**). (b) The reference counted results through pgJDBC: a `CALL`, or an empty query, gives neither rows nor an update count and looks like "no more results", so counting stopped early. (c) Comment-only and empty input. | (a) Separate normaliser findings test. (b) Reference switched to the Vert.x simple protocol. (c) See the next run. |
| manual, seed 777 | FAIL: 1 failure, 2 errors | (a) `pg_query_normalize` leaves literals in some utility statements (**N2**). (b) **Vert.x never completes a query with no statements** (V8); the reference hung for 30 s. | (a) Audit text now masks constants using the scanner (N3). (b) The reference appends a sentinel statement. An edge-case test pins V8. |
| manual, seed 778 | PASS (61 tests) | — | — |
| `Invoke-S2Spike` loop | **PASS 5/5** (61 tests) | Review: token-soup fuzzing gave only ~5% valid inputs, so statement counts were compared on few valid scripts | Added a structured fuzzer (~75% valid inputs) |
| loop (report replaced) | **PASS 5/5** (62 tests) | Review: a rebuild of the DLL gave a **different SHA256**. 11 bytes differed: the PE timestamp, the debug-directory timestamp and the CodeView GUID. | Build made reproducible (B2). The DLL changed, so the count restarted. |
| `s2-20261015-085734.json` | **PASS 5/5** (62 tests per run) | Final code and reproducible DLL | Master seed `368379360` |
| `s2-20261015-090221.json` | **PASS 5/5** (62 tests per run) | Independent confirmation, same code | Master seed `799414986` |

## Findings

| # | Finding | Status | Plan impact |
|---|---|---|---|
| P1 | Statement boundaries agree with the PostgreSQL server for all 40 corpus items. That covers: quotes; `E''` and `U&''` strings; `standard_conforming_strings` on/off; nested comments; `$$` and `$tag$` (nested); `DO`; plpgsql; `BEGIN ATOMIC` functions and procedures; multi-action rules; psql meta-commands (rejected); empty statements; Unicode. Every piece also runs on the server as exactly one statement. | **Tested** (PG 18) | Script splitting is feasible (plan §3.1 use 1) |
| P2 | 30–50 random multi-item scripts per run (≈230–300 statements): 0 mismatches against the server in 10 runs | **Tested** | — |
| P3 | Differential fuzzing against the server in every run: 250–400 token-soup inputs and 150–250 structured inputs (≈75% valid, 40% mutated). **0 disagreements:** whenever the parser rejected text the server rejected it too, and accepted scripts had equal statement counts. | **Tested** | — |
| P4 | `standard_conforming_strings` changes where statements end. The same text is 2 statements with `on` and 1 with `off`. The parser matches the server **only if told the session's setting**. | **Tested** | Script mode needs the session's real setting. Rule: script mode only when the session reports `standard_conforming_strings=on`, and a script may not change it. |
| P5 | Parser offsets are UTF-8 **byte** offsets. Converting them is required and is tested with multi-byte text. | **Tested** | Implementation rule |
| P6 | The top-level statement kind is correct for 23 statement types. **A data-modifying CTE (`WITH d AS (DELETE …) SELECT …`) is a `SelectStmt` at the top level.** | **Tested** | Confirms classification is for UX and audit metadata only, never a security boundary (as planned) |
| N1 | `pg_query_normalize_utility` normalises utility statements only; DML literals stay | **Tested** | Don't use it for audit text |
| N2 | `pg_query_normalize` (18.1.0) leaves literals in 7 of the 28 statement forms: `COMMENT ON … IS`, `COPY … PROGRAM`, `PREPARE`, `EXECUTE`, server `OPTIONS`, column `DEFAULT`, and `BEGIN ATOMIC` bodies. It does replace passwords in `CREATE/ALTER ROLE`. | **Tested** (re-checked every run) | Plan §6 assumed libpg_query normalisation removes all literals. **It doesn't.** |
| N3 | Scanner-based masking (`pg_query_scan_tokens`: drop comment tokens, replace every constant token with `$n`) leaves **no** literal or comment in any of the 28 forms, including passwords, dollar-quoted bodies, `U&''`, bit/hex and numeric constants. Comment markers inside strings are handled correctly. Output is deterministic and keeps identifiers. | **Tested** | Proposed audit-text method. The scanner has no `standard_conforming_strings` option, so for sessions with `off`, audit stores HMAC and metadata only. |
| V8 | **Vert.x 5.2.1 never completes a query whose text contains no statement** (empty, only `;`, or only comments), for both `query()` and `preparedQuery()`. The connection stays usable. pgJDBC returns an empty result. | **Tested** (7 inputs, every run) | The app must reject statement-free input **before** sending, using the scanner, and must put a timeout on every driver call. Adds to ADR-0009. |
| B1 | Zig produces a working MinGW-ABI DLL from the pinned source on Windows with no other tools | **Tested** | Avoids needing Visual Studio in the air gap. The DLL is a build artefact with a recorded SHA256, verified before each test loop. |
| B2 | **Reproducible build.** Two builds from the same pinned source, in **different folders**, gave byte-identical DLLs (SHA256 `3A8BFED5…7557527`). Zig has no deterministic-link switch for Windows (`--no-insert-timestamp`, `--Brepro` and similar are rejected or ignored). Two steps make it reproducible: `-ffile-prefix-map` removes the source path, and after linking the build zeroes exactly three link-time fields (COFF timestamp, debug-directory timestamp, CodeView GUID). | **Tested** | Anyone can rebuild inside the air gap and check that the DLL matches the recorded hash |
| S1 | libpg_query 18.1.0 fixes a heap out-of-bounds read/write in `pg_query_normalize` (GHSA-6ggm-xmc9-8ffg) | Upstream advisory | Pin ≥ 18.1.0 (or 17-6.2.5). A native parser runs untrusted text **inside the backend's process**, so a parser memory bug could crash it or leak memory. Added to the threat model as **T31**. |

## Conclusion for the plan

- **Script mode (plan §3.1 use 1):** feasible for PostgreSQL 18, with the rules in P4. Each piece is still sent through the server-enforced single-statement path (ADR-0009).
- **Single-statement check (use 2):** feasible. ADR-0009 already makes the server enforce it, so the parser adds an early, friendly error.
- **Classification (use 3):** feasible, for UX and audit only (P6).
- **Audit normalisation (use 4):** feasible with **scanner masking (N3)**, not libpg_query's normaliser (N2).
- **Delivery:** a Zig-built DLL loaded with JNA, pinned and checksummed. The WASM option wasn't needed. It would remove the in-process native-code risk (T31) and could be revisited if T31 is judged too high.

## Still open

| Item | Needed |
|---|---|
| Production PostgreSQL major(s) | Inventory §2, then rebuild with the matching libpg_query release and rerun |
| Clean, air-gapped VM (S4) | Copy `tools\` plus the repo, run `Build-PgQuery.ps1` and `Invoke-S2Spike.ps1` with no network |
| Windows Server, GitLab runner | Rerun there |
| T31 risk acceptance | Stakeholder: accept the in-process native parser, or require out-of-process / WASM isolation |
