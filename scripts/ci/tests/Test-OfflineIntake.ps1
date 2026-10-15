#Requires -Version 5.1
<#
.SYNOPSIS
    Exercises the air-gap intake end to end on one machine (plan Phase 2 DoD, ADR-0008):
      1. offline\New-DependencyBundle.ps1 builds a bundle from this checkout's caches;
      2. the bundle's manifest is verified;
      3. the current commit is cloned into a fresh temp folder, which has no tools\ at all;
      4. offline\Import-DependencyBundle.ps1 verifies and imports the bundle there and installs the toolchain offline;
      5. in the clone, the offline CI jobs deps-verify, toolchain-check, lint-*, test-* and package run.
    This proves the bundle is complete for a fresh checkout. It is NOT spike S4 proper: the machine still has
    network access (every tool is forced offline instead) and pre-existing Node.js and PostgreSQL.
    Report: docs\evidence\<Tag>\intake-<timestamp>.json
#>
[CmdletBinding()]
param([string]$Tag = 'phase-2', [switch]$KeepClone)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..\..'))
$work = Join-Path ([IO.Path]::GetTempPath()) ('pgweb-intake-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$bundle = Join-Path $work 'bundle'
$clone = Join-Path $work 'repo'
$steps = @()
$started = Get-Date

function Step([string]$Name, [scriptblock]$Body) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & $Body
    $ok = $LASTEXITCODE -eq 0
    $script:steps += [pscustomobject]@{ step = $Name; ok = $ok; seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1) }
    Write-Host ("  {0} {1} ({2:N0}s)" -f $(if ($ok) { 'ok  ' } else { 'FAIL' }), $Name, $sw.Elapsed.TotalSeconds)
    if (-not $ok) { throw "intake step failed: $Name" }
}

$overall = $false
try {
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    Step 'bundle' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'offline\New-DependencyBundle.ps1') -OutDir $bundle }
    Step 'verify-manifest' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'offline\Write-Sha256Manifest.ps1') -Root $bundle -Manifest (Join-Path $bundle 'SHA256SUMS.txt') -Verify }
    Step 'clone' { & git clone -q --no-hardlinks $repoRoot $clone }
    Step 'import' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $clone 'offline\Import-DependencyBundle.ps1') -Bundle $bundle }
    foreach ($job in 'deps-verify', 'toolchain-check', 'lint-frontend', 'lint-backend', 'test-frontend', 'test-backend', 'package') {
        Step "job:$job" { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $clone 'scripts\ci\Invoke-CiJob.ps1') -Job $job -LogDir (Join-Path $work 'logs') }
    }
    $overall = $true
} catch {
    Write-Host "intake exercise FAILED: $($_.Exception.Message) (logs in $work\logs)"
    $KeepClone = $true
} finally {
    $dir = Join-Path $repoRoot "docs\evidence\$Tag"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bundleSize = if (Test-Path $bundle) { [math]::Round(((Get-ChildItem $bundle -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB) } else { 0 }
    $report = [pscustomobject]@{
        tag = $Tag; overall = $(if ($overall) { 'PASS' } else { 'FAIL' }); started = $started.ToString('o'); finished = (Get-Date).ToString('o')
        commit = ((& git -C $repoRoot rev-parse --short HEAD) -join ''); bundleMB = $bundleSize; steps = $steps
        note = 'Same machine, tools forced offline; not a clean network-disabled VM (S4 proper remains open).'
    }
    $out = Join-Path $dir ('intake-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    $report | ConvertTo-Json -Depth 5 | Set-Content -Path $out -Encoding UTF8
    Write-Host "Intake exercise: $($report.overall). Report: $out"
    if (-not $KeepClone) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue } else { Write-Host "kept: $work" }
}
if ($overall) { exit 0 } else { exit 1 }
