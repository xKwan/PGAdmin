#Requires -Version 5.1
<#
.SYNOPSIS
    Phase 12 rehearsal of the documented deployment, on a throwaway environment: install -> use -> backup ->
    upgrade -> tampered-bundle refusal -> rollback -> uninstall, with the audit data preserved and the chain verified
    at each step, and graceful shutdown proven to close every PostgreSQL session.

.DESCRIPTION
    Uses the real release bundles (offline\New-ReleaseBundle.ps1, two versions of the same build), the real deploy
    scripts (deploy\windows\*.ps1) and a throwaway PostgreSQL 18 cluster set up with the shipped
    deploy\sql\audit-bootstrap.sql. Secrets are dpapi: values, as in production. Sign-in and the database session use
    this Windows account (NTLM loopback, SSPI to PostgreSQL), through PowerShell's own Negotiate client.
    Without elevation the scripts run with -NoService (pgweb as a process; stopped with the same Ctrl+C the service
    wrapper sends). With -Service (elevated PowerShell) the real Windows service is installed under LocalSystem.
    Report: docs\evidence\<Tag>\rehearsal-<timestamp>.json.
#>
[CmdletBinding()]
param([string]$Tag = 'adhoc', [switch]$Service, [switch]$KeepEnvironment)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
. (Join-Path $repoRoot 'scripts\ci\CiCommon.ps1')
Add-Type -AssemblyName System.Security
$deploy = Join-Path $repoRoot 'deploy\windows'
$noService = -not $Service
$steps = New-Object System.Collections.Generic.List[object]
$started = Get-Date

