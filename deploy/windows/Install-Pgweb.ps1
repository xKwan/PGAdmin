#Requires -Version 5.1
<#
.SYNOPSIS
    First installation of pgweb from a release bundle (plan Phase 12). See docs\operations\deployment-guide.md.

.DESCRIPTION
    1. Verifies the bundle (zip checksum, then every file against SHA256SUMS.txt).
    2. Copies the release to <InstallRoot>\releases\<version>.
    3. Creates <DataRoot>\{config,logs,spool,work,backup} with an ACL for SYSTEM, Administrators and the service account.
    4. Puts the prepared configuration in place (never overwrites one unless -ReplaceConfig).
    5. Runs check-config, migrate (audit schema, expand-only) and verify-audit-chain with the new release.
    6. Points <InstallRoot>\current at the release, registers the Windows service (WinSW) and starts it.
    7. Smoke test: /api/health and /api/ready answer 200.
    Every step stops the installation on failure. Nothing touches the audit data except the schema migration.

.PARAMETER Bundle
    The release zip (pgweb-<version>.zip, with its .sha256 next to it) or an expanded release folder.
.PARAMETER Config
    A prepared pgweb.properties (from deploy\windows\pgweb.properties.template; secrets as dpapi:/file:/env:).
.PARAMETER ServiceAccount
    The account the service runs as: a gMSA (DOMAIN\name$) or domain account with the HTTP/<host> SPN and constrained
    delegation to the PostgreSQL SPNs (ADR-0002).
.PARAMETER NoService
    Rehearsal and test only: run pgweb as a process of the calling user instead of a service (no elevation needed).

.EXAMPLE
    .\Install-Pgweb.ps1 -Bundle D:\intake\pgweb-1.0.0.zip -Config D:\intake\pgweb.properties -ServiceAccount 'CORP\svc-pgweb$'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Bundle,
    [string]$Config,
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'pgweb'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'pgweb'),
    [string]$ServiceName = 'pgweb',
    [string]$ServiceAccount,
    [ValidateSet('test', 'prod')][string]$Environment = 'prod',
    [string]$JavaOptions = '-Xms256m -Xmx1g',
    [switch]$ReplaceConfig,
    [switch]$NoService
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PgwebDeploy.psm1') -Force
$sw = [Diagnostics.Stopwatch]::StartNew()
if (-not $NoService) {
    Assert-Administrator
    if (-not $ServiceAccount) { throw '-ServiceAccount is required (the gMSA or domain account with the HTTP SPN and delegation rights).' }
}
$account = if ($NoService) { "$env:USERDOMAIN\$env:USERNAME" } else { $ServiceAccount }

Write-Host "Installing pgweb into $InstallRoot (data: $DataRoot)"
# The zip is expanded into a temporary staging folder, removed at the end whatever happens (the release is
# copied to releases\<version> first). An expanded folder passed as -Bundle is used in place and never removed.
$staging = Join-Path ([IO.Path]::GetTempPath()) "pgweb-install-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
try {
$release = Get-BundleRoot -Bundle $Bundle -Staging $staging
Test-ReleaseManifest -Release $release
$version = Get-ReleaseVersion -Release $release
if (Get-CurrentRelease -InstallRoot $InstallRoot) { throw "pgweb is already installed in $InstallRoot; use Update-Pgweb.ps1." }
$target = Join-Path $InstallRoot "releases\$version"
if (Test-Path $target) { throw "$target exists already." }
New-Item -ItemType Directory -Force -Path (Join-Path $InstallRoot 'releases') | Out-Null
Copy-Item -LiteralPath $release -Destination $target -Recurse
Test-ReleaseManifest -Release $target
Write-Step "release $version copied to $target"

Initialize-DataRoot -DataRoot $DataRoot -Account $account
$cfg = Join-Path $DataRoot 'config\pgweb.properties'
if ($Config) {
    if ((Test-Path $cfg) -and -not $ReplaceConfig) { throw "$cfg exists; pass -ReplaceConfig to overwrite it." }
    Copy-Item -LiteralPath $Config -Destination $cfg -Force
    Write-Step "configuration placed in $cfg"
} elseif (-not (Test-Path $cfg)) {
    throw "No configuration: pass -Config (prepared from deploy\windows\pgweb.properties.template)."
}
$mode = Get-ConfigValue -Config $cfg -Key 'pgweb.mode'
if ($Environment -eq 'prod' -and $mode -ne 'prod') { throw "-Environment prod needs pgweb.mode=prod in $cfg (it is '$mode')." }

foreach ($cmd in 'check-config', 'migrate', 'verify-audit-chain') {
    Write-Step "pgweb $cmd"
    $rc = Invoke-Pgweb -Release $target -Config $cfg -Command $cmd -Log (Join-Path $DataRoot "logs\install-$cmd.log")
    if ($rc -ne 0) { throw "pgweb $cmd failed (exit $rc); see $DataRoot\logs\install-$cmd.log. Nothing was started." }
}

Set-CurrentRelease -InstallRoot $InstallRoot -Version $version
if (-not $NoService) {
    $xml = Write-ServiceConfig -Release $target -InstallRoot $InstallRoot -DataRoot $DataRoot -ServiceName $ServiceName -Account $ServiceAccount -JavaOptions $JavaOptions
    $exe = Join-Path $InstallRoot 'current\service\pgweb-service.exe'
    $out = & $exe install 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Registering the service failed: $out" }
    Write-Step "service $ServiceName registered ($xml)"
}
Start-PgwebInstance -InstallRoot $InstallRoot -DataRoot $DataRoot -ServiceName $ServiceName -NoService:$NoService
Wait-PgwebReady -BaseUrl (Get-BaseUrl -Config $cfg)
Add-DeployHistory -DataRoot $DataRoot -Action 'install' -Version $version -Seconds $sw.Elapsed.TotalSeconds
Write-Host ("pgweb {0} installed and running ({1:n0} s)." -f $version, $sw.Elapsed.TotalSeconds)
} finally {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
}
