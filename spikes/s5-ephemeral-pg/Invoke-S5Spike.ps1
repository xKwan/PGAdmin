#Requires -Version 5.1
<#
.SYNOPSIS
    Spike S5: throwaway PostgreSQL cluster harness (no Docker) with randomised
    concurrency, cancellation and lock scenarios.

.DESCRIPTION
    Each run creates a fresh cluster with the installed PostgreSQL binaries
    (initdb/pg_ctl), on a random free port bound to 127.0.0.1, then runs every
    scenario in a random order with random parameters, and finally destroys the
    cluster and verifies nothing was left behind.

    The harness keeps running until RequiredConsecutivePasses runs in a row have
    passed. Any failure resets the consecutive count to 0 and stops the loop
    (unless -ContinueOnFailure) so the cause can be fixed before re-running.

    Scenario sessions run as a non-superuser role, matching the planned design
    where users connect with their own (non-superuser) PostgreSQL accounts.
    The test superuser is only used to create the role/database and to observe
    pg_stat_activity / pg_locks.

.PARAMETER PgBin
    Directory containing initdb.exe, pg_ctl.exe and psql.exe. Defaults to
    $env:PGWEB_PG_BIN, then initdb on PATH, then the newest
    "<ProgramFiles>\PostgreSQL\<n>\bin".

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Invoke-S5Spike.ps1
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
# $PSScriptRoot is not populated inside param() defaults on Windows PowerShell 5.1.
if (-not $ResultsDir) { $ResultsDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'results' }

# ---------------------------------------------------------------------------
# Toolchain discovery
# ---------------------------------------------------------------------------

function Resolve-PgBin {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:PGWEB_PG_BIN) { $candidates += $env:PGWEB_PG_BIN }
    $onPath = Get-Command initdb.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += (Split-Path $onPath.Source -Parent) }
    foreach ($pf in @($env:ProgramFiles, 'C:\Program Files', 'S:\Program Files')) {
        $base = Join-Path $pf 'PostgreSQL'
        if (Test-Path $base) {
            Get-ChildItem $base -Directory |
                Sort-Object { [int]($_.Name -replace '\D', '0') } -Descending |
                ForEach-Object { $candidates += (Join-Path $_.FullName 'bin') }
        }
    }
    foreach ($c in $candidates) {
        if ((Test-Path (Join-Path $c 'initdb.exe')) -and (Test-Path (Join-Path $c 'pg_ctl.exe')) -and (Test-Path (Join-Path $c 'psql.exe'))) {
            return (Resolve-Path $c).Path
        }
    }
    throw 'PostgreSQL binaries (initdb.exe, pg_ctl.exe, psql.exe) not found. Pass -PgBin or set PGWEB_PG_BIN.'
}

# ---------------------------------------------------------------------------
# Native process helpers (file redirection only: postgres.exe inherits the
# handles, so pipes would never close and the caller would hang)
# ---------------------------------------------------------------------------

