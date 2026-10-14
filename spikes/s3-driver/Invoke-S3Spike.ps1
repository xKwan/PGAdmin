<#
.SYNOPSIS
    Spike S3: runs the driver-protocol test suite repeatedly with random seeds until it passes the required number of
    consecutive runs. Any failure resets the count to 0.

.DESCRIPTION
    Each run is `mvn -o test` (offline: only the project-local Maven repository in tools\m2 is used) with a new seed.
    The seed randomises the corpus sample, scenario parameters, and the order of test classes and methods. Every run
    creates and destroys its own throwaway PostgreSQL cluster. Results go to results\s3-<timestamp>.json.

.PARAMETER ContinueOnFailure
    Keep running after a failed run (the consecutive count still resets). By default the loop stops at the first
    failure so it can be fixed.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s3-driver\Invoke-S3Spike.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s3-driver\Invoke-S3Spike.ps1 -Seed 123 -RequiredConsecutivePasses 5
#>
[CmdletBinding()]
param(
    [string]$PgBin,
    [int]$RequiredConsecutivePasses = 5,
    [int]$MaxRuns = 15,
    [int]$Seed = 0,
    [string]$ResultsDir,
    [switch]$ContinueOnFailure
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $ResultsDir) { $ResultsDir = Join-Path $here 'results' }

. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') | Out-Null

if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum ([int]::MaxValue) }
$master = New-Object System.Random($Seed)
$tempRoot = [IO.Path]::GetTempPath()

function Get-Leftovers {
    @(Get-ChildItem -Path $tempRoot -Directory -Filter 'pgweb-s3-*' -ErrorAction SilentlyContinue)
}

$before = @(Get-Leftovers).Count
$runs = @()
$consecutive = 0
$started = Get-Date

for ($i = 1; $i -le $MaxRuns -and $consecutive -lt $RequiredConsecutivePasses; $i++) {
    $runSeed = $master.Next(1, [int]::MaxValue)
    $findingsFile = Join-Path $here 'target\s3-findings.jsonl'
    if (Test-Path $findingsFile) { Remove-Item $findingsFile -Force }
    $reports = Join-Path $here 'target\surefire-reports'
    if (Test-Path $reports) { Remove-Item $reports -Recurse -Force }

    $mvnArgs = @('-B', '-o', '-q', '-f', (Join-Path $here 'pom.xml'), 'test', "-Ds3.seed=$runSeed")
    if ($PgBin) { $mvnArgs += "-Ds3.pgBin=$PgBin" }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $log = Join-Path $here "target\mvn-run-$i.log"
    New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
    # Run through cmd so Maven's stderr does not become PowerShell error records (PS 5.1).
    & cmd.exe /c "mvn $($mvnArgs -join ' ') > `"$log`" 2>&1"
    $exit = $LASTEXITCODE
    $sw.Stop()

    $summary = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    $failed = @()
    foreach ($xml in @(Get-ChildItem (Join-Path $here 'target\surefire-reports') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)) {
        [xml]$doc = Get-Content -Raw $xml.FullName
        $suite = $doc.testsuite
        foreach ($k in 'tests', 'failures', 'errors', 'skipped') { $summary[$k] += [int]$suite.$k }
        foreach ($tc in @($suite.testcase)) {
            if ($tc.SelectSingleNode('failure') -or $tc.SelectSingleNode('error')) { $failed += "$($tc.classname).$($tc.name)" }
        }
    }
    $leftovers = @(Get-Leftovers).Count - $before
    $pass = ($exit -eq 0) -and ($summary.tests -gt 0) -and ($leftovers -le 0)
    if ($pass) { $consecutive++ } else { $consecutive = 0 }

    $findings = @()
    if (Test-Path $findingsFile) {
        $findings = @(Get-Content $findingsFile | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    }
    $runs += [pscustomobject]@{
        run         = $i
        seed        = $runSeed
        pass        = $pass
        exitCode    = $exit
        tests       = $summary.tests
        failures    = $summary.failures
        errors      = $summary.errors
        failed      = $failed
        leftovers   = $leftovers
        seconds     = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        consecutive = $consecutive
        findings    = $findings
    }
    $status = if ($pass) { 'PASS' } else { 'FAIL' }
    Write-Host ("run {0,2}  seed {1,10}  {2}  tests={3} failures={4} errors={5} leftovers={6}  {7}s  consecutive={8}" -f `
            $i, $runSeed, $status, $summary.tests, $summary.failures, $summary.errors, $leftovers, $runs[-1].seconds, $consecutive)
    if (-not $pass) {
        foreach ($f in $failed) { Write-Host "      failed: $f" }
        Write-Host "      log: $log"
        if (-not $ContinueOnFailure) { break }
    }
}

$overall = $consecutive -ge $RequiredConsecutivePasses
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$report = [pscustomobject]@{
    spike                     = 'S3 driver protocol'
    overall                   = $(if ($overall) { 'PASS' } else { 'FAIL' })
    masterSeed                = $Seed
    requiredConsecutivePasses = $RequiredConsecutivePasses
    started                   = $started.ToString('o')
    finished                  = (Get-Date).ToString('o')
    machine                   = $env:COMPUTERNAME
    os                        = (Get-CimInstance Win32_OperatingSystem).Caption
    java                      = $env:JAVA_HOME
    runs                      = $runs
}
$out = Join-Path $ResultsDir ('s3-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $out -Encoding UTF8
Write-Host "Overall: $($report.overall) (master seed $Seed). Report: $out"
if ($overall) { exit 0 } else { exit 1 }
