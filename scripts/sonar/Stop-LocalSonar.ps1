#Requires -Version 5.1
<#
.SYNOPSIS
    Stops the project-local SonarQube started by Start-LocalSonar.ps1 (only Java processes running from
    tools\sonarqube-*; nothing else is touched).
#>
[CmdletBinding()]
param()
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
$sonarHome = Get-ChildItem (Join-Path $repoRoot 'tools') -Directory -Filter 'sonarqube-*' | Select-Object -First 1
if (-not $sonarHome) { Write-Host 'SonarQube is not installed.'; return }
$procs = @(Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($sonarHome.FullName) })
foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Host "Stopped $($procs.Count) SonarQube process(es)."
