<#
.SYNOPSIS
    Starts a local manual-verification environment: a throwaway PostgreSQL 18 cluster and two pgweb instances.
.DESCRIPTION
    For trying the application by hand in Edge on a developer workstation (docs\dev-setup.md section 7).
    Same set-up as scripts\test\Invoke-E2E.ps1, but with fixed ports and left running:
      - PostgreSQL 18 on localhost:<PgPort> (default 55434). It authenticates this Windows account with SSPI as the
        role with the lower-cased user name, holds the audit database (shipped bootstrap) and a demo database
        `demo_db` owned by that role. Both pgweb profiles (internal and external) point at it.
      - pgweb on http://localhost:<OffPort> (default 8090) with SQL execution OFF, as shipped.
      - pgweb on http://localhost:<OnPort> (default 8091) with SQL execution ON (local test only; never a server).
    Both run in test mode (NTLM allowed, plain secrets) and write to the same audit database.
    Everything lives in .verify-env\ (git-ignored). Running the script again restarts what is stopped and keeps the
    data. Stop with Stop-VerifyEnv.ps1 (-Destroy deletes .verify-env\).
    Needs the JAR with the SPA (backend\target, `mvn -Pwith-spa package`) and, for the execution-on instance, the
    parser DLL (native\libpg_query\build\pg_query.dll).
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev\Start-VerifyEnv.ps1
#>
[CmdletBinding()]
param([int]$PgPort = 55434, [int]$OffPort = 8090, [int]$OnPort = 8091)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet

