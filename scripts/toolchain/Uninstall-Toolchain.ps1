#Requires -Version 5.1
<#
.SYNOPSIS
    Removes software the project installed in tools\ and regenerates
    configuration.properties and software-list.md. Never touches anything outside tools\.

.PARAMETER Name   Packages to remove (e.g. maven).
.PARAMETER All    Remove every installed package.
.PARAMETER Purge  Also delete cached archives; with -All, delete the whole tools\ folder
                  (including the Maven repository).
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\toolchain\Uninstall-Toolchain.ps1 -All -Purge
#>
[CmdletBinding()]
param([string[]]$Name, [switch]$All, [switch]$Purge)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'Toolchain.psm1') -Force

$ctx = New-ToolchainContext
$removed = Uninstall-Toolchain -Ctx $ctx -Name $Name -All:$All -Purge:$Purge
if (@($removed).Count -eq 0) { Write-Host 'Nothing to remove.' } else { Write-Host ('Removed: ' + (@($removed) -join ', ')) }
if ($All -and $Purge) { Write-Host 'The tools folder has been deleted.' }
Write-Host 'Updated: configuration.properties, software-list.md'
