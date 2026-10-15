#Requires -Version 5.1
<#
.SYNOPSIS
    toolchain:check job: the tools on this machine match the pinned versions (plan section 7.5).
    Java, Maven and SonarQube come from configuration.properties; Node.js must satisfy frontend\package.json
    engines; Edge must be installed (E2E uses the msedge channel); PostgreSQL binaries must be major 18 (D5).
    Exit code 0 when everything matches, 1 otherwise.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))

$props = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoRoot 'configuration.properties'))) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $k, $v = $line -split '=', 2
    $props[$k.Trim()] = $v.Trim()
}
$problems = New-Object Collections.ArrayList
function Check([string]$What, [bool]$Ok, [string]$Detail) {
    $mark = if ($Ok) { 'ok  ' } else { 'FAIL' }
    Write-Host ("  {0} {1,-12} {2}" -f $mark, $What, $Detail)
    if (-not $Ok) { [void]$problems.Add($What) }
}
function FirstLine([string]$Exe, [string[]]$ArgList) {
    $out = & cmd.exe /d /c "`"$Exe`" $($ArgList -join ' ') < NUL 2>&1"
    return (@($out) -join "`n")
}

$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javaOut = FirstLine $javaExe @('-version')
Check 'java' ($javaOut -match ('"' + [regex]::Escape(($props['jdk.version'] -split '\+')[0]))) ($javaOut -split "`n")[0]

$mvnOut = FirstLine (Join-Path $env:MAVEN_HOME 'bin\mvn.cmd') @('-v')
Check 'maven' ($mvnOut -match [regex]::Escape("Apache Maven $($props['maven.version'])")) ($mvnOut -split "`n")[0]

$pkg = Get-Content -Raw (Join-Path $repoRoot 'frontend\package.json') | ConvertFrom-Json
$nodeOut = (FirstLine 'node' @('-v')).Trim()
$major = if ($nodeOut -match '^v(\d+)\.') { [int]$Matches[1] } else { -1 }
Check 'node' ($pkg.engines.node -eq '>=24 <25' -and $major -eq 24) "$nodeOut (engines $($pkg.engines.node))"

$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
Check 'edge' ([bool]$edge) $(if ($edge) { (Get-Item $edge).VersionInfo.ProductVersion } else { 'not installed' })

$pgBin = if ($env:PGWEB_PG_BIN) { $env:PGWEB_PG_BIN } elseif ($props.ContainsKey('preexisting.postgresql.path')) { $props['preexisting.postgresql.path'].Replace('/', '\') } else { '' }
$pgOut = if ($pgBin -and (Test-Path (Join-Path $pgBin 'initdb.exe'))) { FirstLine (Join-Path $pgBin 'initdb.exe') @('--version') } else { 'initdb not found' }
Check 'postgresql' ($pgOut -match 'initdb \(PostgreSQL\) 18\.') $pgOut.Trim()

if ($problems.Count -gt 0) { Write-Host "toolchain-check: mismatched: $($problems -join ', ')"; exit 1 }
Write-Host 'toolchain-check: all versions match'
exit 0
