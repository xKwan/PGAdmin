# Spike S2: libpg_query parser on Windows (offline)

Throwaway feasibility code for plan §3.1 and Phase 1 spike S2. It answers four questions:
- Can PostgreSQL's own parser (libpg_query) be built and loaded **offline on Windows**?
- Does it split scripts **exactly as the PostgreSQL server does**?
- Can it produce audit text with **no literals and no comments**?
- Is it fast enough?

Results and conclusions are in [`REPORT.md`](REPORT.md).

## Requirements

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server, PowerShell 5.1 | |
| Project toolchain: JDK 21, Maven 3.9, **Zig 0.17** and the **libpg_query 18.1.0 source** | `scripts\toolchain\Install-Toolchain.ps1` installs all four into `tools\` and records them in `software-list.md`. Zig is used only to compile the DLL. |
| Maven dependencies in `tools\m2\repository` | Fetched once on a connected machine (below). Test runs are offline (`mvn -o`). |
| PostgreSQL binaries (`initdb.exe`, `pg_ctl.exe`) | Same major version as the parser (18) and as production. The installed service is not touched. |

No Visual Studio and no internet access are needed after the toolchain and Maven dependencies are present.

## Building the parser DLL

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s2-parser\Build-PgQuery.ps1
```

This compiles the libpg_query sources from `tools\libpg_query-src-18.1.0` (pinned by SHA256 in `toolchain\software.lock.json`) with `zig cc -target x86_64-windows-gnu -O2`. It uses the same source list and include paths as libpg_query's own Makefile.

Output:
- `build\pg_query.dll` (about 2.8 MB, reproducible: see REPORT B2);
- `build\pg_query.dll.sha256`.

Both are git-ignored. The build takes about 3–4 minutes and never modifies the source tree. `-Clean` rebuilds from scratch, and `-OutDir <dir>` writes elsewhere.

## Getting the Maven dependencies (once, on a connected machine)

```powershell
. .\scripts\toolchain\Use-Toolchain.ps1
mvn -B -f spikes\s2-parser\pom.xml dependency:go-offline test-compile
```

Libraries (test scope only):
- `net.java.dev.jna:jna:5.19.1` loads the DLL;
- `io.vertx:vertx-pg-client:5.2.1` provides the server reference;
- `org.postgresql:postgresql:42.7.14`;
- `jackson-databind 2.22.3`;
- JUnit Jupiter 6.1.3.

## Running

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s2-parser\Invoke-S2Spike.ps1
```

The runner:
- builds the DLL if it's missing;
- verifies its SHA256;
- loops `mvn -o test` with a new seed per run until 5 consecutive passes (any failure resets the count and stops the loop; `-ContinueOnFailure` overrides);
- writes `results\s2-<timestamp>.json`.

Other parameters are the same as for S3: `-Seed`, `-RequiredConsecutivePasses`, `-MaxRuns` and `-PgBin`.

## How it checks the parser

**The server is the reference, not a hand-written table.**
1. Each script is sent unchanged to a throwaway PostgreSQL cluster in one simple-protocol Query, through the Vert.x client (no client-side lexer).
2. The server parses the whole text before running any of it, and returns one result per statement. A sentinel statement is appended so the server never receives empty text (see finding V8).
3. The parser's statement count must equal the server's.
4. Each statement the parser cut out must then run on its own on the server as **exactly one** statement.

| Test class | What it covers |
|---|---|
| `SplitAgainstServerTest` | Every plan §3.1 corpus category (40 items). 30–50 random scripts per run built from the corpus with random separators, under `standard_conforming_strings` on and off. UTF-8 byte offsets. |
| `ClassificationAndFuzzTest` | Statement kinds. Performance (p99 < 5 ms). Token-soup fuzzing and structured fuzzing, each compared with the server. |
| `NormalizationTest` | Audit text: 28 statement forms with random secrets; no secret may survive. Comment markers inside strings. |
| `NormalizerFindingsTest` | Re-checks on every run why libpg_query's own normaliser is **not** used (N1, N2) |
| `DriverEdgeCaseTest` | Text with no statements, on both drivers. `BEGIN ATOMIC` through pgJDBC. |

## Files

| Path | Purpose |
|---|---|
| `Build-PgQuery.ps1` | Offline DLL build with project-local Zig |
| `Invoke-S2Spike.ps1` | Randomised 5-consecutive-pass loop |
| `src/test/java/spike/s2/support/PgQuery.java` | JNA binding: parse (with `standard_conforming_strings` option), scanner-based comment removal and constant masking, libpg_query normaliser |
| `src/test/java/spike/s2/support/ParserCorpus.java` | Plan §3.1 corpus |
| `src/test/java/spike/s2/support/ServerTruth.java` | Server reference via the Vert.x simple protocol |
| `src/test/java/spike/s2/support/EphemeralPostgres.java` | Throwaway cluster (shared with S3) |
