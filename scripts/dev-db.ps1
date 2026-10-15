#Requires -Version 5.1
<#
.SYNOPSIS
    Local development database (plan 7.2): a throwaway PostgreSQL 18 cluster in .dev-db\ (git-ignored) with the
    audit database bootstrapped (deploy\sql\audit-bootstrap.sql) and migrated, plus a ready-to-use
    .dev-db\pgweb.properties in test mode. Never points at a shared or production server.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-db.ps1 start     # create (first time) and start
    powershell -ExecutionPolicy Bypass -File scripts\dev-db.ps1 status
    powershell -ExecutionPolicy Bypass -File scripts\dev-db.ps1 stop
    powershell -ExecutionPolicy Bypass -File scripts\dev-db.ps1 destroy   # stop and delete .dev-db\
    Then: java -jar backend\target\pgweb-backend-0.1.0-SNAPSHOT.jar serve --config .dev-db\pgweb.properties
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][ValidateSet('start', 'stop', 'status', 'destroy')][string]$Action = 'start',
    [int]$Port = 55433,
    [string]$PgBin
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
$devRoot = Join-Path $repoRoot '.dev-db'
$data = Join-Path $devRoot 'data'
$stateFile = Join-Path $devRoot 'state.json'
$configFile = Join-Path $devRoot 'pgweb.properties'