$root = Join-Path $repoRoot '.verify-env'
$data = Join-Path $root 'data'
$stateFile = Join-Path $root 'state.json'
$role = $env:USERNAME.ToLowerInvariant()
$line = Get-Content (Join-Path $repoRoot 'configuration.properties') | Where-Object { $_ -match '^preexisting\.postgresql\.path=' } | Select-Object -First 1
$pgBin = @($env:PGWEB_PG_BIN, $(if ($line) { ($line -split '=', 2)[1].Replace('/', '\') }), "$env:ProgramFiles\PostgreSQL\18\bin") |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'initdb.exe')) } | Select-Object -First 1
if (-not $pgBin) { throw 'PostgreSQL 18 binaries not found (set PGWEB_PG_BIN).' }
$jar = Get-ChildItem (Join-Path $repoRoot 'backend\target') -Filter 'pgweb-backend-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
if (-not $jar) { throw 'No JAR in backend\target: build it with the SPA first (docs\dev-setup.md section 7).' }
$dll = Join-Path $repoRoot 'native\libpg_query\build\pg_query.dll'
if (-not (Test-Path $dll)) { throw "Parser DLL missing: $dll (native\libpg_query\Build-PgQuery.ps1)" }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$fwd = { param($p) $p.Replace('\', '/') }

function New-Secret { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ }) }
function PgCtl([string[]]$PgArgs) {
    # The server that `pg_ctl start` launches inherits pg_ctl's handles (S5): output to files, wait for pg_ctl only.
    $p = Start-Process -FilePath (Join-Path $pgBin 'pg_ctl.exe') -ArgumentList $PgArgs -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $root 'pg_ctl.out') -RedirectStandardError (Join-Path $root 'pg_ctl.err')
    $null = $p.Handle; $p.WaitForExit(); return $p.ExitCode
}
function Invoke-Psql([string]$Db, [string]$File, [string[]]$Vars = @()) {
    $a = @('-X', '-q', '-v', 'ON_ERROR_STOP=1', '-h', 'localhost', '-p', $PgPort, '-U', 'pgweb_verify_super', '-d', $Db) + $Vars + @('-f', $File)
    & (Join-Path $pgBin 'psql.exe') @a *>> (Join-Path $root 'setup.log')
    if ($LASTEXITCODE -ne 0) { throw "psql $File failed; see $root\setup.log" }
}
function Test-Listening([int]$Port) { [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) }

New-Item -ItemType Directory -Force -Path $root | Out-Null
$fresh = -not (Test-Path $stateFile)
if ($fresh) {
    if (Test-Listening $PgPort) { throw "Port $PgPort is already in use; choose another with -PgPort." }
    $s = [pscustomobject]@{ pgPort = $PgPort; super = (New-Secret); migrator = (New-Secret); app = (New-Secret); hmac = (New-Secret) + (New-Secret) }
    $pw = Join-Path $root 'pw.txt'; [IO.File]::WriteAllText($pw, $s.super)
    & (Join-Path $pgBin 'initdb.exe') -D $data -U pgweb_verify_super -A scram-sha-256 "--pwfile=$pw" -E UTF8 --no-locale *> (Join-Path $root 'initdb.log')
    Remove-Item $pw
    if ($LASTEXITCODE -ne 0) { throw "initdb failed; see $root\initdb.log" }
    Set-Content -Path (Join-Path $data 'pg_hba.conf') -Encoding ASCII -Value @(
        "host all $role 127.0.0.1/32 sspi include_realm=1 map=win",
        "host all $role ::1/128 sspi include_realm=1 map=win",
        'host all all 127.0.0.1/32 scram-sha-256',
        'host all all ::1/128 scram-sha-256')
    Set-Content -Path (Join-Path $data 'pg_ident.conf') -Encoding ASCII -Value "win `"/(?i)^$role@.*`$`" $role"
    $s | ConvertTo-Json | Set-Content -Path $stateFile -Encoding ASCII
}
$s = Get-Content -Raw $stateFile | ConvertFrom-Json
$PgPort = $s.pgPort

# Check the pgweb ports before starting anything. Any listener counts, IPv6 included: Docker Desktop and the WSL
# relay hold [::]:8080 / [::1]:8080 on some workstations, and the browser's localhost would reach them instead of
# pgweb (bound to 127.0.0.1 only).
$pidFile = Join-Path $root 'pids.json'
$pids = @{}
if (Test-Path $pidFile) { (Get-Content -Raw $pidFile | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $pids[$_.Name] = $_.Value } }
function Test-OwnInstance([string]$Name) { $pids.ContainsKey($Name) -and [bool](Get-Process -Id $pids[$Name] -ErrorAction SilentlyContinue) }
foreach ($c in @(@{ n = 'exec-off'; p = $OffPort }, @{ n = 'exec-on'; p = $OnPort })) {
    if (-not (Test-OwnInstance $c.n) -and (Test-Listening $c.p)) {
        throw "Port $($c.p) is already in use (see docs\ports-and-services.md); choose another with -OffPort/-OnPort."
    }
}

if ((PgCtl @('status', '-D', "`"$data`"")) -ne 0) {
    if ((PgCtl @('start', '-D', "`"$data`"", '-l', "`"$(Join-Path $root 'server.log')`"", '-w', '-t', '60', '-o', "`"-p $PgPort -c listen_addresses=localhost`"")) -ne 0) {
        throw "pg_ctl start failed; see $root\server.log"
    }
}

if ($fresh) {
    $env:PGPASSWORD = $s.super
    try {
        Invoke-Psql 'postgres' (Join-Path $repoRoot 'deploy\sql\audit-bootstrap.sql') @('-v', "migrator_password=$($s.migrator)", '-v', "app_password=$($s.app)")
        $setup = Join-Path $root 'roles.sql'
        Set-Content -Path $setup -Encoding ASCII -Value @("CREATE ROLE `"$role`" LOGIN NOSUPERUSER;", "CREATE DATABASE demo_db OWNER `"$role`";")
        Invoke-Psql 'postgres' $setup
        $seed = Join-Path $root 'seed.sql'
        Set-Content -Path $seed -Encoding ASCII -Value @(
            "SET ROLE `"$role`";",
            'CREATE TABLE public.items (id int PRIMARY KEY, name text, price numeric(10,2), created timestamptz DEFAULT now());',
            "INSERT INTO public.items (id, name, price) SELECT g, 'item ' || g, round((random() * 100)::numeric, 2) FROM generate_series(1, 2500) g;",
            'CREATE VIEW public.cheap_items AS SELECT * FROM public.items WHERE price < 10;',
            'CREATE SCHEMA sales;',
            'CREATE TABLE sales.orders (id serial PRIMARY KEY, item_id int REFERENCES public.items(id), qty int NOT NULL);',
            'INSERT INTO sales.orders (item_id, qty) SELECT (random() * 2499)::int + 1, (random() * 9)::int + 1 FROM generate_series(1, 200);')
        Invoke-Psql 'demo_db' $seed
    } finally { Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue }
}

function Write-Config([string]$Name, [int]$Port, [bool]$Exec) {
    $dir = Join-Path $root $Name
    $lines = @(
        "# Generated by scripts\dev\Start-VerifyEnv.ps1 ($Name): local verification only; test mode, plain secrets, throwaway cluster.",
        'pgweb.mode=test', "server.port=$Port", 'server.publicHost=localhost',
        'auth.protocols=Negotiate NTLM', 'auth.kerberosOnly=false', 'auth.dataAdminSid=S-1-5-32-545',
        "audit.jdbcUrl=jdbc:postgresql://localhost:$PgPort/pgweb_audit", 'audit.user=pgweb_app', "audit.password=plain:$($s.app)",
        "audit.hmacKey=plain:$($s.hmac)", ('audit.spoolDir=' + (& $fwd (Join-Path $dir 'spool'))),
        'audit.migrator.user=pgweb_migrator', "audit.migrator.password=plain:$($s.migrator)",
        'profile.external.host=localhost', "profile.external.port=$PgPort", 'profile.external.displayName=External (local)',
        'profile.internal.host=localhost', "profile.internal.port=$PgPort", 'profile.internal.displayName=Internal (local)',
        'profile.internal.bannerText=INTERNAL - LOCAL TEST', 'profile.internal.bannerTone=danger',
        "execution.enabled=$($Exec.ToString().ToLowerInvariant())",
        ('logging.dir=' + (& $fwd (Join-Path $dir 'logs'))), ('server.workDir=' + (& $fwd (Join-Path $dir 'work'))))
    if ($Exec) { $lines += 'parser.library=' + (& $fwd $dll) }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $cfg = Join-Path $dir 'pgweb.properties'
    Set-Content -Path $cfg -Encoding ASCII -Value $lines
    & $java -jar $jar.FullName check-config --config $cfg *> (Join-Path $dir 'check-config.log')
    if ($LASTEXITCODE -ne 0) { throw "check-config failed for $Name; see $dir\check-config.log" }
    return $cfg
}
$instances = @(
    @{ name = 'exec-off'; port = $OffPort; cfg = (Write-Config 'exec-off' $OffPort $false) },
    @{ name = 'exec-on'; port = $OnPort; cfg = (Write-Config 'exec-on' $OnPort $true) })
if ($fresh) {
    & $java -jar $jar.FullName migrate --config $instances[0].cfg *> (Join-Path $root 'migrate.log')
    if ($LASTEXITCODE -ne 0) { throw "migrate failed; see $root\migrate.log" }
}

foreach ($i in $instances) {
    $dir = Join-Path $root $i.name
    if (Test-OwnInstance $i.name) {
        Write-Host "pgweb $($i.name): already running on http://localhost:$($i.port)/ (pid $($pids[$i.name]))"
        continue
    }
    $proc = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$($jar.FullName)`"", 'serve', '--config', "`"$($i.cfg)`"") -PassThru -WindowStyle Hidden `
        -WorkingDirectory $dir -RedirectStandardOutput (Join-Path $dir 'app.out.log') -RedirectStandardError (Join-Path $dir 'app.err.log')
    $pids[$i.name] = $proc.Id
    $pids | ConvertTo-Json | Set-Content -Path $pidFile -Encoding ASCII
    $up = $false; $last = ''
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while (-not $up -and $sw.Elapsed.TotalSeconds -lt 180) {
        Start-Sleep -Milliseconds 500
        if ($proc.HasExited) { throw "pgweb $($i.name) exited (code $($proc.ExitCode)); see $dir\logs" }
        # curl, not Invoke-WebRequest: Windows PowerShell's web client answered 401 here while curl and Edge got 200.
        $last = curl.exe -s -o NUL -w '%{http_code}' --max-time 5 "http://127.0.0.1:$($i.port)/api/ready"
        $up = $last -eq '200'
    }
    if (-not $up) { throw "pgweb $($i.name) on port $($i.port) not ready after 180 s (last HTTP status $last); see $dir\logs" }
    Write-Host "pgweb $($i.name): ready on http://localhost:$($i.port)/ (pid $($proc.Id))"
}
Write-Host "PostgreSQL: localhost:$PgPort (SSPI as '$role'; databases demo_db and pgweb_audit; files in $root)"
