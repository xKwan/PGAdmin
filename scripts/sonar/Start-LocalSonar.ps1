#Requires -Version 5.1
<#
.SYNOPSIS
    Starts the project-local SonarQube Community Build (tools\sonarqube-*) and makes sure it is configured for
    pgweb: the "pgweb gate" quality gate (plan section 8.3) as the default, both projects, and an analysis token.

.DESCRIPTION
    - Runs with the project JDK and the embedded H2 database (evaluation mode: fine for a workstation, not for a
      shared server). Data stays in tools\sonarqube-*\data.
    - On first start it replaces the default admin password with a random one, creates a global analysis token,
      and stores both in tools\sonar-local.json (tools\ is never committed).
    - Idempotent: re-running reuses the running server and re-applies the gate definition.
    Prints the token as `SONAR_TOKEN` for the current session when dot-sourced.

.EXAMPLE
    . .\scripts\sonar\Start-LocalSonar.ps1      # start/configure, sets $env:SONAR_TOKEN and $env:SONAR_HOST_URL
#>
[CmdletBinding()]
param([int]$Port = 9000, [int]$StartTimeoutSeconds = 420)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet

$sonarHome = Get-ChildItem (Join-Path $repoRoot 'tools') -Directory -Filter 'sonarqube-*' | Select-Object -First 1
if (-not $sonarHome) { throw 'SonarQube is not installed. Run scripts\toolchain\Install-Toolchain.ps1.' }
$stateFile = Join-Path $repoRoot 'tools\sonar-local.json'
$base = "http://localhost:$Port"

function Invoke-Sonar([string]$Method, [string]$Path, [hashtable]$Form, [string]$User, [string]$Password, [switch]$AllowFail) {
    $headers = @{}
    if ($User) {
        $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${User}:${Password}"))
        $headers['Authorization'] = "Basic $pair"
    }
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri "$base$Path" -Headers $headers -Body $Form -TimeoutSec 60
        if ($r.Content) { return ($r.Content | ConvertFrom-Json) }
        return $null
    } catch {
        if ($AllowFail) { return $null }
        $detail = ''
        if ($_.Exception.Response) {
            $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $sr.ReadToEnd()
        }
        throw "SonarQube $Method $Path failed: $($_.Exception.Message) $detail"
    }
}

function Get-Status {
    try { return (Invoke-WebRequest -UseBasicParsing "$base/api/system/status" -TimeoutSec 5).Content | ConvertFrom-Json } catch { return $null }
}

