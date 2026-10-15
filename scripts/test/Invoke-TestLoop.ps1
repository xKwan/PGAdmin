#Requires -Version 5.1
<#
.SYNOPSIS
    The delivery workflow's randomised test loop: runs the full test suites repeatedly, each run with a new seed,
    until they pass the required number of consecutive runs. Any failure resets the count to 0.

.DESCRIPTION
    Each run, with a seed drawn from the master seed:
      frontend  ESLint (0 warnings) + Vitest with coverage thresholds; the seed shuffles test order and drives
                the randomised tests (PGWEB_TEST_SEED)
      backend   mvn -o verify: Enforcer, -Werror compile, unit (Surefire) + integration (Failsafe, throwaway
                PostgreSQL clusters) tests in random order, JaCoCo thresholds (-Dpgweb.test.seed)
    A run passes only if every suite passes and no throwaway cluster directory (%TEMP%\pgweb-*) is left behind.
    The report (seeds, counts, failures, durations) goes to docs\evidence\<Tag>\loop-<timestamp>.json, so any run can
    be replayed with -Seed.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test\Invoke-TestLoop.ps1 -Tag phase-2
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test\Invoke-TestLoop.ps1 -Suite backend -Seed 42 -RequiredConsecutivePasses 1
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'backend', 'frontend')][string]$Suite = 'all',
    [int]$RequiredConsecutivePasses = 5,
    [int]$MaxRuns = 15,
    [int]$Seed = 0,
    [string]$Tag = 'adhoc',
    [switch]$ContinueOnFailure
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
. (Join-Path $repoRoot 'scripts\ci\CiCommon.ps1')
Set-OfflineNpmEnvironment

if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum ([int]::MaxValue) }
$master = New-Object System.Random($Seed)
$backend = Join-Path $repoRoot 'backend'
$frontend = Join-Path $repoRoot 'frontend'
$settings = Join-Path $repoRoot 'offline\maven-settings.xml'
$logRoot = Join-Path $repoRoot "ci-logs\loop-$Tag"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$evidenceDir = Join-Path $repoRoot "docs\evidence\$Tag"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

function Get-Leftovers { @(Get-ChildItem -Path ([IO.Path]::GetTempPath()) -Directory -Filter 'pgweb-*' -ErrorAction SilentlyContinue) }

function Read-JUnit([string]$Dir, [string]$Label) {
    $r = [pscustomobject]@{ tests = 0; failures = 0; errors = 0; skipped = 0; failed = @() }
    foreach ($xml in @(Get-ChildItem $Dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)) {
        [xml]$doc = Get-Content -Raw $xml.FullName
        $s = $doc.testsuite
        $r.tests += [int]$s.tests; $r.failures += [int]$s.failures; $r.errors += [int]$s.errors; $r.skipped += [int]$s.skipped
        foreach ($tc in @($s.testcase)) {
            if ($tc -and ($tc.SelectSingleNode('failure') -or $tc.SelectSingleNode('error'))) { $r.failed += "[$Label] $($tc.classname).$($tc.name)" }
        }
    }
    return $r
}

$before = @(Get-Leftovers).Count
$runs = @()
$consecutive = 0
$started = Get-Date

