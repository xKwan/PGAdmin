#Requires -Version 5.1
<#
.SYNOPSIS
    Phase 2 acceptance checks (plan Phase 2): each pipeline gate must FAIL when its fault is introduced.
      G1  deps-verify (npm): `npm ci --offline` with an empty cache fails instead of fetching (ENOTCACHED)
      G2  deps-verify (Maven): an offline build with an empty local repository fails instead of fetching
      G3  lint-frontend: a dangerouslySetInnerHTML use fails ESLint
      G4  test-backend: an untested class drops coverage below the JaCoCo thresholds -> mvn verify fails
      G5  test-frontend: an untested module drops coverage below the Vitest thresholds -> vitest fails
      G6  manifest: a modified, a missing and an extra file are each detected by Write-Sha256Manifest -Verify
      G7  toolchain-check: a wrong pinned version is reported (run against a temp copy of configuration.properties)
    Faults are injected into temporary copies, or into a uniquely named probe file that is always removed.
    Writes a JSON report to docs\evidence\<Tag>\gates-<timestamp>.json. Exit 0 only if every gate failed as expected.
#>
[CmdletBinding()]
param([string]$Tag = 'phase-2')

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..\..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
. (Join-Path $repoRoot 'scripts\ci\CiCommon.ps1')
Set-OfflineNpmEnvironment
$settings = Join-Path $repoRoot 'offline\maven-settings.xml'
$frontend = Join-Path $repoRoot 'frontend'
$work = Join-Path ([IO.Path]::GetTempPath()) ('pgweb-gates-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$results = @()

function Gate([string]$Id, [string]$Name, [scriptblock]$Body) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = & $Body
        $ok = [bool]$r.ok
        $detail = [string]$r.detail
    } catch {
        $ok = $false; $detail = "error: $($_.Exception.Message)"
    }
    $script:results += [pscustomobject]@{ id = $Id; gate = $Name; failedAsExpected = $ok; detail = $detail; seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1) }
    Write-Host ("  {0} {1}  {2}  ({3})" -f $(if ($ok) { 'ok  ' } else { 'FAIL' }), $Id, $Name, $detail)
}

function Copy-Backend([string]$To) {
    New-Item -ItemType Directory -Force -Path $To | Out-Null
    Copy-Item (Join-Path $repoRoot 'backend\pom.xml') $To
    Copy-Item (Join-Path $repoRoot 'backend\src') (Join-Path $To 'src') -Recurse
}

