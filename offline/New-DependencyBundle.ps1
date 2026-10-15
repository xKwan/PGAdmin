#Requires -Version 5.1
<#
.SYNOPSIS
    Staging side of the air-gap intake (ADR-0008, plan 7.1): collects everything an offline machine needs to
    build and test pgweb into one folder with a SHA-256 manifest, ready for scanning, second-person check and
    transfer.

.DESCRIPTION
    Run on a connected Windows x64 machine after a full online build has populated the caches
    (`mvn -B verify sonar:sonar` in backend\, `npm ci` in frontend\ and e2e\, scripts\toolchain\Install-Toolchain.ps1).

    Bundle layout (default: tools\bundles\pgweb-deps-<yyyyMMdd-HHmmss>\, never committed):
      downloads\            pinned tool archives (JDK, Maven, Zig, libpg_query source, SonarQube)
      m2\repository\        Maven local repository (all plugins and dependencies)
      npm-cache\            npm cache (content-addressed; lock-file integrity is checked on install)
      sonar-user-home\      scanner engine, JRE and analysers provisioned by SonarQube (optional)
      sbom\                 CycloneDX SBOMs of the backend and frontend
      software.lock.json    pinned versions and checksums of the tool archives
      SHA256SUMS.txt        manifest of every file above
    Then scan the folder (organisational malware/vulnerability scan), have a second person check SHA256SUMS.txt,
    and transfer it. On the air-gapped side run offline\Import-DependencyBundle.ps1.
#>
[CmdletBinding()]
param([string]$OutDir, [switch]$SkipSonarCache)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..'))
$tools = Join-Path $repoRoot 'tools'
if (-not $OutDir) { $OutDir = Join-Path $tools ('bundles\pgweb-deps-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
if (Test-Path $OutDir) { throw "Output folder already exists: $OutDir" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function CopyTree([string]$From, [string]$To) {
    if (-not (Test-Path $From)) { throw "Missing $From; populate the caches with an online build first." }
    # robocopy: /E subfolders, /NFL /NDL /NJH /NJS quiet. Exit codes below 8 mean success.
    & robocopy.exe $From $To /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE): $From" }
}

CopyTree (Join-Path $tools 'downloads') (Join-Path $OutDir 'downloads')
CopyTree (Join-Path $tools 'm2\repository') (Join-Path $OutDir 'm2\repository')
CopyTree (Join-Path $tools 'npm-cache') (Join-Path $OutDir 'npm-cache')
if (-not $SkipSonarCache -and (Test-Path (Join-Path $tools 'sonar-user-home'))) {
    CopyTree (Join-Path $tools 'sonar-user-home\cache') (Join-Path $OutDir 'sonar-user-home\cache')
}
Copy-Item (Join-Path $repoRoot 'toolchain\software.lock.json') $OutDir
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'sbom') | Out-Null
foreach ($s in @('backend\target\pgweb-backend-sbom.json', 'frontend\dist-sbom\pgweb-frontend-sbom.json')) {
    $p = Join-Path $repoRoot $s
    if (Test-Path $p) { Copy-Item $p (Join-Path $OutDir 'sbom') } else { Write-Warning "SBOM not found (run the package job first): $s" }
}
# Drop Maven's per-machine bookkeeping files; they are not needed offline and differ between machines.
Get-ChildItem (Join-Path $OutDir 'm2\repository') -Recurse -Force -Include '_remote.repositories', '*.lastUpdated', 'resolver-status.properties' |
    Remove-Item -Force

& (Join-Path $repoRoot 'offline\Write-Sha256Manifest.ps1') -Root $OutDir -Manifest (Join-Path $OutDir 'SHA256SUMS.txt')
$size = (Get-ChildItem $OutDir -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host ("dependency bundle: {0} ({1:N0} MB)" -f $OutDir, ($size / 1MB))
