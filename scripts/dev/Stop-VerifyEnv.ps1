<#
.SYNOPSIS
    Stops the local manual-verification environment started by Start-VerifyEnv.ps1.
.DESCRIPTION
    pgweb is stopped with Stop-PgwebProcess (a console Ctrl+C, as the service wrapper does), so its shutdown hook
    closes every database session; it is killed only after 30 s. Then the PostgreSQL cluster is stopped.
    -Destroy also deletes .verify-env\ (cluster, audit records, logs).
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev\Stop-VerifyEnv.ps1 -Destroy
#>
[CmdletBinding()]
param([switch]$Destroy)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$root = Join-Path $repoRoot '.verify-env'
if (-not (Test-Path $root)) { Write-Host 'verify-env: not created'; return }

$pidFile = Join-Path $root 'pids.json'
if (Test-Path $pidFile) {
    Import-Module (Join-Path $repoRoot 'deploy\windows\PgwebDeploy.psm1') -Force
    foreach ($p in (Get-Content -Raw $pidFile | ConvertFrom-Json).PSObject.Properties) {
        Stop-PgwebProcess -ProcessId ([int]$p.Value)
        Write-Host "pgweb $($p.Name) stopped"
    }
    Remove-Item $pidFile
}

$line = Get-Content (Join-Path $repoRoot 'configuration.properties') | Where-Object { $_ -match '^preexisting\.postgresql\.path=' } | Select-Object -First 1
$pgBin = @($env:PGWEB_PG_BIN, $(if ($line) { ($line -split '=', 2)[1].Replace('/', '\') }), "$env:ProgramFiles\PostgreSQL\18\bin") |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'pg_ctl.exe')) } | Select-Object -First 1
$data = Join-Path $root 'data'
if ((Test-Path $data) -and $pgBin) {
    $p = Start-Process -FilePath (Join-Path $pgBin 'pg_ctl.exe') -ArgumentList @('stop', '-D', "`"$data`"", '-m', 'fast', '-w') -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $root 'pg_ctl.out') -RedirectStandardError (Join-Path $root 'pg_ctl.err')
    $null = $p.Handle; $p.WaitForExit()
    Write-Host 'PostgreSQL stopped'
}
if ($Destroy) {
    Remove-Item -Recurse -Force $root
    Write-Host 'verify-env: deleted'
}
