#Requires -Version 5.1
<#
.SYNOPSIS
    Air-gap side of the intake (ADR-0008, plan 7.1 steps 5-6): verifies a dependency bundle against its
    SHA-256 manifest and copies it into tools\, then installs the toolchain offline.
    Refuses the whole bundle if any file is missing, altered or unlisted.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File offline\Import-DependencyBundle.ps1 -Bundle E:\pgweb-deps-20261015-120000
    Then run CI's deps-verify job (scripts\ci\Invoke-CiJob.ps1 -Job deps-verify) to prove a clean offline install.
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Bundle)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..'))
$tools = Join-Path $repoRoot 'tools'
$manifest = Join-Path $Bundle 'SHA256SUMS.txt'
if (-not (Test-Path $manifest)) { throw "No SHA256SUMS.txt in $Bundle" }

& (Join-Path $repoRoot 'offline\Write-Sha256Manifest.ps1') -Root $Bundle -Manifest $manifest -Verify
if ($LASTEXITCODE -ne 0) { throw 'Bundle verification failed; nothing was imported.' }

$lockInBundle = Get-Content -Raw (Join-Path $Bundle 'software.lock.json')
$lockInRepo = Get-Content -Raw (Join-Path $repoRoot 'toolchain\software.lock.json')
if ($lockInBundle.Trim() -ne $lockInRepo.Trim()) { throw 'The bundle was made for a different toolchain\software.lock.json; check out the matching commit.' }

foreach ($pair in @(@('downloads', 'downloads'), @('m2\repository', 'm2\repository'), @('npm-cache', 'npm-cache'), @('sonar-user-home', 'sonar-user-home'))) {
    $from = Join-Path $Bundle $pair[0]
    if (-not (Test-Path $from)) { continue }
    & robocopy.exe $from (Join-Path $tools $pair[1]) /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE) for $($pair[0])" }
    Write-Host "  imported $($pair[0])"
}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'scripts\toolchain\Install-Toolchain.ps1') -Offline
if ($LASTEXITCODE -ne 0) { throw 'Offline toolchain install failed.' }
Write-Host 'Import complete. Next: scripts\ci\Invoke-CiJob.ps1 -Job deps-verify'