try {
    Gate 'G1' 'npm ci --offline with an empty cache fails' {
        $d = Join-Path $work 'npm'; New-Item -ItemType Directory -Force -Path $d | Out-Null
        Copy-Item (Join-Path $frontend 'package.json'), (Join-Path $frontend 'package-lock.json') $d
        $old = $env:npm_config_cache; $env:npm_config_cache = Join-Path $work 'empty-npm-cache'
        try { $rc = Invoke-LoggedCommand -CommandLine 'npm ci --offline' -Log (Join-Path $work 'g1.log') -WorkDir $d } finally { $env:npm_config_cache = $old }
        $log = Get-Content -Raw (Join-Path $work 'g1.log')
        @{ ok = ($rc -ne 0 -and $log -match 'ENOTCACHED'); detail = "exit $rc, ENOTCACHED=$($log -match 'ENOTCACHED')" }
    }

    Gate 'G2' 'offline Maven build with an empty repository fails' {
        $d = Join-Path $work 'mvn-empty'; Copy-Backend $d
        $repo = Join-Path $work 'empty-m2'
        $rc = Invoke-LoggedCommand -CommandLine "mvn -B -o -s `"$settings`" -Dmaven.repo.local=`"$repo`" -q package -DskipTests" -Log (Join-Path $work 'g2.log') -WorkDir $d
        $log = Get-Content -Raw (Join-Path $work 'g2.log')
        $downloaded = @(Get-ChildItem $repo -Recurse -File -Filter '*.jar' -ErrorAction SilentlyContinue).Count
        @{ ok = ($rc -ne 0 -and $log -match 'offline' -and $downloaded -eq 0); detail = "exit $rc, jars fetched=$downloaded" }
    }

    Gate 'G3' 'dangerouslySetInnerHTML fails ESLint' {
        $probe = Join-Path $frontend ('src\gate-probe-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.jsx')
        Set-Content -Path $probe -Encoding UTF8 -Value "export function Probe({ html }) {`n  return <div dangerouslySetInnerHTML={{ __html: html }} />;`n}"
        try { $rc = Invoke-LoggedCommand -CommandLine "npx eslint --max-warnings 0 `"$probe`"" -Log (Join-Path $work 'g3.log') -WorkDir $frontend } finally { Remove-Item -Force $probe }
        $log = Get-Content -Raw (Join-Path $work 'g3.log')
        @{ ok = ($rc -ne 0 -and $log -match 'dangerouslySetInnerHTML is banned'); detail = "exit $rc" }
    }

    Gate 'G4' 'backend coverage drop fails mvn verify' {
        $d = Join-Path $work 'mvn-cov'; Copy-Backend $d
        $cls = Join-Path $d 'src\main\java\pgweb\GateProbe.java'
        $body = "package pgweb;`n`npublic final class GateProbe {`n    private GateProbe() {`n    }`n`n"
        for ($i = 0; $i -lt 40; $i++) { $body += "    public static int f$i(int x) {`n        if (x > $i) {`n            return x - $i;`n        }`n        return x + $i;`n    }`n`n" }
        $body += "}`n"
        Set-Content -Path $cls -Value $body -Encoding ASCII
        $rc = Invoke-LoggedCommand -CommandLine "mvn -B -o -s `"$settings`" verify" -Log (Join-Path $work 'g4.log') -WorkDir $d
        $log = Get-Content -Raw (Join-Path $work 'g4.log')
        @{ ok = ($rc -ne 0 -and $log -match 'Coverage checks have not been met'); detail = "exit $rc" }
    }

    Gate 'G5' 'frontend coverage drop fails vitest' {
        $probe = Join-Path $frontend ('src\gate-probe-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.js')
        $body = ''
        for ($i = 0; $i -lt 40; $i++) { $body += "export function f$i(x) {`n  if (x > $i) {`n    return x - $i;`n  }`n  return x + $i;`n}`n" }
        Set-Content -Path $probe -Value $body -Encoding ASCII
        try { $rc = Invoke-LoggedCommand -CommandLine 'npx vitest run --coverage' -Log (Join-Path $work 'g5.log') -WorkDir $frontend } finally { Remove-Item -Force $probe }
        $log = Get-Content -Raw (Join-Path $work 'g5.log')
        @{ ok = ($rc -ne 0 -and $log -match 'does not meet'); detail = "exit $rc" }
    }

    Gate 'G6' 'manifest detects modified, missing and extra files' {
        $writer = Join-Path $repoRoot 'offline\Write-Sha256Manifest.ps1'
        $outcomes = @()
        foreach ($fault in 'modified', 'missing', 'extra') {
            $d = Join-Path $work "manifest-$fault"; New-Item -ItemType Directory -Force -Path (Join-Path $d 'sub') | Out-Null
            Set-Content (Join-Path $d 'a.txt') 'alpha'; Set-Content (Join-Path $d 'sub\b.txt') 'beta'
            $m = Join-Path $d 'SHA256SUMS.txt'
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $writer -Root $d -Manifest $m | Out-Null
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $writer -Root $d -Manifest $m -Verify | Out-Null
            $clean = $LASTEXITCODE
            switch ($fault) {
                'modified' { Set-Content (Join-Path $d 'sub\b.txt') 'BETA' }
                'missing' { Remove-Item (Join-Path $d 'a.txt') }
                'extra' { Set-Content (Join-Path $d 'sub\c.txt') 'gamma' }
            }
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $writer -Root $d -Manifest $m -Verify | Out-Null
            $outcomes += [pscustomobject]@{ fault = $fault; clean = $clean; faulty = $LASTEXITCODE }
        }
        $ok = @($outcomes | Where-Object { $_.clean -ne 0 -or $_.faulty -eq 0 }).Count -eq 0
        @{ ok = $ok; detail = (($outcomes | ForEach-Object { "$($_.fault): clean=$($_.clean) faulty=$($_.faulty)" }) -join '; ') }
    }

    Gate 'G7' 'toolchain-check reports a version mismatch' {
        $d = Join-Path $work 'toolchain'
        New-Item -ItemType Directory -Force -Path (Join-Path $d 'scripts\ci'), (Join-Path $d 'frontend') | Out-Null
        Copy-Item (Join-Path $repoRoot 'scripts\ci\Test-ToolchainVersions.ps1') (Join-Path $d 'scripts\ci')
        Copy-Item (Join-Path $repoRoot 'frontend\package.json') (Join-Path $d 'frontend')
        (Get-Content (Join-Path $repoRoot 'configuration.properties')) -replace '^maven\.version=.*$', 'maven.version=3.9.0' |
            Set-Content (Join-Path $d 'configuration.properties')
        $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $d 'scripts\ci\Test-ToolchainVersions.ps1') 2>&1
        $rc = $LASTEXITCODE
        @{ ok = ($rc -ne 0 -and (($out -join "`n") -match 'FAIL maven')); detail = "exit $rc" }
    }
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

$all = @($results | Where-Object { -not $_.failedAsExpected }).Count -eq 0
$dir = Join-Path $repoRoot "docs\evidence\$Tag"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$out = Join-Path $dir ('gates-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
[pscustomobject]@{ tag = $Tag; overall = $(if ($all) { 'PASS' } else { 'FAIL' }); when = (Get-Date).ToString('o'); commit = ((& git -C $repoRoot rev-parse --short HEAD) -join ''); gates = $results } |
    ConvertTo-Json -Depth 5 | Set-Content -Path $out -Encoding UTF8
Write-Host "Gate checks: $(if ($all) { 'PASS' } else { 'FAIL' }). Report: $out"
if ($all) { exit 0 } else { exit 1 }
