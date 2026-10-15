<#
.SYNOPSIS
    Spike S6 (partial) and S4 (partial, npm): Playwright drives the installed Microsoft Edge against the S1 Waffle
    service, repeatedly with random seeds, until the required number of consecutive runs pass. Any failure resets
    the count to 0.

.DESCRIPTION
    Every run:
      1. Deletes node_modules and reinstalls it with `npm ci --offline` from the project-local npm cache
         (tools\npm-cache), with the npm registry pointed at an unreachable address, which proves that the
         JavaScript dependencies install with no network access (partial S4).
      2. Starts the S1 Waffle service (Spring Boot 4.1 line, compiled offline with Maven) on a random free port.
      3. Runs the Playwright tests with system Edge (channel msedge); no browser is ever downloaded.
      4. Stops the service and checks the port is free again.
    Results go to results\s6-<timestamp>.json, including the observations the tests record.

.PARAMETER Prepare
    Online, once: fills tools\npm-cache with `npm ci` (or `npm install` if there is no lock file).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s6-e2e\Invoke-S6Spike.ps1
#>
[CmdletBinding()]
param(
    [int]$RequiredConsecutivePasses = 5,
    [int]$MaxRuns = 15,
    [int]$Seed = 0,
    [string]$ResultsDir,
    [switch]$Prepare,
    [switch]$ContinueOnFailure
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
$s1 = Join-Path $repoRoot 'spikes\s1-waffle'
if (-not $ResultsDir) { $ResultsDir = Join-Path $here 'results' }

. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'

function Invoke-Cmd {
    # Runs a command line through cmd.exe with output to a log file (keeps PS 5.1 from turning stderr into errors).
    param([string]$CommandLine, [string]$Log, [string]$WorkDir)
    Push-Location $WorkDir
    try { & cmd.exe /c "$CommandLine > `"$Log`" 2>&1"; return $LASTEXITCODE } finally { Pop-Location }
}

function Get-FreePort {
    $l = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0); $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); return $p
}

function Test-PortOpen([int]$Port) {
    $c = New-Object Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', $Port); return $true } catch { return $false } finally { $c.Dispose() }
}

$target = Join-Path $here 'target'
New-Item -ItemType Directory -Force -Path $target | Out-Null

if ($Prepare) {
    $cmd = if (Test-Path (Join-Path $here 'package-lock.json')) { 'npm ci --no-audit --no-fund' } else { 'npm install --no-audit --no-fund' }
    $rc = Invoke-Cmd -CommandLine $cmd -Log (Join-Path $target 'npm-prepare.log') -WorkDir $here
    if ($rc -ne 0) { throw "npm prepare failed; see target\npm-prepare.log" }
    Write-Host "npm cache filled: $env:npm_config_cache"
}

# S1 service: compile offline and get its runtime classpath.
$cpFile = Join-Path $s1 'target\runtime-cp.txt'
$rc = Invoke-Cmd -CommandLine "mvn -B -o -q -Pboot4 compile dependency:build-classpath -Dmdep.outputFile=`"$cpFile`" -Dmdep.includeScope=runtime" `
    -Log (Join-Path $target 'mvn-s1-build.log') -WorkDir $s1
if ($rc -ne 0) { throw "Building the S1 service failed; see target\mvn-s1-build.log" }
$classpath = (Join-Path $s1 'target\classes') + ';' + (Get-Content -Raw $cpFile).Trim()
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$expectSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$edge = Get-Item 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' -ErrorAction SilentlyContinue

if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum ([int]::MaxValue) }
$master = New-Object System.Random($Seed)
$runs = @()
$consecutive = 0
$started = Get-Date

for ($i = 1; $i -le $MaxRuns -and $consecutive -lt $RequiredConsecutivePasses; $i++) {
    $runSeed = $master.Next(1, [int]::MaxValue)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $problems = @()

    # 1. Offline reinstall of the JavaScript dependencies.
    $nm = Join-Path $here 'node_modules'
    if (Test-Path $nm) { Remove-Item -Recurse -Force $nm }
    $env:npm_config_registry = 'http://127.0.0.1:9/'   # unreachable: any network access would fail the install
    $npmWatch = [Diagnostics.Stopwatch]::StartNew()
    $rc = Invoke-Cmd -CommandLine 'npm ci --offline --no-audit --no-fund' -Log (Join-Path $target "npm-offline-$i.log") -WorkDir $here
    $npmSeconds = [math]::Round($npmWatch.Elapsed.TotalSeconds, 1)
    Remove-Item Env:\npm_config_registry
    if ($rc -ne 0) { $problems += "npm ci --offline failed (exit $rc)" }

    # 2. Start the S1 service.
    $port = Get-FreePort
    $appLog = Join-Path $target "s1-app-$i.log"
    $app = $null
    $tests = @{ expected = 0; unexpected = 0; skipped = 0 }
    $observations = @()
    $failedTests = @()
    if ($problems.Count -eq 0) {
        $app = Start-Process -FilePath $java -ArgumentList @('-cp', "`"$classpath`"", 'spike.s1.WaffleSpikeApplication', "--server.port=$port") `
            -NoNewWindow -PassThru -RedirectStandardOutput $appLog -RedirectStandardError "$appLog.err"
        $deadline = (Get-Date).AddSeconds(60)
        while (-not (Test-PortOpen $port) -and (Get-Date) -lt $deadline -and -not $app.HasExited) { Start-Sleep -Milliseconds 250 }
        if (-not (Test-PortOpen $port)) { $problems += 'S1 service did not start' }
    }

    # 3. Browser tests.
    if ($problems.Count -eq 0) {
        $env:S6_PORT = "$port"; $env:S6_EXPECT_SID = $expectSid; $env:S6_SEED = "$runSeed"
        $json = Join-Path $here 'test-results\results.json'
        if (Test-Path $json) { Remove-Item $json -Force }
        $rc = Invoke-Cmd -CommandLine 'node_modules\.bin\playwright.cmd test' -Log (Join-Path $target "playwright-$i.log") -WorkDir $here
        if (Test-Path $json) {
            $res = Get-Content -Raw $json | ConvertFrom-Json
            $tests.expected = [int]$res.stats.expected; $tests.unexpected = [int]$res.stats.unexpected; $tests.skipped = [int]$res.stats.skipped
            foreach ($suite in @($res.suites)) {
                foreach ($spec in @($suite.specs)) {
                    foreach ($t in @($spec.tests)) {
                        foreach ($a in @($t.annotations)) { $observations += [pscustomobject]@{ test = $spec.title; type = $a.type; value = $a.description } }
                        if ($t.status -ne 'expected') { $failedTests += $spec.title }
                    }
                }
            }
        } else { $problems += 'no Playwright JSON report' }
        if ($rc -ne 0) { $problems += "playwright exit $rc" }
    }

    # 4. Stop the service; the port must be released.
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force; $app.WaitForExit(15000) | Out-Null }
    Start-Sleep -Milliseconds 300
    if ($port -and (Test-PortOpen $port)) { $problems += "port $port still open after stopping the service" }

    $sw.Stop()
    $pass = ($problems.Count -eq 0) -and ($tests.unexpected -eq 0) -and ($tests.expected -gt 0)
    if ($pass) { $consecutive++ } else { $consecutive = 0 }
    $runs += [pscustomobject]@{
        run = $i; seed = $runSeed; pass = $pass; npmOfflineSeconds = $npmSeconds
        tests = $tests.expected + $tests.unexpected + $tests.skipped; failures = $tests.unexpected
        failed = $failedTests; problems = $problems; observations = $observations
        seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1); consecutive = $consecutive
    }
    $status = if ($pass) { 'PASS' } else { 'FAIL' }
    Write-Host ("run {0,2}  seed {1,10}  {2}  tests={3} failures={4} npm-offline={5}s  {6}s  consecutive={7}" -f `
            $i, $runSeed, $status, $runs[-1].tests, $tests.unexpected, $npmSeconds, $runs[-1].seconds, $consecutive)
    if (-not $pass) {
        foreach ($p in $problems) { Write-Host "      problem: $p" }
        foreach ($f in $failedTests) { Write-Host "      failed: $f" }
        Write-Host "      logs: $target"
        if (-not $ContinueOnFailure) { break }
    }
}

$overall = $consecutive -ge $RequiredConsecutivePasses
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$report = [pscustomobject]@{
    spike                     = 'S6 Edge E2E with Windows integrated auth (partial) + S4 npm offline install (partial)'
    overall                   = $(if ($overall) { 'PASS' } else { 'FAIL' })
    masterSeed                = $Seed
    requiredConsecutivePasses = $RequiredConsecutivePasses
    started                   = $started.ToString('o')
    finished                  = (Get-Date).ToString('o')
    os                        = (Get-CimInstance Win32_OperatingSystem).Caption
    domainJoined              = (Get-CimInstance Win32_ComputerSystem).PartOfDomain
    edgeVersion               = $(if ($edge) { $edge.VersionInfo.ProductVersion } else { 'not found' })
    node                      = (& node -v)
    playwright                = (Get-Content -Raw (Join-Path $here 'package.json') | ConvertFrom-Json).devDependencies.'@playwright/test'
    runs                      = $runs
}
$out = Join-Path $ResultsDir ('s6-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $out -Encoding UTF8
Write-Host "Overall: $($report.overall) (master seed $Seed). Report: $out"
if ($overall) { exit 0 } else { exit 1 }