function ConvertTo-QuotedArg {
    param([string]$Value)
    if ($Value -eq '') { return '""' }
    if ($Value -notmatch '[\s"]') { return $Value }
    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

function Start-Native {
    param($C, [string]$File, [string[]]$ArgList, [string]$Tag)
    $stem = Join-Path $C.Root ($Tag + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $argString = ($ArgList | ForEach-Object { ConvertTo-QuotedArg $_ }) -join ' '
    $p = Start-Process -FilePath $File -ArgumentList $argString -NoNewWindow -PassThru `
        -RedirectStandardOutput "$stem.out" -RedirectStandardError "$stem.err"
    $null = $p.Handle   # keeps ExitCode available after exit
    [pscustomobject]@{ Process = $p; OutFile = "$stem.out"; ErrFile = "$stem.err"; Tag = $Tag; Started = [DateTime]::UtcNow }
}

function Wait-Native {
    param($Handle, [int]$TimeoutSec)
    if (-not $Handle.Process.WaitForExit($TimeoutSec * 1000)) {
        try { $Handle.Process.Kill() } catch { }
        throw "$($Handle.Tag) did not exit within $TimeoutSec s"
    }
    $Handle.Process.WaitForExit()
    $out = ''; $err = ''
    # "$(...)" always yields a string; [string](Get-Content -Raw <empty file>) yields $null on PS 5.1.
    if (Test-Path $Handle.OutFile) { $out = "$(Get-Content -Raw $Handle.OutFile)" }
    if (Test-Path $Handle.ErrFile) { $err = "$(Get-Content -Raw $Handle.ErrFile)" }
    [pscustomobject]@{
        ExitCode  = $Handle.Process.ExitCode
        Out       = $out
        Err       = $err
        ElapsedMs = [int]([DateTime]::UtcNow - $Handle.Started).TotalMilliseconds
    }
}

function Wait-Until {
    param([scriptblock]$Condition, [int]$TimeoutSec, [string]$What)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Timed out after $TimeoutSec s waiting for: $What"
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

function New-Secret {
    $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Get-FreePort {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

function Test-PortFree {
    param([int]$Port)
    try {
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
        $listener.Start(); $listener.Stop(); return $true
    } catch { return $false }
}

# ---------------------------------------------------------------------------
# psql helpers
# ---------------------------------------------------------------------------

function Start-Psql {
    param($C, [string]$Sql, [string]$App, [string]$User, [string]$Db)
    if (-not $User) { $User = $C.AppUser }
    if (-not $Db) { $Db = $C.Db }
    $file = Join-Path $C.Root ('sql-' + $App + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.sql')
    Set-Content -Path $file -Value ("\set VERBOSITY verbose`n" + $Sql + "`n") -Encoding ASCII
    $conn = "host=127.0.0.1 port=$($C.Port) dbname=$Db user=$User application_name=$App connect_timeout=10"
    Start-Native -C $C -File $C.Psql -ArgList @('-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-d', $conn, '-f', $file) -Tag $App
}

function Invoke-Psql {
    param($C, [string]$Sql, [string]$App = 'harness', [string]$User, [string]$Db, [int]$TimeoutSec = 60)
    Wait-Native (Start-Psql -C $C -Sql $Sql -App $App -User $User -Db $Db) $TimeoutSec
}

function Get-Scalar {
    param($C, [string]$Sql, [string]$User, [string]$Db)
    $r = Invoke-Psql -C $C -Sql $Sql -App 'harness' -User $User -Db $Db
    if ($r.ExitCode -ne 0) {
        # Never let generated passwords reach error messages or reports.
        $safeSql = $Sql -replace "(?i)PASSWORD\s+'[^']*'", "PASSWORD '***'"
        throw "SQL failed (exit $($r.ExitCode)): $safeSql`n$($r.Err)"
    }
    return $r.Out.Trim()
}

# Observation queries run as the test superuser.
function Get-Activity {
    param($C, [string]$App)
    Get-Scalar -C $C -User $C.Super -Db 'postgres' -Sql ("SELECT pid || '|' || coalesce(state,'') || '|' || coalesce(wait_event_type,'') || '|' || coalesce(wait_event,'') FROM pg_stat_activity WHERE application_name = '" + $App + "';")
}

function Get-LockCount {
    param($C, [int]$BackendPid)
    [int](Get-Scalar -C $C -User $C.Super -Db 'postgres' -Sql "SELECT count(*) FROM pg_locks WHERE pid = $BackendPid;")
}

function Get-Value {
    param($C, [int]$Id)
    [int](Get-Scalar -C $C -Sql "SELECT v FROM t WHERE id = $Id;")
}

# Cancel/terminate run as the scenario role itself (the planned "companion
# connection": a non-superuser signalling its own backends).
function Send-Signal {
    param($C, [string]$App, [ValidateSet('cancel', 'terminate')][string]$Kind)
    $fn = if ($Kind -eq 'cancel') { 'pg_cancel_backend' } else { 'pg_terminate_backend' }
    $res = Get-Scalar -C $C -Sql ("SELECT bool_and($fn(pid)) FROM pg_stat_activity WHERE application_name = '" + $App + "';")
    Assert-True ($res -eq 't') "$fn on '$App' returned '$res' (expected t)"
}

function Start-Barrier {
    param($C, [int]$Key)
    $app = "barrier_$Key"
    $h = Start-Psql -C $C -App $app -Sql "SELECT pg_advisory_lock($Key); SELECT pg_sleep(120);"
    Wait-Until { (Get-Activity $C $app) -match '\|PgSleep$' } 20 'barrier session holding advisory lock'
    return $h
}

function Stop-Barrier {
    param($C, $Handle, [int]$Key)
    Send-Signal -C $C -App "barrier_$Key" -Kind terminate
    $null = Wait-Native $Handle 20
}

# ---------------------------------------------------------------------------
# Cluster lifecycle and safety guard
# ---------------------------------------------------------------------------

function New-Cluster {
    param([string]$Bin)
    $root = Join-Path ([IO.Path]::GetTempPath()) ('pgweb-s5-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root | Out-Null
    $C = [pscustomobject]@{
        Root    = $root
        Data    = (Join-Path $root 'data')
        Log     = (Join-Path $root 'postgres.log')
        Port    = 0
        Token   = [guid]::NewGuid().ToString('N')
        Super   = 'pgweb_test_super'
        SuperPw = (New-Secret)
        AppUser = 's5_user'
        AppPw   = (New-Secret)
        Db      = 's5'
        Psql    = (Join-Path $Bin 'psql.exe')
        PgCtl   = (Join-Path $Bin 'pg_ctl.exe')
        InitDb  = (Join-Path $Bin 'initdb.exe')
        Started = $false
    }
    try {
        Set-Content -Path (Join-Path $root '.pgweb-test-cluster') -Value $C.Token -Encoding ASCII
        $pwFile = Join-Path $root 'pw.txt'
        Set-Content -Path $pwFile -Value $C.SuperPw -Encoding ASCII
        $r = Wait-Native (Start-Native -C $C -File $C.InitDb -Tag 'initdb' -ArgList @(
                '-D', $C.Data, '-U', $C.Super, '-A', 'scram-sha-256', "--pwfile=$pwFile",
                '-E', 'UTF8', '--no-locale', '--no-sync')) 180
        Remove-Item $pwFile -Force
        if ($r.ExitCode -ne 0) { throw "initdb failed (exit $($r.ExitCode)):`n$($r.Err)" }

        for ($attempt = 1; $attempt -le 3 -and -not $C.Started; $attempt++) {
            $C.Port = Get-FreePort
            $opts = "-p $($C.Port) -c listen_addresses=127.0.0.1 -c pgweb.test_run_token=$($C.Token) -c deadlock_timeout=200ms -c fsync=off"
            $r = Wait-Native (Start-Native -C $C -File $C.PgCtl -Tag 'pgctl-start' -ArgList @(
                    'start', '-D', $C.Data, '-l', $C.Log, '-w', '-t', '60', '-o', $opts)) 90
            if ($r.ExitCode -eq 0) { $C.Started = $true }
        }
        if (-not $C.Started) { throw "pg_ctl start failed:`n$($r.Err)`n$(Get-Content -Raw $C.Log -ErrorAction SilentlyContinue)" }

        $pgpass = Join-Path $root 'pgpass.conf'
        Set-Content -Path $pgpass -Encoding ASCII -Value @(
            "127.0.0.1:$($C.Port):*:$($C.Super):$($C.SuperPw)",
            "127.0.0.1:$($C.Port):*:$($C.AppUser):$($C.AppPw)")
        $env:PGPASSFILE = $pgpass

        Assert-TestCluster $C
        $null = Get-Scalar -C $C -User $C.Super -Db 'postgres' -Sql (
            "CREATE ROLE $($C.AppUser) LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB PASSWORD '$($C.AppPw)';" +
            "CREATE DATABASE $($C.Db) OWNER $($C.AppUser);")
        return $C
    } catch {
        Remove-Cluster $C
        throw
    }
}

function Assert-TestCluster {
    param($C)
    $marker = Join-Path $C.Root '.pgweb-test-cluster'
    if (-not (Test-Path $marker)) { throw 'Safety guard: marker file missing; refusing to use this cluster.' }
    if ((Get-Content -Raw $marker).Trim() -ne $C.Token) { throw 'Safety guard: marker token mismatch; refusing to use this cluster.' }
    $serverToken = Get-Scalar -C $C -User $C.Super -Db 'postgres' -Sql "SELECT current_setting('pgweb.test_run_token', true);"
    if ($serverToken -ne $C.Token) { throw 'Safety guard: server run token mismatch; refusing to use this cluster.' }
}

function Get-ClusterProcesses {
    param($C)
    $a = $C.Data.ToLowerInvariant()
    $b = $a.Replace('\', '/')
    @(Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" | Where-Object {
            $_.CommandLine -and (($_.CommandLine.ToLowerInvariant().Contains($a)) -or ($_.CommandLine.ToLowerInvariant().Contains($b)))
        })
}

function Remove-Cluster {
    param($C)
    if ($null -eq $C) { return }
    if ($C.Started) {
        try {
            $null = Wait-Native (Start-Native -C $C -File $C.PgCtl -Tag 'pgctl-stop' -ArgList @('stop', '-D', $C.Data, '-m', 'fast', '-w', '-t', '30')) 45
        } catch { }
    }
    foreach ($p in (Get-ClusterProcesses $C)) {
        try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch { }
    }
    for ($i = 0; $i -lt 20 -and (Test-Path $C.Root); $i++) {
        try { Remove-Item -Recurse -Force $C.Root -ErrorAction Stop } catch { Start-Sleep -Milliseconds 250 }
    }
    if ($env:PGPASSFILE -and $env:PGPASSFILE.StartsWith($C.Root)) { Remove-Item Env:\PGPASSFILE }
}

function Assert-ClusterGone {
    param($C)
    Assert-True (-not (Test-Path $C.Root)) "cluster directory still exists: $($C.Root)"
    # @() is required: an empty array returned from a function arrives as $null.
    Assert-True (@(Get-ClusterProcesses $C).Count -eq 0) 'postgres.exe processes still running for the cluster'
    if ($C.Port -gt 0) { Assert-True (Test-PortFree $C.Port) "port $($C.Port) still in use" }
}

# ---------------------------------------------------------------------------
# Scenarios. Each receives the cluster and a seeded RNG, throws on failure,
# and returns a short description of the random parameters it used.
# ---------------------------------------------------------------------------

function Get-DistinctIds {
    param([System.Random]$Rng, [int]$Count)
    $ids = @()
    while ($ids.Count -lt $Count) { $n = $Rng.Next(1, 21); if ($ids -notcontains $n) { $ids += $n } }
    return $ids
}

$Scenarios = [ordered]@{

    'basic-crud' = {
        param($C, $Rng)
        $n = $Rng.Next(50, 501); $m = $Rng.Next(1, 100)
        $null = Get-Scalar -C $C -Sql "DROP TABLE IF EXISTS b; CREATE TABLE b(id serial PRIMARY KEY, x int); INSERT INTO b(x) SELECT g * $m FROM generate_series(1, $n) g;"
        $res = Get-Scalar -C $C -Sql 'SELECT count(*) || '':'' || sum(x) FROM b;'
        $expected = "${n}:" + ($m * $n * ($n + 1) / 2)
        Assert-True ($res -eq $expected) "count:sum was '$res', expected '$expected'"
        "rows=$n mult=$m"
    }

    'safety-guard' = {
        param($C, $Rng)
        Assert-TestCluster $C
        $fake = $C.PSObject.Copy(); $fake.Token = [guid]::NewGuid().ToString('N')
        $refused = $false
        try { Assert-TestCluster $fake } catch { $refused = $_.Exception.Message -match 'Safety guard' }
        Assert-True $refused 'guard did not refuse a cluster with a mismatched token'
        'guard accepts own cluster, refuses mismatched token'
    }

    'timeout-backstop-overridable' = {
        param($C, $Rng)
        $null = Get-Scalar -C $C -User $C.Super -Db 'postgres' -Sql "ALTER ROLE $($C.AppUser) SET statement_timeout = '1s';"
        try {
            # 1) The role-level backstop works when untouched...
            $r = Invoke-Psql -C $C -App 'backstop' -Sql 'SELECT pg_sleep(5);'
            Assert-True ($r.ExitCode -ne 0 -and $r.Err -match '57014') "role statement_timeout did not fire: $($r.Err)"
            # 2) ...but the user can switch it off, so it is not a control.
            $waitMs = $Rng.Next(1500, 2600)
            $h = Start-Psql -C $C -App 'override' -Sql "SET statement_timeout = 0; SET lock_timeout = 0; SELECT pg_sleep(60);"
            Wait-Until { (Get-Activity $C 'override') -match '\|PgSleep$' } 15 'override session sleeping'
            $backendPid = [int]((Get-Activity $C 'override').Split('|')[0])
            Start-Sleep -Milliseconds $waitMs
            Assert-True ((Get-Activity $C 'override') -match '\|active\|') 'session stopped although the user disabled statement_timeout'
            # 3) The external (app wall-clock) cancel still stops it.
            Send-Signal -C $C -App 'override' -Kind cancel
            $r = Wait-Native $h 10
            Assert-True ($r.ExitCode -ne 0 -and $r.Err -match '57014') "cancel did not end the statement with 57014: $($r.Err)"
            Wait-Until { (Get-Activity $C 'override') -eq '' } 5 'cancelled backend gone from pg_stat_activity'
            Assert-True ((Get-LockCount $C $backendPid) -eq 0) 'cancelled backend still holds locks'
            "backstop=1s override-wait=${waitMs}ms"
        } finally {
            $null = Get-Scalar -C $C -User $C.Super -Db 'postgres' -Sql "ALTER ROLE $($C.AppUser) RESET statement_timeout;"
        }
    }

    'cancel-releases-locks' = {
        param($C, $Rng)
        $id = (Get-DistinctIds $Rng 1)[0]; $delta = $Rng.Next(1, 50)
        $v0 = Get-Value $C $id
        $h = Start-Psql -C $C -App 'holder' -Sql "BEGIN; UPDATE t SET v = v + $delta WHERE id = $id; SELECT pg_sleep(60); COMMIT;"
        Wait-Until { (Get-Activity $C 'holder') -match '\|PgSleep$' } 15 'holder sleeping inside transaction'
        $backendPid = [int]((Get-Activity $C 'holder').Split('|')[0])
        Assert-True ((Get-LockCount $C $backendPid) -gt 0) 'holder holds no locks before cancel'
        Send-Signal -C $C -App 'holder' -Kind cancel
        $r = Wait-Native $h 10
        Assert-True ($r.ExitCode -ne 0 -and $r.Err -match '57014') "holder not cancelled with 57014: $($r.Err)"
        Wait-Until { (Get-LockCount $C $backendPid) -eq 0 } 5 'locks released after cancel'
        $r = Invoke-Psql -C $C -App 'after' -Sql "SET lock_timeout = '1s'; UPDATE t SET v = v WHERE id = $id;"
        Assert-True ($r.ExitCode -eq 0) "row still locked after cancel: $($r.Err)"
        Assert-True ((Get-Value $C $id) -eq $v0) 'cancelled transaction was not rolled back'
        "id=$id delta=$delta"
    }

    'lock-timeout-then-terminate' = {
        param($C, $Rng)
        $id = (Get-DistinctIds $Rng 1)[0]; $timeoutMs = $Rng.Next(200, 900)
        $v0 = Get-Value $C $id
        $h = Start-Psql -C $C -App 'holder2' -Sql "BEGIN; UPDATE t SET v = v + 1 WHERE id = $id; SELECT pg_sleep(60); COMMIT;"
        Wait-Until { (Get-Activity $C 'holder2') -match '\|PgSleep$' } 15 'holder2 sleeping inside transaction'
        $backendPid = [int]((Get-Activity $C 'holder2').Split('|')[0])
        $r = Invoke-Psql -C $C -App 'waiter' -Sql "SET lock_timeout = '${timeoutMs}ms'; UPDATE t SET v = v + 1 WHERE id = $id;"
        Assert-True ($r.ExitCode -ne 0 -and $r.Err -match '55P03') "expected 55P03 lock_not_available: $($r.Err)"
        Assert-True ($r.ElapsedMs -ge $timeoutMs) "lock wait ended after $($r.ElapsedMs) ms, before the $timeoutMs ms timeout"
        Send-Signal -C $C -App 'holder2' -Kind terminate
        $null = Wait-Native $h 10
        Wait-Until { (Get-Activity $C 'holder2') -eq '' } 5 'terminated backend gone'
        Assert-True ((Get-LockCount $C $backendPid) -eq 0) 'terminated backend still holds locks'
        Assert-True ((Get-Value $C $id) -eq $v0) 'terminated transaction was not rolled back'
        "id=$id lock_timeout=${timeoutMs}ms"
    }

    'same-row-update' = {
        param($C, $Rng)
        $id = (Get-DistinctIds $Rng 1)[0]; $a = $Rng.Next(1, 100); $b = $Rng.Next(1, 100); $key = $Rng.Next(1000, 9999)
        $v0 = Get-Value $C $id
        $bar = Start-Barrier $C $key
        $ha = Start-Psql -C $C -App 'rowA' -Sql "BEGIN; UPDATE t SET v = v + $a WHERE id = $id; SELECT pg_advisory_lock($key); COMMIT;"
        Wait-Until { (Get-Activity $C 'rowA') -match '\|Lock\|advisory$' } 15 'A holds row lock and waits on barrier'
        $hb = Start-Psql -C $C -App 'rowB' -Sql "UPDATE t SET v = v + $b WHERE id = $id;"
        Wait-Until { (Get-Activity $C 'rowB') -match '\|Lock\|transactionid$' } 15 'B blocked on A''s row lock'
        Stop-Barrier $C $bar $key
        $ra = Wait-Native $ha 15; $rb = Wait-Native $hb 15
        Assert-True ($ra.ExitCode -eq 0) "A failed: $($ra.Err)"
        Assert-True ($rb.ExitCode -eq 0) "B failed: $($rb.Err)"
        $v1 = Get-Value $C $id
        Assert-True ($v1 -eq ($v0 + $a + $b)) "lost update: v=$v1 expected $($v0 + $a + $b)"
        "id=$id a=$a b=$b"
    }

    'deadlock' = {
        param($C, $Rng)
        $ids = Get-DistinctIds $Rng 2; $k1 = $ids[0]; $k2 = $ids[1]
        $a = $Rng.Next(1, 100); $b = $Rng.Next(100, 200); $key = $Rng.Next(1000, 9999)
        $v1 = Get-Value $C $k1; $v2 = Get-Value $C $k2
        $bar = Start-Barrier $C $key
        $ha = Start-Psql -C $C -App 'dlA' -Sql "BEGIN; UPDATE t SET v = v + $a WHERE id = $k1; SELECT pg_advisory_lock($key); UPDATE t SET v = v + $a WHERE id = $k2; COMMIT;"
        Wait-Until { (Get-Activity $C 'dlA') -match '\|Lock\|advisory$' } 15 'A holds k1 and waits on barrier'
        $hb = Start-Psql -C $C -App 'dlB' -Sql "BEGIN; UPDATE t SET v = v + $b WHERE id = $k2; UPDATE t SET v = v + $b WHERE id = $k1; COMMIT;"
        Wait-Until { (Get-Activity $C 'dlB') -match '\|Lock\|transactionid$' } 15 'B holds k2 and waits on k1'
        Stop-Barrier $C $bar $key
        $ra = Wait-Native $ha 20; $rb = Wait-Native $hb 20
        $aDead = ($ra.ExitCode -ne 0 -and $ra.Err -match '40P01')
        $bDead = ($rb.ExitCode -ne 0 -and $rb.Err -match '40P01')
        Assert-True ($aDead -xor $bDead) "expected exactly one 40P01 victim. A: $($ra.ExitCode) $($ra.Err) | B: $($rb.ExitCode) $($rb.Err)"
        if ($aDead) { Assert-True ($rb.ExitCode -eq 0) "survivor B failed: $($rb.Err)"; $inc = $b } else { Assert-True ($ra.ExitCode -eq 0) "survivor A failed: $($ra.Err)"; $inc = $a }
        Assert-True ((Get-Value $C $k1) -eq ($v1 + $inc) -and (Get-Value $C $k2) -eq ($v2 + $inc)) 'final values do not match the surviving transaction only'
        $victim = if ($aDead) { 'A' } else { 'B' }
        "k1=$k1 k2=$k2 victim=$victim"
    }

    'serialization-failure' = {
        param($C, $Rng)
        # Note: PowerShell variable names are case-insensitive, so never name a local $c (it would replace $C).
        $id = (Get-DistinctIds $Rng 1)[0]; $concurrentInc = $Rng.Next(1, 100); $key = $Rng.Next(1000, 9999)
        $v0 = Get-Value $C $id
        $bar = Start-Barrier $C $key
        $ha = Start-Psql -C $C -App 'rrA' -Sql "BEGIN ISOLATION LEVEL REPEATABLE READ; SELECT v FROM t WHERE id = $id; SELECT pg_advisory_lock($key); UPDATE t SET v = v + 1 WHERE id = $id; COMMIT;"
        Wait-Until { (Get-Activity $C 'rrA') -match '\|Lock\|advisory$' } 15 'A took its snapshot and waits on barrier'
        $rb = Invoke-Psql -C $C -App 'rrB' -Sql "UPDATE t SET v = v + $concurrentInc WHERE id = $id;"
        Assert-True ($rb.ExitCode -eq 0) "concurrent update failed: $($rb.Err)"
        Stop-Barrier $C $bar $key
        $ra = Wait-Native $ha 15
        Assert-True ($ra.ExitCode -ne 0 -and $ra.Err -match '40001') "expected 40001 serialization_failure: $($ra.Err)"
        Assert-True ((Get-Value $C $id) -eq ($v0 + $concurrentInc)) 'final value should include only the committed concurrent update'
        "id=$id concurrent=+$concurrentInc"
    }

    'cleanup-after-failure' = {
        param($C, $Rng)
        $c2 = New-Cluster -Bin $script:PgBinResolved
        $saved = $env:PGPASSFILE
        try {
            try { throw 'injected failure' } finally { Remove-Cluster $c2 }
        } catch {
            if ($_.Exception.Message -ne 'injected failure') { throw }
        } finally {
            $env:PGPASSFILE = (Join-Path $C.Root 'pgpass.conf')
        }
        Assert-ClusterGone $c2
        "second cluster on port $($c2.Port) removed after injected failure"
    }
}

# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

function Invoke-OneRun {
    param([int]$RunSeed)
    $rng = New-Object System.Random($RunSeed)
    $order = @($Scenarios.Keys | Sort-Object { $rng.Next() })
    $results = @()
    $C = $null
    try {
        $C = New-Cluster -Bin $script:PgBinResolved
        $seedSql = "CREATE TABLE t(id int PRIMARY KEY, v int NOT NULL); INSERT INTO t SELECT g, (random() * 1000)::int FROM generate_series(1, 20) g;"
        $null = Get-Scalar -C $C -Sql $seedSql
        foreach ($name in $order) {
            Assert-TestCluster $C
            $sw = [Diagnostics.Stopwatch]::StartNew()
            try {
                $detail = & $Scenarios[$name] $C $rng
                $results += [pscustomobject]@{ Scenario = $name; Passed = $true; Ms = $sw.ElapsedMilliseconds; Detail = [string]$detail }
            } catch {
                $results += [pscustomobject]@{ Scenario = $name; Passed = $false; Ms = $sw.ElapsedMilliseconds; Detail = $_.Exception.Message }
                throw
            }
        }
    } finally {
        if ($C) {
            Remove-Cluster $C
            try {
                Assert-ClusterGone $C
                $results += [pscustomobject]@{ Scenario = 'teardown-leaves-nothing'; Passed = $true; Ms = 0; Detail = "port $($C.Port)" }
            } catch {
                $results += [pscustomobject]@{ Scenario = 'teardown-leaves-nothing'; Passed = $false; Ms = 0; Detail = $_.Exception.Message }
            }
        }
        $script:LastRunResults = $results
    }
    if (@($results | Where-Object { -not $_.Passed }).Count -gt 0) { throw 'teardown check failed' }
    return $results
}

$script:PgBinResolved = Resolve-PgBin $PgBin
$versionLine = (& (Join-Path $script:PgBinResolved 'psql.exe') --version)
if ($Seed -eq 0) { $Seed = [Environment]::TickCount -band 0x7FFFFFFF }
$master = New-Object System.Random($Seed)

Write-Host "S5 spike | PostgreSQL bin: $script:PgBinResolved ($versionLine) | master seed: $Seed"
Write-Host "Goal: $RequiredConsecutivePasses consecutive passing runs (max $MaxRuns runs)`n"

$runs = @()
$consecutive = 0
for ($run = 1; $run -le $MaxRuns; $run++) {
    $runSeed = $master.Next()
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $passed = $true; $err = ''
    try { $null = Invoke-OneRun -RunSeed $runSeed } catch { $passed = $false; $err = $_.Exception.Message + "`n" + $_.ScriptStackTrace }
    $results = $script:LastRunResults
    if ($passed) { $consecutive++ } else { $consecutive = 0 }
    $runs += [pscustomobject]@{ Run = $run; Seed = $runSeed; Passed = $passed; Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1); Consecutive = $consecutive; Error = $err; Scenarios = $results }

    $status = if ($passed) { 'PASS' } else { 'FAIL' }
    Write-Host ("Run {0,2} seed {1,10}  {2}  {3,5}s  consecutive={4}" -f $run, $runSeed, $status, [math]::Round($sw.Elapsed.TotalSeconds, 1), $consecutive)
    foreach ($s in $results) {
        $mark = if ($s.Passed) { 'ok  ' } else { 'FAIL' }
        Write-Host ("        {0} {1,-30} {2,6}ms  {3}" -f $mark, $s.Scenario, $s.Ms, $s.Detail)
    }
    if (-not $passed) { Write-Host "        error: $err" }
    if ($consecutive -ge $RequiredConsecutivePasses) { break }
    if (-not $passed -and -not $ContinueOnFailure) { break }
}

$ok = $consecutive -ge $RequiredConsecutivePasses
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$os = Get-CimInstance Win32_OperatingSystem
$report = [pscustomobject]@{
    Spike                     = 'S5'
    Timestamp                 = (Get-Date).ToString('o')
    Machine                   = "$($os.Caption) $($os.Version)"
    PowerShell                = $PSVersionTable.PSVersion.ToString()
    PostgreSQL                = "$versionLine"
    MasterSeed                = $Seed
    RequiredConsecutivePasses = $RequiredConsecutivePasses
    Result                    = $(if ($ok) { 'PASS' } else { 'FAIL' })
    Runs                      = $runs
}
$report | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $ResultsDir "s5-$stamp.json") -Encoding UTF8
Write-Host "`nResult: $($report.Result) ($consecutive consecutive passes). Report: $(Join-Path $ResultsDir "s5-$stamp.json")"
if ($ok) { exit 0 } else { exit 1 }
