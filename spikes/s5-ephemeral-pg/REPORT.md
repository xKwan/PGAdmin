# Spike S5 — Report

**Result: PASS on the developer workstation (partial S5).** 5 consecutive randomised runs passed, with no residue.
**Not yet done:** running on the GitLab Windows runner and on Windows Server. Plan S5 requires the runner, so S5 stays **open** for that environment.

## Environment

| Item | Value |
|---|---|
| Machine | Developer workstation, Windows 11 Pro 10.0.26200, not domain-joined, **not air-gapped** |
| Shell | Windows PowerShell 5.1 (`powershell.exe -NoProfile`) |
| PostgreSQL binaries | 18.6 (`S:\Program Files\PostgreSQL\18\bin`), EDB installer |
| Internet used by the harness | None (only local binaries and localhost connections) |

## Loop history (2026-10-15)

| Report | Result | Cause | Fix |
|---|---|---|---|
| `s5-20261015-002056.json` | FAIL | `$PSScriptRoot` is empty in `param()` defaults on PS 5.1 | Resolve the results path in the script body |
| `s5-20261015-002136.json` | FAIL | `[string](Get-Content -Raw <empty file>)` yields `$null` on PS 5.1 | Use `"$(...)"` |
| `s5-20261015-002204.json` | FAIL (6 scenarios passed) | Empty array returned from a function arrives as `$null`; `.Count` fails under strict mode | Wrap in `@()` |
| `s5-20261015-002246.json` | FAIL (5 scenarios passed) | Local `$c` overwrote parameter `$C` (PowerShell names are case-insensitive) | Renamed the variable; scanned for other clashes |
| `s5-20261015-002434.json` | **PASS: 5/5 consecutive** | — | — |
| `s5-20261015-002801.json` | **PASS: 5/5 consecutive** | Re-run after a code change: SQL error messages now redact `PASSWORD '…'` | — |

All four failures were defects in the harness itself, not in PostgreSQL behaviour. After each fix, the consecutive count restarted at 0. Any code change also restarts the count, so the final code version passed its own fresh 5/5 loop (master seed `1484766625`; replay with `-Seed 1484766625`).

## Findings

| # | Finding | Status | Plan impact |
|---|---|---|---|
| F1 | Throwaway clusters via `initdb`/`pg_ctl` work on Windows without admin rights or Docker; ~3 s to create, full cleanup verified each run | **Tested** (workstation) | Confirms ADR-0007 default; still needs the GitLab runner (C10) |
| F2 | A role-level `statement_timeout` is defeated by the user running `SET statement_timeout = 0` | **Tested** | Confirms plan §3.5: session settings are backstops only; the app's own timer is required |
| F3 | An external `pg_cancel_backend` from a **same-role non-superuser** session ends the statement (57014), rolls back the open transaction and releases its locks | **Tested** | Supports ADR-0004 companion connection and A13. Cancellation via pgJDBC's protocol-level cancel is still S3. |
| F4 | A same-role non-superuser `pg_terminate_backend` ends the backend and releases its locks | **Tested** | Supports cancel escalation (plan §3.5 step 4) |
| F5 | Lock timeout (55P03), deadlock (40P01; PostgreSQL chose the second waiter as victim in all observed runs) and REPEATABLE READ serialization failure (40001) behave as the plan describes, with no lost updates | **Tested** | Confirms plan §4 reporting design; the victim choice is not relied on |
| F6 | Advisory-lock barriers plus `pg_stat_activity` polling give deterministic concurrency tests (no fixed sleeps; 0 flaky failures in 5 runs) | **Tested** | Pattern to reuse in the Java integration suite (P9) |

## Still open for S5 and related items

- Run on the **GitLab Windows runner** under the runner's service account, and on **Windows Server** (C10).
- Use binaries of the **production PostgreSQL major version** (C12; target versions unknown).
- Cancellation through **pgJDBC** (`Statement.cancel()`) rather than `pg_cancel_backend`, and the measurements in P7, remain part of S3/P7.
