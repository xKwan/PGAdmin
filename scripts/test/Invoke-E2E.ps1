#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the end-to-end suite (e2e\, Playwright with the installed Microsoft Edge) against a real, throwaway
    environment: a PostgreSQL 18 cluster that authenticates this Windows account with SSPI, and the packaged pgweb
    JAR (with the SPA) in test mode with execution enabled.

.DESCRIPTION
    1. Builds the JAR with the SPA unless -SkipBuild (mvn package -Pwith-spa).
    2. Creates %TEMP%\pgweb-e2e-<random>: initdb, pg_hba.conf with sspi for this account's role (lower-cased user
       name), scram for the service roles; starts it on a free localhost port.
    3. Bootstraps and migrates the audit database, creates the role and an e2e_db database owned by it.
    4. Starts pgweb on a free port (test mode, NTLM allowed, execution enabled, external profile -> the cluster).
    5. Installs e2e\ offline and runs `npx playwright test` -Repeat times, each with a new seed.
    6. Stops everything and deletes the temporary folder (also on failure).
    Results: docs\evidence\<Tag>\e2e-<timestamp>.json. Exit 0 only if every repetition passed.

    Workstation only (no domain): sign-in is NTLM loopback. Kerberos and delegation to another host are target
    environment items (docs\target-environment-checklist.md).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test\Invoke-E2E.ps1 -Tag phase-8 -Repeat 5
#>
[CmdletBinding()]
param(
    [string]$Tag = 'adhoc',
    [int]$Repeat = 1,
    [int]$Seed = 0,
    [switch]$SkipBuild,
    [switch]$KeepEnvironment
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
. (Join-Path $repoRoot 'scripts\ci\CiCommon.ps1')
Set-OfflineNpmEnvironment

if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum ([int]::MaxValue) }
$master = New-Object System.Random($Seed)
$logDir = Join-Path $repoRoot "ci-logs\e2e-$Tag"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$evidenceDir = Join-Path $repoRoot "docs\evidence\$Tag"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