if (-not $PgBin) {
    $props = Get-Content (Join-Path $repoRoot 'configuration.properties') | Where-Object { $_ -match '^preexisting\.postgresql\.path=' }
    $PgBin = if ($env:PGWEB_PG_BIN) { $env:PGWEB_PG_BIN } elseif ($props) { ($props -split '=', 2)[1].Replace('/', '\') } else { "$env:ProgramFiles\PostgreSQL\18\bin" }
}
foreach ($exe in 'initdb', 'pg_ctl', 'psql') {
    if (-not (Test-Path (Join-Path $PgBin "$exe.exe"))) { throw "$exe.exe not found in $PgBin (set -PgBin or PGWEB_PG_BIN)" }
}
function PgCtl([string[]]$PgArgs) {
    # The server that `pg_ctl start` launches inherits pg_ctl's handles (S5 finding): output goes to files, and only
    # the pg_ctl process itself is waited for (`Start-Process -Wait` or a pipe would wait for the server too).
    $log = Join-Path $devRoot 'pg_ctl.out'
    $p = Start-Process -FilePath (Join-Path $PgBin 'pg_ctl.exe') -ArgumentList $PgArgs -NoNewWindow -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $null = $p.Handle   # keep the handle so ExitCode is available after exit
    $p.WaitForExit()
    return $p.ExitCode
}
function New-Secret {
    $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $b = New-Object byte[] 24
    (New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($b)
    'Dv' + (-join ($b | ForEach-Object { $chars[$_ % $chars.Length] }))
}
function Test-Running { (PgCtl @('status', '-D', "`"$data`"")) -eq 0 }

switch ($Action) {
    'status' {
        if (-not (Test-Path $stateFile)) { Write-Host 'dev-db: not created'; return }
        $s = Get-Content -Raw $stateFile | ConvertFrom-Json
        Write-Host ("dev-db: {0} on port {1}; config {2}" -f $(if (Test-Running) { 'running' } else { 'stopped' }), $s.port, $configFile)
    }
    'stop' {
        if ((Test-Path $data) -and (Test-Running)) { [void](PgCtl @('stop', '-D', "`"$data`"", '-m', 'fast', '-w')) }
        Write-Host 'dev-db: stopped'
    }
    'destroy' {
        if ((Test-Path $data) -and (Test-Running)) { [void](PgCtl @('stop', '-D', "`"$data`"", '-m', 'fast', '-w')) }
        if (Test-Path $devRoot) { Remove-Item -Recurse -Force $devRoot }
        Write-Host 'dev-db: deleted'
    }
    'start' {
        $fresh = -not (Test-Path $stateFile)
        if ($fresh) {
            New-Item -ItemType Directory -Force -Path $devRoot | Out-Null
            $state = [pscustomobject]@{ port = $Port; super = (New-Secret); migrator = (New-Secret); app = (New-Secret); hmac = (New-Secret) + (New-Secret) }
            $pw = Join-Path $devRoot 'pw.txt'
            [IO.File]::WriteAllText($pw, $state.super)
            & (Join-Path $PgBin 'initdb.exe') -D $data -U pgweb_dev_super -A scram-sha-256 "--pwfile=$pw" -E UTF8 --no-locale *> (Join-Path $devRoot 'initdb.log')
            Remove-Item $pw
            if ($LASTEXITCODE -ne 0) { throw "initdb failed; see $devRoot\initdb.log" }
            Set-Content -Path (Join-Path $data 'pg_hba.conf') -Encoding ASCII -Value @(
                'host all all 127.0.0.1/32 scram-sha-256', 'host all all ::1/128 scram-sha-256')
            $state | ConvertTo-Json | Set-Content -Path $stateFile -Encoding UTF8
        }
        $state = Get-Content -Raw $stateFile | ConvertFrom-Json
        if (-not (Test-Running)) {
            $rc = PgCtl @('start', '-D', "`"$data`"", '-l', "`"$(Join-Path $devRoot 'server.log')`"", '-w', '-o', "`"-p $($state.port) -c listen_addresses=localhost`"")
            if ($rc -ne 0) { throw "pg_ctl start failed; see $devRoot\server.log" }
        }
        if ($fresh) {
            $env:PGPASSWORD = $state.super
            try {
                & (Join-Path $PgBin 'psql.exe') -X -q -h localhost -p $state.port -U pgweb_dev_super -d postgres `
                    -v "migrator_password=$($state.migrator)" -v "app_password=$($state.app)" -f (Join-Path $repoRoot 'deploy\sql\audit-bootstrap.sql') *> (Join-Path $devRoot 'bootstrap.log')
                if ($LASTEXITCODE -ne 0) { throw "audit bootstrap failed; see $devRoot\bootstrap.log" }
            } finally { Remove-Item Env:\PGPASSWORD }
            @(
                '# Generated by scripts\dev-db.ps1 (development only: test mode, plain secrets, local cluster).',
                'pgweb.mode=test',
                'server.port=8080',
                'server.publicHost=localhost',
                'auth.protocols=Negotiate NTLM',
                'auth.kerberosOnly=false',
                'auth.dataAdminSid=S-1-5-32-545',
                "audit.jdbcUrl=jdbc:postgresql://localhost:$($state.port)/pgweb_audit",
                'audit.user=pgweb_app',
                "audit.password=plain:$($state.app)",
                "audit.hmacKey=plain:$($state.hmac)",
                ('audit.spoolDir=' + (Join-Path $devRoot 'spool').Replace('\', '/')),
                'audit.migrator.user=pgweb_migrator',
                "audit.migrator.password=plain:$($state.migrator)",
                'profile.external.host=localhost',
                "profile.external.port=$($state.port)",
                ('logging.dir=' + (Join-Path $devRoot 'logs').Replace('\', '/')),
                ('server.workDir=' + (Join-Path $devRoot 'work').Replace('\', '/'))
            ) | Set-Content -Path $configFile -Encoding ASCII
            $jar = Get-ChildItem (Join-Path $repoRoot 'backend\target') -Filter 'pgweb-backend-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($jar) {
                & (Join-Path $env:JAVA_HOME 'bin\java.exe') -jar $jar.FullName migrate --config $configFile
                if ($LASTEXITCODE -ne 0) { throw 'audit migration failed' }
            } else {
                Write-Host 'Build the backend (mvn package), then run: java -jar backend\target\pgweb-backend-*.jar migrate --config .dev-db\pgweb.properties'
            }
        }
        Write-Host "dev-db: running on localhost:$($state.port); config $configFile"
    }
}
