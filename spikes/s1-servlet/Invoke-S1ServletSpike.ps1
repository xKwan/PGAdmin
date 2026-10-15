<#
.SYNOPSIS
    Spike S1 (servlet variant): runs the Waffle servlet-filter test suite (embedded Tomcat 11, no Spring) repeatedly
    with random seeds until it passes the required number of consecutive runs. Any failure resets the count to 0.

.DESCRIPTION
    Each run is `mvn -o test` (offline, project-local Maven repository) with a new seed. The seed randomises paths,
    schemes, garbage tokens, concurrency, the foreign DataAdmin SID, and test class and method order.
    Results go to results\s1s-<timestamp>.json.

.PARAMETER ContinueOnFailure
    Keep running after a failed run (the consecutive count still resets). By default the loop stops at the first
    failure so it can be fixed.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s1-servlet\Invoke-S1ServletSpike.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s1-servlet\Invoke-S1ServletSpike.ps1 -Seed 123 -RequiredConsecutivePasses 5
#>
[CmdletBinding()]
param(
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
    # Nothing is created outside target\; kept for the same report shape as the other spikes.
    @()
}

$before = @(Get-Leftovers).Count
$runs = @()
$consecutive = 0
$started = Get-Date

for ($i = 1; $i -le $MaxRuns -and $consecutive -lt $RequiredConsecutivePasses; $i++) {
    $runSeed = $master.Next(1, [int]::MaxValue)
    $findingsFile = Join-Path $here 'target\s1-findings.jsonl'
    if (Test-Path $findingsFile) { Remove-Item $findingsFile -Force }
    $profiles = @('servlet')
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $exit = 0
    $summary = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    $failed = @()
    foreach ($profile in $profiles) {
        $reports = Join-Path $here 'target\surefire-reports'
        if (Test-Path $reports) { Remove-Item $reports -Recurse -Force }
        $mvnArgs = @('-B', '-o', '-q', '-f', (Join-Path $here 'pom.xml'), 'test', "-Ds1.seed=$runSeed")
        $log = Join-Path $here "target\mvn-run-$i-$profile.log"
        New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
        # Run through cmd so Maven's stderr does not become PowerShell error records (PS 5.1).
        & cmd.exe /c "mvn $($mvnArgs -join ' ') > `"$log`" 2>&1"
        if ($LASTEXITCODE -ne 0) { $exit = $LASTEXITCODE }
        foreach ($xml in @(Get-ChildItem $reports -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)) {
            [xml]$doc = Get-Content -Raw $xml.FullName
            $suite = $doc.testsuite
            foreach ($k in 'tests', 'failures', 'errors', 'skipped') { $summary[$k] += [int]$suite.$k }
            foreach ($tc in @($suite.testcase)) {
                if ($tc.SelectSingleNode('failure') -or $tc.SelectSingleNode('error')) { $failed += "[$profile] $($tc.classname).$($tc.name)" }
            }
        }
    }
    $sw.Stop()
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
        Write-Host "      logs: $(Join-Path $here 'target')\mvn-run-$i-*.log"
        if (-not $ContinueOnFailure) { break }
    }
}

$overall = $consecutive -ge $RequiredConsecutivePasses
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$report = [pscustomobject]@{
    spike                     = 'S1 Waffle servlet filter on embedded Tomcat 11, no Spring (partial: workgroup machine)'
    overall                   = $(if ($overall) { 'PASS' } else { 'FAIL' })
    masterSeed                = $Seed
    requiredConsecutivePasses = $RequiredConsecutivePasses
    started                   = $started.ToString('o')
    finished                  = (Get-Date).ToString('o')
    os                        = (Get-CimInstance Win32_OperatingSystem).Caption
    java                      = $env:JAVA_HOME
    domainJoined              = (Get-CimInstance Win32_ComputerSystem).PartOfDomain
    runs                      = $runs
}
$out = Join-Path $ResultsDir ('s1s-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $out -Encoding UTF8
Write-Host "Overall: $($report.overall) (master seed $Seed). Report: $out"
if ($overall) { exit 0 } else { exit 1 }
