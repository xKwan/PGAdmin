#Requires -Version 5.1
<#
.SYNOPSIS
    Stops pgweb and removes the Windows service and the "current" junction. Releases, configuration, logs and the
    audit spool are kept unless asked otherwise; the audit database is never touched.

.EXAMPLE
    .\Uninstall-Pgweb.ps1
    .\Uninstall-Pgweb.ps1 -RemoveReleases
#>
[CmdletBinding()]
param(
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'pgweb'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'pgweb'),
    [string]$ServiceName = 'pgweb',
    [switch]$RemoveReleases,
    [switch]$RemoveData,
    [switch]$NoService
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PgwebDeploy.psm1') -Force
if (-not $NoService) { Assert-Administrator }
Stop-PgwebInstance -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
$exe = Join-Path $InstallRoot 'current\service\pgweb-service.exe'
if (-not $NoService -and (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) {
    $out = & $exe uninstall 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Removing the service failed: $out" }
    Write-Step "service $ServiceName removed"
}
$current = Join-Path $InstallRoot 'current'
if (Test-Path -LiteralPath $current) { [IO.Directory]::Delete($current); Write-Step 'current junction removed' }
if ($RemoveReleases -and (Test-Path (Join-Path $InstallRoot 'releases'))) {
    Remove-Item -LiteralPath (Join-Path $InstallRoot 'releases') -Recurse -Force
    Write-Step 'releases removed'
}
if ($RemoveData) {
    $spool = @(Get-ChildItem -LiteralPath (Join-Path $DataRoot 'spool') -File -ErrorAction SilentlyContinue)
    if ($spool.Count -gt 0) { throw "The audit spool holds $($spool.Count) file(s) not yet written to the audit database; replay them first (runbooks: 'Spool recovery')." }
    Remove-Item -LiteralPath $DataRoot -Recurse -Force
    Write-Step "data folder $DataRoot removed"
}
Write-Host 'pgweb uninstalled.'