for ($i = 1; $i -le $MaxRuns -and $consecutive -lt $RequiredConsecutivePasses; $i++) {
    $runSeed = $master.Next(1, [int]::MaxValue)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $suites = @()
    $failed = @()
    $ok = $true

    if ($Suite -in 'all', 'frontend') {
        $env:PGWEB_TEST_SEED = "$runSeed"
        $lintLog = Join-Path $logRoot "run-$i-frontend-lint.log"
        $lintRc = Invoke-LoggedCommand -CommandLine 'npm run lint' -Log $lintLog -WorkDir $frontend
        $resultsJson = Join-Path $frontend 'coverage\vitest-results.json'
        if (Test-Path $resultsJson) { Remove-Item -Force $resultsJson }
        $testLog = Join-Path $logRoot "run-$i-frontend-test.log"
        $testRc = Invoke-LoggedCommand -CommandLine 'npx vitest run --coverage --reporter=default --reporter=json --outputFile.json=coverage/vitest-results.json' -Log $testLog -WorkDir $frontend
        $fe = [pscustomobject]@{ suite = 'frontend'; lintExit = $lintRc; testExit = $testRc; tests = 0; failures = 0; coverage = $null }
        if (Test-Path $resultsJson) {
            $vr = Get-Content -Raw $resultsJson | ConvertFrom-Json
            $fe.tests = [int]$vr.numTotalTests; $fe.failures = [int]$vr.numFailedTests
            foreach ($f in @($vr.testResults)) {
                foreach ($a in @($f.assertionResults)) { if ($a.status -eq 'failed') { $failed += "[frontend] $($a.fullName)" } }
            }
        }
        $summary = Join-Path $frontend 'coverage\coverage-summary.json'
        if (Test-Path $summary) {
            $cs = (Get-Content -Raw $summary | ConvertFrom-Json).total
            $fe.coverage = [pscustomobject]@{ lines = $cs.lines.pct; branches = $cs.branches.pct; functions = $cs.functions.pct; statements = $cs.statements.pct }
        }
        if ($lintRc -ne 0) { $failed += '[frontend] eslint' }
        if ($testRc -ne 0 -and $fe.failures -eq 0) { $failed += '[frontend] vitest exited non-zero (coverage threshold or error); see log' }
        $ok = $ok -and $lintRc -eq 0 -and $testRc -eq 0 -and $fe.tests -gt 0
        $suites += $fe
    }

    if ($Suite -in 'all', 'backend') {
        foreach ($d in 'surefire-reports', 'failsafe-reports') {
            $p = Join-Path $backend "target\$d"
            if (Test-Path $p) { Remove-Item -Recurse -Force $p }
        }
        $beLog = Join-Path $logRoot "run-$i-backend.log"
        $beRc = Invoke-LoggedCommand -CommandLine "mvn -B -o -s `"$settings`" verify -Dpgweb.test.seed=$runSeed" -Log $beLog -WorkDir $backend
        $unit = Read-JUnit (Join-Path $backend 'target\surefire-reports') 'backend-unit'
        $it = Read-JUnit (Join-Path $backend 'target\failsafe-reports') 'backend-it'
        $cov = $null
        $jx = Join-Path $backend 'target\site\jacoco-merged\jacoco.xml'
        if (Test-Path $jx) {
            $settingsXml = New-Object Xml.XmlReaderSettings
            $settingsXml.DtdProcessing = [Xml.DtdProcessing]::Ignore
            $reader = [Xml.XmlReader]::Create($jx, $settingsXml)
            try { $jdoc = New-Object Xml.XmlDocument; $jdoc.Load($reader) } finally { $reader.Close() }
            $cov = @{}
            foreach ($c in @($jdoc.report.counter)) {
                $tot = [double]$c.missed + [double]$c.covered
                if ($tot -gt 0) { $cov[$c.type.ToLowerInvariant()] = [math]::Round(100 * [double]$c.covered / $tot, 1) }
            }
        }
        $failed += $unit.failed + $it.failed
        if ($beRc -ne 0 -and ($unit.failed.Count + $it.failed.Count) -eq 0) { $failed += '[backend] mvn verify failed (build, Enforcer or coverage); see log' }
        $suites += [pscustomobject]@{ suite = 'backend'; exit = $beRc; unit = $unit.tests; integration = $it.tests; failures = $unit.failures + $it.failures; errors = $unit.errors + $it.errors; coverage = $cov }
        $ok = $ok -and $beRc -eq 0 -and ($unit.tests + $it.tests) -gt 0
    }

    $sw.Stop()
    $leftovers = @(Get-Leftovers).Count - $before
    if ($leftovers -gt 0) { $failed += "[cleanup] $leftovers throwaway directories left in %TEMP%" }
    $pass = $ok -and $leftovers -le 0
    if ($pass) { $consecutive++ } else { $consecutive = 0 }
    $runs += [pscustomobject]@{ run = $i; seed = $runSeed; pass = $pass; seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1); consecutive = $consecutive; leftovers = $leftovers; failed = $failed; suites = $suites }

    $counts = ($suites | ForEach-Object {
            if ($_.suite -eq 'frontend') { "frontend tests=$($_.tests)" } else { "backend unit=$($_.unit) it=$($_.integration)" }
        }) -join '  '
    Write-Host ("run {0,2}  seed {1,10}  {2}  {3}  {4}s  consecutive={5}" -f $i, $runSeed, $(if ($pass) { 'PASS' } else { 'FAIL' }), $counts, $runs[-1].seconds, $consecutive)
    if (-not $pass) {
        foreach ($f in $failed) { Write-Host "      failed: $f" }
        Write-Host "      logs: $logRoot\run-$i-*.log"
        if (-not $ContinueOnFailure) { break }
    }
}

$overall = $consecutive -ge $RequiredConsecutivePasses
$report = [pscustomobject]@{
    tag = $Tag; suite = $Suite; overall = $(if ($overall) { 'PASS' } else { 'FAIL' }); masterSeed = $Seed
    requiredConsecutivePasses = $RequiredConsecutivePasses
    started = $started.ToString('o'); finished = (Get-Date).ToString('o')
    os = [Environment]::OSVersion.VersionString; java = $env:JAVA_HOME; node = ((& node -v) -join '')
    commit = ((& git -C $repoRoot rev-parse --short HEAD 2>$null) -join ''); dirty = [bool](& git -C $repoRoot status --porcelain 2>$null)
    runs = $runs
}
$out = Join-Path $evidenceDir ('loop-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $out -Encoding UTF8
Write-Host "Overall: $($report.overall) (master seed $Seed). Report: $out"
if ($overall) { exit 0 } else { exit 1 }
