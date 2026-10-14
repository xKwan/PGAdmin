#Requires -Version 5.1
<#
.SYNOPSIS
    Installs or verifies the project's software in tools\ and regenerates
    configuration.properties and software-list.md.

.PARAMETER Name     Only these packages (default: all in toolchain\software.json).
.PARAMETER Update   Re-resolve the newest versions within each version spec and update the lock file.
.PARAMETER Offline  Never use the network; needs toolchain\software.lock.json and tools\downloads\.
.PARAMETER NoVerify Skip running the installed tools to confirm they work.
#>
[CmdletBinding()]
param([string[]]$Name, [switch]$Update, [switch]$Offline, [switch]$NoVerify)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'Toolchain.psm1') -Force

$ctx = New-ToolchainContext
$results = Install-Toolchain -Ctx $ctx -Name $Name -Update:$Update -Offline:$Offline
foreach ($r in $results) {
    $what = if ($r.Changed) { 'installed' } else { 'already installed (verified)' }
    Write-Host ("{0,-8} {1,-14} {2}" -f $r.Name, $r.Version, $what)
}

if (-not $NoVerify) {
    . (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'Use-Toolchain.ps1') -Quiet
    if ($env:JAVA_HOME) {
        $r = Invoke-NativeTool -Exe (Join-Path $env:JAVA_HOME 'bin\java.exe') -Arguments @('-version')
        if ($r.ExitCode -ne 0) { throw "java -version failed:`n$($r.Output)" }
        Write-Host "`njava -version:`n$($r.Output)"
    }
    if ($env:MAVEN_HOME) {
        $r = Invoke-NativeTool -Exe (Join-Path $env:MAVEN_HOME 'bin\mvn.cmd') -Arguments @('-v')
        if ($r.ExitCode -ne 0) { throw "mvn -v failed:`n$($r.Output)" }
        Write-Host "`nmvn -v:`n$($r.Output)"
    }
}
Write-Host "`nUpdated: configuration.properties, software-list.md, toolchain\software.lock.json"