function Step([string]$Name, [scriptblock]$Body) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "== $Name"
    try {
        $detail = & $Body
        $steps.Add([pscustomobject]@{ step = $Name; ok = $true; seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1); detail = "$detail" })
    } catch {
        $steps.Add([pscustomobject]@{ step = $Name; ok = $false; seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1); detail = $_.Exception.Message })
        throw
    }
}
function Get-FreePort { $l = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0); $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); $p }
function New-Secret { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ }) }
function Protect([string]$Text) {
    $b = [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($Text), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    'dpapi:' + [Convert]::ToBase64String($b)
}

$line = Get-Content (Join-Path $repoRoot 'configuration.properties') | Where-Object { $_ -match '^preexisting\.postgresql\.path=' } | Select-Object -First 1
$pgBin = if ($env:PGWEB_PG_BIN) { $env:PGWEB_PG_BIN } else { ($line -split '=', 2)[1].Replace('/', '\') }
$work = Join-Path ([IO.Path]::GetTempPath()) ('pgweb-rehearsal-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$rehearsalStart = Get-Date
$data = Join-Path $work 'pg'
$installRoot = Join-Path $work 'pgweb'
$dataRoot = Join-Path $work 'pgweb-data'
$bundles = Join-Path $work 'bundles'
$role = $env:USERNAME.ToLowerInvariant()
$pgPort = Get-FreePort
$appPort = Get-FreePort
$base = "http://localhost:$appPort"
$super = New-Secret; $migrator = New-Secret; $appPw = New-Secret; $hmac = (New-Secret) + (New-Secret)
$v1 = '0.1.0-rehearsal.1'; $v2 = '0.1.0-rehearsal.2'
$commonArgs = @{ InstallRoot = $installRoot; DataRoot = $dataRoot; ServiceName = 'pgweb-rehearsal' }
if ($noService) { $commonArgs['NoService'] = $true }

function Psql([string]$Db, [string]$Sql) {
    $env:PGPASSWORD = $super
    try {
        $out = & (Join-Path $pgBin 'psql.exe') -X -q -t -A -v ON_ERROR_STOP=1 -h localhost -p $pgPort -U rehearsal_super -d $Db -c $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "psql failed: $out" }
        return ($out -join "`n").Trim()
    } finally { Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue }
}
function AuditRows { [int](Psql 'pgweb_audit' 'SELECT count(*) FROM audit.event') }
function Backends { [int](Psql 'postgres' "SELECT count(*) FROM pg_stat_activity WHERE usename = '$role'") }

Add-Type -AssemblyName System.Net.Http
function Send([System.Net.Http.HttpClient]$Client, [string]$Method, [string]$Path, [string]$Body, [string]$Csrf) {
    $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), "$base$Path"
    if ($Body) {
        $req.Content = New-Object System.Net.Http.StringContent $Body, ([Text.Encoding]::UTF8), 'application/json'
        [void]$req.Headers.TryAddWithoutValidation('Origin', $base)
        [void]$req.Headers.TryAddWithoutValidation('X-CSRF-Token', $Csrf)
    }
    $res = $Client.SendAsync($req).GetAwaiter().GetResult()
    $text = $res.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $res.IsSuccessStatusCode) { throw "$Method $Path -> $([int]$res.StatusCode): $text" }
    return ($text | ConvertFrom-Json)
}

function Use-Pgweb([string]$Sql) {
    <#
      Signs in with this Windows account in the installed Edge, opens a DB session and runs SQL (e2e\rehearsal-use.mjs).
      A browser, because the request that opens a session needs its own Windows handshake (ADR-0002), which
      PowerShell's .NET clients don't answer on a POST (finding P12-2).
    #>
    $e2e = Join-Path $repoRoot 'e2e'
    if (-not (Test-Path (Join-Path $e2e 'node_modules\@playwright\test'))) {
        if ((Invoke-LoggedCommand -CommandLine 'npm ci --offline --no-audit --no-fund' -Log (Join-Path $work 'e2e-ci.log') -WorkDir $e2e) -ne 0) { throw 'npm ci (e2e) failed' }
    }
    $args = @('rehearsal-use.mjs', $base, 'rehearsal_db') + @($Sql -split ';;' | ForEach-Object { "`"$($_.Replace('"', '\"'))`"" })
    $log = Join-Path $work ('use-' + [guid]::NewGuid().ToString('N').Substring(0, 6) + '.json')
    $rc = Invoke-LoggedCommand -CommandLine ("node " + ($args -join ' ')) -Log $log -WorkDir $e2e
    if ($rc -ne 0) { throw "using pgweb failed: $(Get-Content -Raw $log)" }
    return (Get-Content -Raw $log | ConvertFrom-Json)
}

$ok = $false
$rollbackSeconds = 0
New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    Step 'build two release bundles' {
        foreach ($v in $v1, $v2) {
            $rc = Invoke-LoggedCommand -CommandLine "powershell -NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $repoRoot 'offline\New-ReleaseBundle.ps1')`" -OutDir `"$bundles`" -VersionLabel $v" -Log (Join-Path $work "bundle-$v.log") -WorkDir $repoRoot
            if ($rc -ne 0) { throw "New-ReleaseBundle $v failed; see $work\bundle-$v.log" }
        }
        "$v1, $v2"
    }

    Step 'throwaway PostgreSQL 18 with the shipped audit bootstrap' {
        New-Item -ItemType Directory -Force -Path $work | Out-Null
        $pw = Join-Path $work 'pw.txt'; [IO.File]::WriteAllText($pw, $super)
        & (Join-Path $pgBin 'initdb.exe') -D $data -U rehearsal_super -A scram-sha-256 "--pwfile=$pw" -E UTF8 --no-locale *> (Join-Path $work 'initdb.log')
        Remove-Item $pw
        if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
        Set-Content -Path (Join-Path $data 'pg_hba.conf') -Encoding ASCII -Value @(
            "host all $role 127.0.0.1/32 sspi include_realm=1 map=win", "host all $role ::1/128 sspi include_realm=1 map=win",
            'host all all 127.0.0.1/32 scram-sha-256', 'host all all ::1/128 scram-sha-256')
        Set-Content -Path (Join-Path $data 'pg_ident.conf') -Encoding ASCII -Value "win `"/(?i)^$role@.*`$`" $role"
        $p = Start-Process -FilePath (Join-Path $pgBin 'pg_ctl.exe') -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $work 'pgctl.out') -RedirectStandardError (Join-Path $work 'pgctl.err') `
            -ArgumentList @('start', '-D', "`"$data`"", '-l', "`"$(Join-Path $work 'server.log')`"", '-w', '-t', '60', '-o', "`"-p $pgPort -c listen_addresses=localhost`"")
        $null = $p.Handle; $p.WaitForExit()
        if ($p.ExitCode -ne 0) { throw 'pg_ctl start failed' }
        $env:PGPASSWORD = $super
        try {
            & (Join-Path $pgBin 'psql.exe') -X -q -h localhost -p $pgPort -U rehearsal_super -d postgres -v "migrator_password=$migrator" -v "app_password=$appPw" -f (Join-Path $repoRoot 'deploy\sql\audit-bootstrap.sql') *> (Join-Path $work 'bootstrap.log')
            if ($LASTEXITCODE -ne 0) { throw 'audit bootstrap failed' }
        } finally { Remove-Item Env:\PGPASSWORD }
        Psql 'postgres' "CREATE ROLE `"$role`" LOGIN NOSUPERUSER" | Out-Null
        Psql 'postgres' "CREATE DATABASE rehearsal_db OWNER `"$role`"" | Out-Null
        Psql 'rehearsal_db' "CREATE TABLE public.t (id int PRIMARY KEY); ALTER TABLE public.t OWNER TO `"$role`"" | Out-Null
        "port $pgPort"
    }

    $cfg = Join-Path $work 'pgweb.properties'
    Step 'prepare the configuration (dpapi: secrets)' {
        $fwd = { param($x) $x.Replace('\', '/') }
        Set-Content -Path $cfg -Encoding ASCII -Value @(
            'pgweb.mode=test', "server.port=$appPort", 'server.publicHost=localhost',
            'auth.protocols=Negotiate NTLM', 'auth.kerberosOnly=false', 'auth.dataAdminSid=S-1-5-32-545',
            "audit.jdbcUrl=jdbc:postgresql://localhost:$pgPort/pgweb_audit", 'audit.user=pgweb_app', "audit.password=$(Protect $appPw)",
            "audit.hmacKey=$(Protect $hmac)", ('audit.spoolDir=' + (& $fwd (Join-Path $dataRoot 'spool'))),
            'audit.migrator.user=pgweb_migrator', "audit.migrator.password=$(Protect $migrator)",
            'profile.external.host=localhost', "profile.external.port=$pgPort",
            'execution.enabled=true', 'parser.library=native/pg_query.dll',
            ('logging.dir=' + (& $fwd (Join-Path $dataRoot 'logs'))), ('server.workDir=' + (& $fwd (Join-Path $dataRoot 'work'))))
        'secrets: dpapi (CurrentUser)'
    }

    Step "install $v1 (Install-Pgweb.ps1$(if ($noService) { ' -NoService' }))" {
        & (Join-Path $deploy 'Install-Pgweb.ps1') -Bundle (Join-Path $bundles "pgweb-$v1.zip") -Config $cfg -Environment test -ServiceAccount 'LocalSystem' @commonArgs
        # Threat T27: the data folders (config with secrets, spool, logs) carry no inherited access and only SYSTEM,
        # Administrators and the account pgweb runs as (here: this user, -NoService).
        $me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $allowed = @('S-1-5-18', 'S-1-5-32-544', $me)
        foreach ($d in @($dataRoot) + @(Get-ChildItem -LiteralPath $dataRoot -Directory | ForEach-Object FullName) + @(Join-Path $dataRoot 'config\pgweb.properties')) {
            $acl = Get-Acl -LiteralPath $d
            if (-not $acl.AreAccessRulesProtected -and $d -eq $dataRoot) { throw "$d inherits access from its parent" }
            foreach ($rule in $acl.Access) {
                $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
                if ($allowed -notcontains $sid) { throw "$d grants access to $($rule.IdentityReference)" }
            }
        }
        "ACL: SYSTEM, Administrators, $env:USERNAME only"
        "current=$(Split-Path -Leaf (Get-Item (Join-Path $installRoot 'current')).Target)"
    }

    $beforeUpgrade = 0
    Step 'use it: Windows sign-in, SSPI session, an uncommitted change left open' {
        $u = Use-Pgweb 'INSERT INTO t VALUES (1);;BEGIN;;INSERT INTO t VALUES (2)'
        if (@($u.results)[-1].transaction -ne 'OPEN') { throw 'expected an open transaction' }
        if ((Backends) -lt 2) { throw 'expected the main and companion backends' }
        $script:beforeUpgrade = AuditRows
        "session $($u.session); audit rows $beforeUpgrade; backends $(Backends)"
    }

    Step 'back up the audit database (pg_dump, as the DBA would)' {
        $dump = Join-Path $dataRoot 'backup\pgweb_audit-before-upgrade.dump'
        $env:PGPASSWORD = $super
        try { & (Join-Path $pgBin 'pg_dump.exe') -h localhost -p $pgPort -U rehearsal_super -d pgweb_audit -Fc -f $dump; if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed' } }
        finally { Remove-Item Env:\PGPASSWORD }
        "{0:n0} bytes" -f (Get-Item $dump).Length
    }

    Step "upgrade $v1 -> $v2 (Update-Pgweb.ps1): graceful stop closes every session" {
        $updArgs = @{} + $commonArgs
        & (Join-Path $deploy 'Update-Pgweb.ps1') -Bundle (Join-Path $bundles "pgweb-$v2.zip") -AuditBackupConfirmed @updArgs
        $current = Split-Path -Leaf (Get-Item (Join-Path $installRoot 'current')).Target
        if ($current -ne $v2) { throw "current is $current" }
        if ([int](Psql 'rehearsal_db' 'SELECT count(*) FROM t WHERE id = 2') -ne 0) { throw 'the uncommitted row survived the shutdown' }
        if ([int](Psql 'rehearsal_db' 'SELECT count(*) FROM t WHERE id = 1') -ne 1) { throw 'the committed row is missing' }
        $closed = [int](Psql 'pgweb_audit' "SELECT count(*) FROM audit.event WHERE event_type = 'db_disconnect' AND details->>'reason' IN ('shutdown', 'app_session_ended')")
        if ($closed -lt 1) { throw 'no db_disconnect at shutdown in the audit' }
        # The new release has opened no session yet, so any backend of the user's role was left over by the old one.
        $left = Backends
        if ($left -ne 0) { throw "$left PostgreSQL backend(s) of $role survived the shutdown" }
        $after = AuditRows
        if ($after -lt $beforeUpgrade) { throw "audit rows went from $beforeUpgrade to $after" }
        "current=$current; uncommitted row rolled back; user backends after the stop: 0; db_disconnect at shutdown: $closed; audit rows $beforeUpgrade -> $after"
    }

    Step "use $v2" {
        $u = Use-Pgweb 'SELECT count(*) AS n FROM t'
        "rows in t: $(@($u.results)[0].results[0].rows[0][0])"
    }

    Step 'a tampered bundle is refused and nothing changes' {
        $tamper = Join-Path $work 'tampered'
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $bundles "pgweb-$v2.zip"), $tamper)
        $rel = Get-ChildItem $tamper -Directory | Select-Object -First 1
        Set-Content -Path (Join-Path $rel.FullName 'VERSION') -Value '0.1.0-rehearsal.3' -Encoding ASCII
        Add-Content -Path (Join-Path $rel.FullName 'deploy\sql\audit-bootstrap.sql') -Value '-- tampered'
        $failed = $false
        try { & (Join-Path $deploy 'Update-Pgweb.ps1') -Bundle $rel.FullName -AuditBackupConfirmed @commonArgs } catch { $failed = $_.Exception.Message }
        if (-not $failed) { throw 'the tampered bundle was accepted' }
        if ((Split-Path -Leaf (Get-Item (Join-Path $installRoot 'current')).Target) -ne $v2) { throw 'current changed' }
        if ((Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$appPort/api/ready" -TimeoutSec 10).StatusCode -ne 200) { throw 'pgweb is not ready' }
        "refused before anything stopped: $failed"
    }

    Step "roll back $v2 -> $v1 (Rollback-Pgweb.ps1)" {
        $before = AuditRows
        $sw = [Diagnostics.Stopwatch]::StartNew()
        & (Join-Path $deploy 'Rollback-Pgweb.ps1') @commonArgs
        $script:rollbackSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        $current = Split-Path -Leaf (Get-Item (Join-Path $installRoot 'current')).Target
        if ($current -ne $v1) { throw "current is $current" }
        if ($rollbackSeconds -gt 900) { throw "rollback took $rollbackSeconds s (limit 900)" }
        $after = AuditRows
        if ($after -lt $before) { throw 'audit rows lost' }
        $u = Use-Pgweb 'SELECT 1 AS ok'
        "current=$current in $rollbackSeconds s; audit rows $before -> $after; usable"
    }

    Step 'uninstall (keeps data and releases)' {
        & (Join-Path $deploy 'Uninstall-Pgweb.ps1') @commonArgs
        if (Test-Path (Join-Path $installRoot 'current')) { throw 'current junction still there' }
        if (-not (Test-Path (Join-Path $dataRoot 'config\pgweb.properties'))) { throw 'configuration removed' }
        # Install and upgrade expand each bundle into a temporary staging folder; none may be left behind.
        $leftover = @(Get-ChildItem ([IO.Path]::GetTempPath()) -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^pgweb-(install|update)-' -and $_.CreationTime -ge $rehearsalStart })
        if ($leftover.Count -gt 0) { throw "staging folders left in TEMP: $($leftover.Name -join ', ')" }
        $up = $true; try { Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$appPort/api/health" -TimeoutSec 3 | Out-Null } catch { $up = $false }
        if ($up) { throw 'pgweb still answers' }
        $history = @(Get-Content -Raw (Join-Path $dataRoot 'deploy-history.json') | ConvertFrom-Json | ForEach-Object { $_ })
        "stopped; history: $(($history | ForEach-Object { "$($_.action) $($_.version)" }) -join ', ')"
    }
    $ok = $true
} finally {
    $pidFile = Join-Path $dataRoot 'work\pgweb.pid'
    if (Test-Path $pidFile) { Stop-Process -Id ([int](Get-Content $pidFile)) -Force -ErrorAction SilentlyContinue }
    if (Test-Path $data) {
        $p = Start-Process -FilePath (Join-Path $pgBin 'pg_ctl.exe') -ArgumentList @('stop', '-D', "`"$data`"", '-m', 'fast', '-w') -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $work 'stop.out') -RedirectStandardError (Join-Path $work 'stop.err')
        $null = $p.Handle; $p.WaitForExit()
    }
    $evidence = Join-Path $repoRoot "docs\evidence\$Tag"
    New-Item -ItemType Directory -Force -Path $evidence | Out-Null
    $out = Join-Path $evidence ('rehearsal-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    [pscustomobject]@{
        tag = $Tag; when = $started.ToString('o'); overall = $(if ($ok) { 'PASS' } else { 'FAIL' }); mode = $(if ($noService) { 'process (-NoService, not elevated)' } else { 'Windows service' })
        rollbackSeconds = $rollbackSeconds; releases = @($v1, $v2); commit = ((& git -C $repoRoot rev-parse --short HEAD) -join ''); steps = $steps
    } | ConvertTo-Json -Depth 5 | Set-Content -Path $out -Encoding UTF8
    if (-not $KeepEnvironment -and (Test-Path $work)) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }
    Write-Host ("Overall: {0}. Report: {1}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $out)
}
