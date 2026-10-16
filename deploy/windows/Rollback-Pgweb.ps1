#Requires -Version 5.1
<#
.SYNOPSIS
    Returns pgweb to the previous release (or -Version): stop, repoint "current", start, smoke test, verify the audit
    chain (plan section 7.6). Audit data is kept: migrations are expand-only, so the older release works against the
    newer schema. Target time: under 15 minutes (docs\operations\runbooks.md, "Rollback").

.EXAMPLE
    .\Rollback-Pgweb.ps1
    .\Rollback-Pgweb.ps1 -Version 1.0.0
#>
[CmdletBinding()]
param(
    [string]$Version,
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'pgweb'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'pgweb'),
    [string]$ServiceName = 'pgweb',
    [switch]$NoService
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PgwebDeploy.psm1') -Force
$sw = [Diagnostics.Stopwatch]::StartNew()
if (-not $NoService) { Assert-Administrator }
$cfg = Join-Path $DataRoot 'config\pgweb.properties'
$current = Get-CurrentRelease -InstallRoot $InstallRoot
if (-not $current) { throw "pgweb is not installed in $InstallRoot." }
if (-not $Version) {
    # The release that was current before the current one (from the deployment history).
    $history = @(Get-DeployHistory -DataRoot $DataRoot | Where-Object { $_.action -in 'install', 'update', 'rollback' })
    $earlier = @($history | ForEach-Object { $_.version } | Where-Object { $_ -ne $current })
    if ($earlier.Count -eq 0) { throw 'No earlier release in the deployment history; pass -Version.' }
    $Version = $earlier[-1]
}
if ($Version -eq $current) { throw "$Version is already the current release." }
$target = Join-Path $InstallRoot "releases\$Version"
if (-not (Test-Path $target)) { throw "Release $Version is not installed ($target)." }
Test-ReleaseManifest -Release $target
Write-Host "Rolling back pgweb $current -> $Version"

Stop-PgwebInstance -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
Set-CurrentRelease -InstallRoot $InstallRoot -Version $Version
Start-PgwebInstance -InstallRoot $InstallRoot -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
Wait-PgwebReady -BaseUrl (Get-BaseUrl -Config $cfg)
if ((Invoke-Pgweb -Release $target -Config $cfg -Command 'verify-audit-chain' -Log (Join-Path $DataRoot 'logs\rollback-verify.log')) -ne 0) {
    throw "Rolled back to $Version, but the audit chain does not verify: investigate (runbooks: 'Audit investigation')."
}
Add-DeployHistory -DataRoot $DataRoot -Action 'rollback' -Version $Version -From $current -Seconds $sw.Elapsed.TotalSeconds
Write-Host ("pgweb rolled back {0} -> {1} in {2:n0} s." -f $current, $Version, $sw.Elapsed.TotalSeconds)
