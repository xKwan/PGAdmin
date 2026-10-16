#Requires -Version 5.1
<#
.SYNOPSIS
    Upgrades pgweb to a new release, side by side, with automatic rollback if the new release doesn't come up
    (plan section 7.6, Phase 12). See docs\operations\runbooks.md, "Upgrade".

.DESCRIPTION
    Before anything stops:
      - the bundle is verified and copied to releases\<new>;
      - the new release checks the existing configuration (check-config);
      - the audit chain is verified with the running release;
      - you confirm that the DBA has backed up the audit database (pg_dump), with -AuditBackupConfirmed.
    Then: graceful stop (all database sessions closed; open transactions roll back), schema migration with the new
    release (expand-only: the previous release keeps working against it), "current" repointed, start, smoke test,
    audit chain verified again. If any of these fails, the previous release is restarted and the script fails.
    Audit data is never deleted.

.EXAMPLE
    .\Update-Pgweb.ps1 -Bundle D:\intake\pgweb-1.1.0.zip -AuditBackupConfirmed
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Bundle,
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'pgweb'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'pgweb'),
    [string]$ServiceName = 'pgweb',
    [string]$ServiceAccount,
    [string]$JavaOptions = '-Xms256m -Xmx1g',
    [switch]$AuditBackupConfirmed,
    [switch]$NoService
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PgwebDeploy.psm1') -Force
$sw = [Diagnostics.Stopwatch]::StartNew()
if (-not $NoService) { Assert-Administrator }
if (-not $AuditBackupConfirmed) {
    throw 'Back up the audit database first (runbooks: "Upgrade", step 1: pg_dump by the DBA), then run again with -AuditBackupConfirmed.'
}
$cfg = Join-Path $DataRoot 'config\pgweb.properties'
$previous = Get-CurrentRelease -InstallRoot $InstallRoot
if (-not $previous) { throw "pgweb is not installed in $InstallRoot; use Install-Pgweb.ps1." }

# The zip is expanded into a temporary staging folder, removed at the end whatever happens (the release is
# copied to releases\<version> first). An expanded folder passed as -Bundle is used in place and never removed.
$staging = Join-Path ([IO.Path]::GetTempPath()) "pgweb-update-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
try {
$release = Get-BundleRoot -Bundle $Bundle -Staging $staging
Test-ReleaseManifest -Release $release
$version = Get-ReleaseVersion -Release $release
if ($version -eq $previous) { throw "Release $version is already the current release." }
$target = Join-Path $InstallRoot "releases\$version"
if (Test-Path $target) {
    Test-ReleaseManifest -Release $target
    Write-Step "release $version already present (verified)"
} else {
    Copy-Item -LiteralPath $release -Destination $target -Recurse
    Test-ReleaseManifest -Release $target
    Write-Step "release $version copied to $target"
}
$prevRelease = Join-Path $InstallRoot "releases\$previous"

Write-Step "pgweb check-config (new release $version)"
if ((Invoke-Pgweb -Release $target -Config $cfg -Command 'check-config' -Log (Join-Path $DataRoot 'logs\update-check-config.log')) -ne 0) {
    throw 'The new release does not accept the configuration; nothing was changed.'
}
Write-Step "pgweb verify-audit-chain (running release $previous)"
if ((Invoke-Pgweb -Release $prevRelease -Config $cfg -Command 'verify-audit-chain' -Log (Join-Path $DataRoot 'logs\update-verify-before.log')) -ne 0) {
    throw 'The audit chain does not verify; investigate before upgrading (runbooks: "Audit investigation"). Nothing was changed.'
}
if (-not $NoService) {
    $account = if ($ServiceAccount) { $ServiceAccount } else { (Get-CimInstance Win32_Service -Filter "Name='$ServiceName'").StartName }
    [void](Write-ServiceConfig -Release $target -InstallRoot $InstallRoot -DataRoot $DataRoot -ServiceName $ServiceName -Account $account -JavaOptions $JavaOptions)
}

Stop-PgwebInstance -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
try {
    Write-Step "pgweb migrate (new release $version)"
    if ((Invoke-Pgweb -Release $target -Config $cfg -Command 'migrate' -Log (Join-Path $DataRoot 'logs\update-migrate.log')) -ne 0) { throw 'migration failed' }
    Set-CurrentRelease -InstallRoot $InstallRoot -Version $version
    Start-PgwebInstance -InstallRoot $InstallRoot -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
    Wait-PgwebReady -BaseUrl (Get-BaseUrl -Config $cfg)
    if ((Invoke-Pgweb -Release $target -Config $cfg -Command 'verify-audit-chain' -Log (Join-Path $DataRoot 'logs\update-verify-after.log')) -ne 0) { throw 'audit chain does not verify after the upgrade' }
} catch {
    Write-Warning "Upgrade to $version failed ($($_.Exception.Message)); restarting $previous."
    Stop-PgwebInstance -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
    Set-CurrentRelease -InstallRoot $InstallRoot -Version $previous
    Start-PgwebInstance -InstallRoot $InstallRoot -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
    Wait-PgwebReady -BaseUrl (Get-BaseUrl -Config $cfg)
    Add-DeployHistory -DataRoot $DataRoot -Action 'update-failed-rolled-back' -Version $previous -From $version -Seconds $sw.Elapsed.TotalSeconds
    throw "Upgrade failed and was rolled back to ${previous}: $($_.Exception.Message)"
}
Add-DeployHistory -DataRoot $DataRoot -Action 'update' -Version $version -From $previous -Seconds $sw.Elapsed.TotalSeconds
Write-Host ("pgweb upgraded {0} -> {1} ({2:n0} s). Rollback: .\Rollback-Pgweb.ps1" -f $previous, $version, $sw.Elapsed.TotalSeconds)
} finally {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
}
