# Spike S5 — Throwaway PostgreSQL clusters without Docker

Proves that integration tests can create, use and destroy a real PostgreSQL cluster on Windows using only the PostgreSQL binaries that the EDB Windows installer already provides. No Docker, no Testcontainers, no internet access.

It also exercises the concurrency and cancellation behaviour the application design depends on (plan §3.5 and §4), using a **non-superuser** role, as the application will.

## Requirements

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server 2016+ | Tested on Windows 11 Pro 10.0.26200 |
| Windows PowerShell 5.1 (built in) | PowerShell 7 also works |
| PostgreSQL binaries: `initdb.exe`, `pg_ctl.exe`, `psql.exe` | From the EDB installer's `bin` folder. Use the **same major version as the production servers**. The running PostgreSQL service is not touched. |
| Permission to write to `%TEMP%` and bind a localhost port | No administrator rights needed |
| Internet access | **Not required** |

## Running it

```powershell
cd spikes\s5-ephemeral-pg
powershell -NoProfile -ExecutionPolicy Bypass -File .\Invoke-S5Spike.ps1
```

| Parameter | Default | Purpose |
|---|---|---|
| `-PgBin <dir>` | `$env:PGWEB_PG_BIN`, then `initdb` on PATH, then the newest `Program Files\PostgreSQL\<n>\bin` | Which PostgreSQL binaries to use |
| `-RequiredConsecutivePasses <n>` | 5 | Consecutive passing runs needed for overall PASS |
| `-MaxRuns <n>` | 15 | Upper bound on runs |
| `-Seed <n>` | random | Master seed. Re-use a reported seed to replay the same randomised runs. |
| `-ContinueOnFailure` | off | Keep running after a failure (the consecutive count still resets to 0) |
| `-ResultsDir <dir>` | `.\results` | Where the JSON report is written |

Exit code `0` = PASS, `1` = FAIL.

## What each run does

1. Creates a cluster in `%TEMP%\pgweb-s5-<guid>` with `initdb` (SCRAM authentication, random superuser password), starts it with `pg_ctl` on a **random free port bound to 127.0.0.1**, and stamps it with a per-run token (marker file plus a server setting).
2. Creates a non-superuser role and a test database.
3. Runs every scenario below **in a random order with random parameters** (row IDs, increments, timeouts, delays, advisory-lock keys).
4. Stops the cluster, deletes the directory, and verifies that no directory, process or port is left behind.

The **safety guard** checks the marker file and server token before every scenario. The harness refuses to touch any cluster it didn't create.

## Scenarios

| Scenario | Proves |
|---|---|
| `basic-crud` | Cluster usable; bulk insert and aggregate correct |
| `safety-guard` | Guard accepts its own cluster and refuses one with a mismatched token |
| `timeout-backstop-overridable` | A role-level `statement_timeout` fires, **but the user can disable it with `SET statement_timeout = 0`**. An external cancel (as the app's wall-clock timer will send) still ends the statement with 57014, and the backend holds no locks afterwards. |
| `cancel-releases-locks` | Cancelling a statement inside an open transaction rolls it back and releases its row locks; another session can then update the row |
| `lock-timeout-then-terminate` | `lock_timeout` produces 55P03 no earlier than the timeout; a same-role `pg_terminate_backend` ends the holder, rolls back its work and releases its locks |
| `same-row-update` | Concurrent updates to one row serialise (the second waits) with no lost update |
| `deadlock` | PostgreSQL detects the deadlock (40P01), exactly one transaction is rolled back, and only the survivor's changes persist |
| `serialization-failure` | Under REPEATABLE READ, a conflicting concurrent commit produces 40001 and the failed transaction's change is discarded |
| `cleanup-after-failure` | A second cluster is fully removed even when an exception is thrown while it is in use |
| `teardown-leaves-nothing` | After every run: no directory, no `postgres.exe` for the cluster, port free |

Concurrency scenarios coordinate sessions with advisory-lock barriers and by polling `pg_stat_activity` wait events. They don't rely on fixed sleeps.

## Results

See `REPORT.md` for the recorded evidence and `results/*.json` for raw run data (seeds, scenario order, timings, errors).
