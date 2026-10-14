# Spike S3: PostgreSQL driver protocol behaviour

Throwaway feasibility code for plan §3.2 and Phase 1 spike S3. It finds out **what each candidate Java driver actually sends to PostgreSQL**, by reading the server's own statement log, and whether row limits, cancellation and transaction tracking behave as the design assumes.

Candidates:
- **Option A:** pgJDBC 42.7.14.
- **Option B:** Vert.x Reactive PostgreSQL Client 5.2.1, using prepared (extended-protocol) queries only.

Results and conclusions are in [`REPORT.md`](REPORT.md).

## Requirements

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server, PowerShell 5.1 | |
| Project toolchain (JDK 21, Maven 3.9) | `scripts\toolchain\Install-Toolchain.ps1` (see the root `README.md`) |
| Maven dependencies in `tools\m2\repository` | Downloaded once on a connected machine (see below). Runs are then **offline** (`mvn -o`). |
| PostgreSQL binaries (`initdb.exe`, `pg_ctl.exe`) | EDB installer `bin` folder, **same major version as production**. Found automatically, or pass `-PgBin`. The installed PostgreSQL service is never touched. |
| Permission to write to `%TEMP%` and bind a localhost port | No administrator rights needed |

## Getting the dependencies (once, on a connected machine)

```powershell
. .\scripts\toolchain\Use-Toolchain.ps1
mvn -B -f spikes\s3-driver\pom.xml dependency:go-offline test-compile
```

For an air-gapped machine, copy `tools\m2\` along with `tools\downloads\` (see the root README).

Libraries used (test scope only): `org.postgresql:postgresql:42.7.14`, `io.vertx:vertx-pg-client:5.2.1` (with its Netty and Vert.x core dependencies), `com.fasterxml.jackson.core:jackson-databind:2.22.3` (reads the server's JSON log), and JUnit Jupiter 6.1.3.

## Running

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s3-driver\Invoke-S3Spike.ps1
```

| Parameter | Default | Purpose |
|---|---|---|
| `-RequiredConsecutivePasses` | 5 | Consecutive passing runs needed for overall PASS |
| `-MaxRuns` | 15 | Upper bound on runs |
| `-Seed` | random | Master seed. Reuse a reported seed to replay the same runs. |
| `-PgBin` | auto | PostgreSQL `bin` folder |
| `-ContinueOnFailure` | off | Keep looping after a failure (the count still resets to 0) |

Each run is `mvn -o test -Ds3.seed=<n>`. The seed drives:
- the order of test classes and methods;
- the corpus order and whitespace/case variations;
- row counts, limits, delays and transaction verbs.

Each run creates its own throwaway cluster in `%TEMP%\pgweb-s3-*` and checks that it is fully gone afterwards. Reports are written to `results\s3-<timestamp>.json` and include every finding per run.

To run one test class directly: `mvn -o -f spikes\s3-driver\pom.xml test -Ds3.seed=123 -Dtest=VertxOptionBTest`.

## How it observes the wire

The cluster runs with `log_statement=all` and `log_destination=jsonlog`. PostgreSQL logs:
- `statement: …` for a simple-protocol Query, which may contain several statements;
- `execute <name>: …` for each extended-protocol Execute, which always contains exactly one statement.

Each test uses a unique `application_name`, then sends a unique sentinel statement on the same session. Everything logged before the sentinel is what the driver sent.

## Safety

- The cluster listens on 127.0.0.1 on a random port.
- It is stamped with a per-run token, both in a marker file and as a server setting. `EphemeralPostgres.assertOwned()` refuses to continue if either doesn't match.
- The behaviour under test always runs as a **non-superuser** role (`s3_user`). The superuser is used only for setup.
- Passwords are random per run and never written to reports.

## Files

| Path | Purpose |
|---|---|
| `src/test/java/spike/s3/support/EphemeralPostgres.java` | Throwaway cluster lifecycle and safety guard, plus the server-log reader. A candidate for reuse in the real integration test harness (ADR-0007). |
| `src/test/java/spike/s3/support/Corpus.java` | Single statements that contain semicolons, comments, dollar quotes and similar traps |
| `PgJdbcSplittingTest` | Client-side splitting, simple vs extended mode, corpus integrity, escape processing |
| `PgJdbcResultTest` | `setMaxRows`, `setFetchSize`, DML `RETURNING` with limits, command outcomes |
| `PgJdbcSessionTest` | Transaction-state tracking, `cancel()`, companion terminate, cross-role signalling, timeout override |
| `VertxOptionBTest` | Server-side multi-statement rejection, corpus, `query()` vs `preparedQuery()`, cursor limits, cancel, row counts, transaction-status probe |
| `Invoke-S3Spike.ps1` | Randomised 5-consecutive-pass loop and JSON report |
