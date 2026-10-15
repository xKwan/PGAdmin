# Offline dependency intake (air gap)

How dependencies get from the internet into the air-gapped environment (ADR-0008, plan §7.1). Every tool, library and plugin the build uses is pinned exactly, and every CI job runs with network access disabled. A job that would need something missing fails; it never fetches.

## Files

| File | Purpose |
|---|---|
| `maven-settings.xml` | Maven settings for every CI job: `offline=true`, every repository mirrored to a closed local port, `checksumPolicy=fail` |
| `New-DependencyBundle.ps1` | **Staging side.** Collects the tool archives, the Maven repository, the npm cache, the SonarScanner cache and the SBOMs into one folder with `SHA256SUMS.txt` |
| `Import-DependencyBundle.ps1` | **Air-gap side.** Verifies the bundle (any missing, altered or unlisted file rejects the whole bundle), copies it into `tools\`, installs the toolchain offline |
| `Write-Sha256Manifest.ps1` | Writes or verifies a `sha256sum`-format manifest |
| `New-ReleaseBundle.ps1` | Builds the application release (`release\pgweb-<version>.zip`) with SBOMs and a manifest |

## Procedure

Plan §7.1 lists seven intake controls. Steps 1–7 below follow them.

### On the connected staging machine (Windows x64)

esbuild/Rolldown ship platform-specific native packages, so the npm cache must be filled on Windows x64.

1. **Change request.** List the new or changed packages and versions. Rule: an exact version, **published at least two weeks earlier**.
2. **Update the pins.** `backend/pom.xml` properties; `npm install --save-exact <pkg>@<version>` in `frontend/` (and `e2e/`); `toolchain/software.json` for tools.
3. **Fill the caches online, once:**
   ```powershell
   . .\scripts\toolchain\Use-Toolchain.ps1
   powershell -ExecutionPolicy Bypass -File scripts\toolchain\Install-Toolchain.ps1
   cd backend;  mvn -B verify;  cd ..
   cd frontend; npm ci;  npm run build;  cd ..
   . .\scripts\sonar\Start-LocalSonar.ps1        # fills tools\sonar-user-home with the scanner engine and JRE
   powershell -ExecutionPolicy Bypass -File scripts\ci\Invoke-LocalPipeline.ps1 -Sonar -Package
   ```
4. **Build the bundle:** `powershell -ExecutionPolicy Bypass -File offline\New-DependencyBundle.ps1` → `tools\bundles\pgweb-deps-<timestamp>\`.
5. **Scan and check.** Scan the SBOMs in `sbom\` (OWASP Dependency-Check, Trivy, or the organisation's tool) and run the organisation's malware scan over the folder. A **second person** checks `SHA256SUMS.txt` against the folder, using `offline\Write-Sha256Manifest.ps1 -Verify`.
6. **Transfer** through the approved media process.

### In the air gap

7. ```powershell
   powershell -ExecutionPolicy Bypass -File offline\Import-DependencyBundle.ps1 -Bundle <path-to-bundle>
   powershell -ExecutionPolicy Bypass -File scripts\ci\Invoke-CiJob.ps1 -Job deps-verify
   ```
   `deps-verify` must pass, proving a clean offline install. If the organisation has an internal Nexus/Artifactory, import the Maven repository and npm cache into it instead, and point `maven-settings.xml` and `npm_config_registry` at it.

## Notes

- `npm audit` and online CVE feeds don't work in the air gap. Vulnerability status is assessed at intake (step 5).
- npm 11 doesn't run package install scripts unless they are approved. None are needed: `msw`'s copies an optional browser worker, and `libxmljs2` (used by the SBOM generator only for XML validation) is not needed for JSON SBOMs.
- Bundle sizes on the workstation (2026-10-15): about 1.2 GB of tool archives (950 MB of it SonarQube), 225 MB of Maven repository, 370 MB of npm cache and 500 MB of Sonar scanner cache.
