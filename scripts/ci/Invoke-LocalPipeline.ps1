#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the GitLab pipeline's jobs locally, in stage order, with the same scripts the runner uses
    (scripts\ci\Invoke-CiJob.ps1). Stops at the first failing job.

.PARAMETER Sonar
    Also run the analysis stage against the project-local SonarQube (started if needed).
.PARAMETER Package
    Also run the package stage.
#>
[CmdletBinding()]
param([int]$Seed = 0, [switch]$Sonar, [switch]$Package, [string]$LogDir)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum ([int]::MaxValue) }
if (-not $LogDir) { $LogDir = Join-Path $repoRoot 'ci-logs' }

$jobs = @('deps-verify', 'toolchain-check', 'lint-frontend', 'lint-backend', 'test-frontend', 'test-backend', 'e2e')
if ($Sonar) {
    . (Join-Path $repoRoot 'scripts\sonar\Start-LocalSonar.ps1') | Out-Null
    $jobs += @('sonar-backend', 'sonar-frontend')
}
if ($Package) { $jobs += 'package' }

$sw = [Diagnostics.Stopwatch]::StartNew()
foreach ($j in $jobs) {
    Write-Host "== $j"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'scripts\ci\Invoke-CiJob.ps1') -Job $j -Seed $Seed -LogDir $LogDir
    if ($LASTEXITCODE -ne 0) { Write-Host "pipeline FAILED at $j (seed $Seed)"; exit 1 }
}
Write-Host ("pipeline PASSED: {0} jobs in {1:N0}s (seed {2})" -f $jobs.Count, $sw.Elapsed.TotalSeconds, $Seed)
exit 0