$status = Get-Status
if (-not $status -or $status.status -ne 'UP') {
    if (-not $status) {
        Write-Host "Starting SonarQube $($sonarHome.Name) on port $Port ..."
        $env:SONAR_JAVA_PATH = Join-Path $env:JAVA_HOME 'bin\java.exe'
        $env:SONAR_WEB_PORT = "$Port"
        $bat = Join-Path $sonarHome.FullName 'bin\windows-x86-64\StartSonar.bat'
        $log = Join-Path $sonarHome.FullName 'logs\startsonar-console.log'
        Start-Process -FilePath $bat -WorkingDirectory (Split-Path $bat) -WindowStyle Hidden `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
    }
    $deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
    do {
        Start-Sleep -Seconds 3
        $status = Get-Status
    } while ((-not $status -or $status.status -ne 'UP') -and (Get-Date) -lt $deadline)
    if (-not $status -or $status.status -ne 'UP') { throw "SonarQube did not come up within $StartTimeoutSeconds s; see $($sonarHome.FullName)\logs" }
}
Write-Host "SonarQube $($status.version) is UP at $base"

$state = if (Test-Path $stateFile) { Get-Content -Raw $stateFile | ConvertFrom-Json } else { $null }
if (-not $state) {
    $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $rng = New-Object Security.Cryptography.RNGCryptoServiceProvider
    $bytes = New-Object byte[] 32
    $rng.GetBytes($bytes)
    $pw = 'Pg' + (-join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })) + '!9'
    Invoke-Sonar POST '/api/users/change_password' @{ login = 'admin'; previousPassword = 'admin'; password = $pw } 'admin' 'admin' | Out-Null
    $tok = Invoke-Sonar POST '/api/user_tokens/generate' @{ name = 'pgweb-local-analysis'; type = 'GLOBAL_ANALYSIS_TOKEN' } 'admin' $pw
    $state = [pscustomobject]@{ adminPassword = $pw; token = $tok.token; created = (Get-Date).ToString('o') }
    $state | ConvertTo-Json | Set-Content -Path $stateFile -Encoding UTF8
    Write-Host "Admin password changed and analysis token created; stored in tools\sonar-local.json"
}
$pw = $state.adminPassword

# Quality gate "pgweb gate" (plan 8.3). Conditions are re-created each time so the definition stays in this file.
$gateName = 'pgweb gate'
$gates = Invoke-Sonar GET '/api/qualitygates/list' $null 'admin' $pw
$existing = @($gates.qualitygates | Where-Object { $_.name -eq $gateName })
if ($existing.Count -eq 0) {
    Invoke-Sonar POST '/api/qualitygates/create' @{ name = $gateName } 'admin' $pw | Out-Null
}
$shown = Invoke-Sonar GET ('/api/qualitygates/show?name=' + [uri]::EscapeDataString($gateName)) $null 'admin' $pw
foreach ($c in @($shown.conditions)) {
    Invoke-Sonar POST '/api/qualitygates/delete_condition' @{ id = $c.id } 'admin' $pw | Out-Null
}
$conditions = @(
    # New code
    @{ metric = 'new_coverage'; op = 'LT'; error = '80' },
    @{ metric = 'new_duplicated_lines_density'; op = 'GT'; error = '3' },
    @{ metric = 'new_software_quality_blocker_issues'; op = 'GT'; error = '0' },
    @{ metric = 'new_software_quality_high_issues'; op = 'GT'; error = '0' },
    @{ metric = 'new_software_quality_reliability_rating'; op = 'GT'; error = '1' },
    @{ metric = 'new_software_quality_security_rating'; op = 'GT'; error = '1' },
    @{ metric = 'new_software_quality_maintainability_rating'; op = 'GT'; error = '1' },
    @{ metric = 'new_security_hotspots_reviewed'; op = 'LT'; error = '100' },
    # Overall code, checked at each phase gate
    @{ metric = 'software_quality_blocker_issues'; op = 'GT'; error = '0' },
    @{ metric = 'software_quality_high_issues'; op = 'GT'; error = '0' },
    @{ metric = 'software_quality_security_rating'; op = 'GT'; error = '1' },
    @{ metric = 'software_quality_reliability_rating'; op = 'GT'; error = '1' },
    @{ metric = 'security_hotspots_reviewed'; op = 'LT'; error = '100' }
)
foreach ($c in $conditions) {
    Invoke-Sonar POST '/api/qualitygates/create_condition' (@{ gateName = $gateName } + $c) 'admin' $pw | Out-Null
}
Invoke-Sonar POST '/api/qualitygates/set_as_default' @{ name = $gateName } 'admin' $pw | Out-Null

foreach ($p in @(@{ key = 'pgweb-backend'; name = 'pgweb backend' }, @{ key = 'pgweb-frontend'; name = 'pgweb frontend' })) {
    $found = Invoke-Sonar GET "/api/projects/search?projects=$($p.key)" $null 'admin' $pw
    if (@($found.components).Count -eq 0) {
        Invoke-Sonar POST '/api/projects/create' @{ project = $p.key; name = $p.name; mainBranch = 'main' } 'admin' $pw | Out-Null
    }
    Invoke-Sonar POST '/api/qualitygates/select' @{ gateName = $gateName; projectKey = $p.key } 'admin' $pw | Out-Null
}
Write-Host "Quality gate '$gateName' ($($conditions.Count) conditions) is the default; projects pgweb-backend and pgweb-frontend exist."

$env:SONAR_TOKEN = $state.token
$env:SONAR_HOST_URL = $base
Write-Host 'SONAR_TOKEN and SONAR_HOST_URL are set for this session.'