function Get-FreePort {
    $l = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); return $p
}
function New-Secret { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ }) }
function Find-PgBin {
    # Same order as scripts\dev-db.ps1: PGWEB_PG_BIN, the recorded pre-existing install, the default location.
    $line = Get-Content (Join-Path $repoRoot 'configuration.properties') | Where-Object { $_ -match '^preexisting\.postgresql\.path=' } | Select-Object -First 1
    $recorded = if ($line) { ($line -split '=', 2)[1].Replace('/', '\') } else { $null }
    foreach ($c in @($env:PGWEB_PG_BIN, $recorded, (Join-Path $env:ProgramFiles 'PostgreSQL\18\bin'))) {
        if ($c -and (Test-Path (Join-Path $c 'initdb.exe'))) { return $c }
    }
    throw 'PostgreSQL 18 binaries not found (set PGWEB_PG_BIN).'
}
function Run([string]$Name, [string]$CommandLine, [string]$WorkDir = $repoRoot) {
    $log = Join-Path $logDir "$Name.log"
    $rc = Invoke-LoggedCommand -CommandLine $CommandLine -Log $log -WorkDir $WorkDir
    if ($rc -ne 0) { Get-Content $log -Tail 30 | ForEach-Object { Write-Host "  $_" }; throw "$Name failed (exit $rc). Log: $log" }
}

$pgBin = Find-PgBin
$backend = Join-Path $repoRoot 'backend'
$settings = Join-Path $repoRoot 'offline\maven-settings.xml'
$dll = Join-Path $repoRoot 'native\libpg_query\build\pg_query.dll'
Assert-SqlParser -RepoRoot $repoRoot -Log (Join-Path $logDir 'parser.log')
if (-not $SkipBuild) {
    Run 'frontend-ci' 'npm ci --offline --no-audit --no-fund' (Join-Path $repoRoot 'frontend')
    # -Pwith-spa only copies frontend\dist into the JAR: build it from the current sources first (finding P10-1).
    Run 'spa-build' 'npm run build' (Join-Path $repoRoot 'frontend')
    Run 'jar' "mvn -B -o -q -s `"$settings`" -Pwith-spa -DskipTests package" $backend
}
$jar = (Get-ChildItem (Join-Path $backend 'target') -Filter 'pgweb-backend-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1).FullName
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

$root = Join-Path ([IO.Path]::GetTempPath()) ('pgweb-e2e-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$data = Join-Path $root 'data'
$role = $env:USERNAME.ToLowerInvariant()
$pgPort = Get-FreePort
$appPort = Get-FreePort
$app = $null
$app2 = $null
$results = @()
try {
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    $super = New-Secret; $migrator = New-Secret; $appPw = New-Secret; $hmac = (New-Secret) + (New-Secret)
    $pwFile = Join-Path $root 'pw.txt'
    [IO.File]::WriteAllText($pwFile, $super)
    Run 'initdb' "`"$(Join-Path $pgBin 'initdb.exe')`" -D `"$data`" -U pgweb_e2e_super -A scram-sha-256 `"--pwfile=$pwFile`" -E UTF8 --no-locale"
    Remove-Item $pwFile
    Set-Content -Path (Join-Path $data 'pg_hba.conf') -Encoding ASCII -Value @(
        "host all $role 127.0.0.1/32 sspi include_realm=1 map=win",
        "host all $role ::1/128 sspi include_realm=1 map=win",
        'host all all 127.0.0.1/32 scram-sha-256',
        'host all all ::1/128 scram-sha-256')
    Set-Content -Path (Join-Path $data 'pg_ident.conf') -Encoding ASCII -Value "win `"/(?i)^$role@.*`$`" $role"
    # The server that `pg_ctl start` launches inherits pg_ctl's handles (S5): output to files, wait for pg_ctl only.
    $pgc = Start-Process -FilePath (Join-Path $pgBin 'pg_ctl.exe') -NoNewWindow -PassThru `
        -ArgumentList @('start', '-D', "`"$data`"", '-l', "`"$(Join-Path $root 'server.log')`"", '-w', '-t', '60', '-o', "`"-p $pgPort -c listen_addresses=localhost`"") `
        -RedirectStandardOutput (Join-Path $logDir 'pg-start.log') -RedirectStandardError (Join-Path $logDir 'pg-start.err.log')
    $null = $pgc.Handle
    $pgc.WaitForExit()
    if ($pgc.ExitCode -ne 0) { throw "pg_ctl start failed; see $root\server.log" }

    $env:PGPASSWORD = $super
    try {
        $psql = "`"$(Join-Path $pgBin 'psql.exe')`" -X -q -v ON_ERROR_STOP=1 -h localhost -p $pgPort -U pgweb_e2e_super"
        Run 'audit-bootstrap' "$psql -d postgres -v `"migrator_password=$migrator`" -v `"app_password=$appPw`" -f `"$(Join-Path $repoRoot 'deploy\sql\audit-bootstrap.sql')`""
        $setup = Join-Path $root 'setup.sql'
        Set-Content -Path $setup -Encoding ASCII -Value @(
            "CREATE ROLE `"$role`" LOGIN NOSUPERUSER CREATEDB CREATEROLE;",
            "CREATE DATABASE e2e_db OWNER `"$role`";",
            # A second database of the same role, to switch to (admin Phase 1).
            "CREATE DATABASE e2e_other OWNER `"$role`";",
            # The test account is an auditor (admin Phase 9): the audit viewer reads as this role.
            "GRANT pgweb_audit_reader TO `"$role`";")
        Run 'roles' "$psql -d postgres -f `"$setup`""
        $seedSql = Join-Path $root 'seed.sql'
        Set-Content -Path $seedSql -Encoding ASCII -Value @(
            "SET ROLE `"$role`";",
            'CREATE TABLE public.items (id int PRIMARY KEY, name text);',
            "INSERT INTO public.items SELECT g, 'item ' || g FROM generate_series(1, 2500) g;",
            '-- An object whose name is markup: the browser must show it as text (Phase 11).',
            'CREATE TABLE public."<img src=x onerror=alert(1)>" (a int);')
        Run 'seed' "$psql -d e2e_db -f `"$seedSql`""
    } finally { Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue }

    $config = Join-Path $root 'pgweb.properties'
    $fwd = { param($p) $p.Replace('\', '/') }
    Set-Content -Path $config -Encoding ASCII -Value @(
        'pgweb.mode=test', "server.port=$appPort", 'server.publicHost=localhost',
        'auth.protocols=Negotiate NTLM', 'auth.kerberosOnly=false', 'auth.dataAdminSid=S-1-5-32-545',
        "audit.jdbcUrl=jdbc:postgresql://localhost:$pgPort/pgweb_audit", 'audit.user=pgweb_app', "audit.password=plain:$appPw",
        "audit.hmacKey=plain:$hmac", ('audit.spoolDir=' + (& $fwd (Join-Path $root 'spool'))),
        'audit.migrator.user=pgweb_migrator', "audit.migrator.password=plain:$migrator",
        'profile.external.host=localhost', "profile.external.port=$pgPort", 'profile.external.statementTimeoutSeconds=20',
        # The internal profile (DataAdmin only) points at the same throwaway cluster, with a banner.
        'profile.internal.host=localhost', "profile.internal.port=$pgPort", 'profile.internal.bannerText=INTERNAL', 'profile.internal.bannerTone=danger',
        'execution.enabled=true', ('parser.library=' + (& $fwd $dll)),
        'auditViewer.enabled=true', 'auditViewer.host=localhost', "auditViewer.port=$pgPort", 'savedQueries.enabled=true',
        ('logging.dir=' + (& $fwd (Join-Path $root 'logs'))), ('server.workDir=' + (& $fwd (Join-Path $root 'work'))))
    Run 'migrate' "`"$java`" -jar `"$jar`" migrate --config `"$config`""

    # A second instance (Phase 11): the same cluster, but you are NOT DataAdmin there (a SID nobody has), and the app
    # session expires after 1 idle minute (session-expiry journey).
    $appPort2 = Get-FreePort
    $config2 = Join-Path $root 'pgweb-noadmin.properties'
    (Get-Content $config) | ForEach-Object {
        if ($_ -like 'server.port=*') { "server.port=$appPort2" }
        elseif ($_ -like 'auth.dataAdminSid=*') { 'auth.dataAdminSid=S-1-5-21-1-2-3-999999' }
        elseif ($_ -like 'logging.dir=*') { 'logging.dir=' + (& $fwd (Join-Path $root 'logs2')) }
        elseif ($_ -like 'server.workDir=*') { 'server.workDir=' + (& $fwd (Join-Path $root 'work2')) }
        elseif ($_ -like 'audit.spoolDir=*') { 'audit.spoolDir=' + (& $fwd (Join-Path $root 'spool2')) }
        else { $_ }
    } | Set-Content -Path $config2 -Encoding ASCII
    Add-Content -Path $config2 -Encoding ASCII -Value 'auth.session.idleMinutes=1'

    $app = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"", 'serve', '--config', "`"$config`"") -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir 'app.out.log') -RedirectStandardError (Join-Path $logDir 'app.err.log')
    $app2 = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"", 'serve', '--config', "`"$config2`"") -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir 'app2.out.log') -RedirectStandardError (Join-Path $logDir 'app2.err.log')
    foreach ($port in $appPort, $appPort2) {
        $up = $false
        for ($i = 0; $i -lt 120 -and -not $up; $i++) {
            Start-Sleep -Milliseconds 500
            try { $up = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$port/api/ready" -TimeoutSec 2).StatusCode -eq 200 } catch { $up = $false }
        }
        if (-not $up) { throw "pgweb on port $port did not become ready; see $logDir" }
    }

    $e2e = Join-Path $repoRoot 'e2e'
    Run 'e2e-ci' 'npm ci --offline --no-audit --no-fund' $e2e
    for ($r = 1; $r -le $Repeat; $r++) {
        $runSeed = $master.Next(1, [int]::MaxValue)
        $env:E2E_BASE_URL = "http://localhost:$appPort"
        $env:E2E_BASE_URL_NOADMIN = "http://localhost:$appPort2"
        # For the audit-outage journey: the test switches the throwaway cluster's audit login off and on with psql.
        $env:E2E_PSQL = Join-Path $pgBin 'psql.exe'
        $env:E2E_PG_PORT = "$pgPort"
        $env:E2E_PG_SUPER_PASSWORD = $super
        $env:E2E_ROLE = $role
        $env:E2E_SEED = "$runSeed"
        $json = Join-Path $e2e 'test-results\results.json'
        if (Test-Path $json) { Remove-Item -Force $json }
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $rc = Invoke-LoggedCommand -CommandLine 'npx playwright test' -Log (Join-Path $logDir "run-$r.log") -WorkDir $e2e
        $stats = if (Test-Path $json) { (Get-Content -Raw $json | ConvertFrom-Json).stats } else { $null }
        $failed = @()
        if (Test-Path $json) {
            $doc = Get-Content -Raw $json | ConvertFrom-Json
            foreach ($s in @($doc.suites)) { foreach ($sp in @($s.specs)) { if (-not $sp.ok) { $failed += $sp.title } } }
        }
        $ok = $rc -eq 0
        $passed = 0
        if ($stats) { $passed = [int]$stats.expected }
        $results += [pscustomobject]@{ run = $r; seed = $runSeed; ok = $ok; passed = $passed; failed = $failed; seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1) }
        Write-Host ("e2e run {0}  seed {1,10}  {2}  passed={3}  {4}s" -f $r, $runSeed, $(if ($ok) { 'PASS' } else { 'FAIL' }), $passed, [math]::Round($sw.Elapsed.TotalSeconds, 1))
        foreach ($f in $failed) { Write-Host "      failed: $f" }
    }
} finally {
    foreach ($a in @($app, $app2)) { if ($a -and -not $a.HasExited) { Stop-Process -Id $a.Id -Force } }
    Remove-Item Env:\E2E_PG_SUPER_PASSWORD -ErrorAction SilentlyContinue
    if (Test-Path $data) {
        [void](Invoke-LoggedCommand -CommandLine "`"$(Join-Path $pgBin 'pg_ctl.exe')`" stop -D `"$data`" -m immediate -w -t 30" -Log (Join-Path $logDir 'pg-stop.log') -WorkDir $repoRoot)
    }
    if ((Test-Path $root) -and -not $KeepEnvironment) {
        Copy-Item (Join-Path $root 'server.log') (Join-Path $logDir 'pg-server.log') -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
    }
}

$all = @($results).Count -eq $Repeat -and @($results | Where-Object { -not $_.ok }).Count -eq 0
$edge = (Get-Item 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' -ErrorAction SilentlyContinue).VersionInfo.ProductVersion
$out = Join-Path $evidenceDir ('e2e-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
[pscustomobject]@{
    tag = $Tag; when = (Get-Date).ToString('o'); masterSeed = $Seed; repeat = $Repeat; overall = $(if ($all) { 'PASS' } else { 'FAIL' })
    edge = $edge; commit = ((& git -C $repoRoot rev-parse --short HEAD) -join ''); runs = $results
} | ConvertTo-Json -Depth 5 | Set-Content -Path $out -Encoding UTF8
Write-Host ("Overall: {0} (master seed {1}). Report: {2}" -f $(if ($all) { 'PASS' } else { 'FAIL' }), $Seed, $out)
if ($all) { exit 0 } else { exit 1 }
