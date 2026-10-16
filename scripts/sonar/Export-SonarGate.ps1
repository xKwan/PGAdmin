#Requires -Version 5.1
<#
.SYNOPSIS
    Records the SonarQube quality-gate status and key measures of both projects as phase evidence
    (docs\evidence\<Tag>\sonar-<timestamp>.json). Needs SONAR_HOST_URL/SONAR_TOKEN (Start-LocalSonar.ps1 sets them)
    or the local admin credentials in tools\sonar-local.json. Exit 0 only if both gates are OK.
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Tag)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
. (Join-Path $repoRoot 'scripts\ci\CiCommon.ps1')
$base = if ($env:SONAR_HOST_URL) { $env:SONAR_HOST_URL } else { 'http://localhost:9000' }
$state = Get-Content -Raw (Join-Path $repoRoot 'tools\sonar-local.json') | ConvertFrom-Json
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$($state.adminPassword)"))
function Get-Api([string]$Path) { (Invoke-WebRequest -UseBasicParsing -Uri "$base$Path" -Headers @{ Authorization = $auth } -TimeoutSec 30).Content | ConvertFrom-Json }

$metrics = 'coverage,line_coverage,branch_coverage,ncloc,duplicated_lines_density,software_quality_blocker_issues,software_quality_high_issues,software_quality_security_rating,software_quality_reliability_rating,software_quality_maintainability_rating,security_hotspots,security_hotspots_reviewed,vulnerabilities,bugs'
$projects = @()
foreach ($key in 'pgweb-backend', 'pgweb-frontend') {
    $gate = Get-Api "/api/qualitygates/project_status?projectKey=$key"
    $m = Get-Api "/api/measures/component?component=$key&metricKeys=$metrics"
    $measures = [ordered]@{}
    foreach ($x in @($m.component.measures)) { $measures[$x.metric] = $x.value }
    # The gate status is that of the project's LAST analysis. Refuse to export it unless the sonar job recorded that
    # it analysed exactly the sources in the working tree now (finding P6-4: a scan that silently failed to upload
    # would otherwise look like a pass).
    $last = @((Get-Api "/api/project_analyses/search?project=$key&ps=1").analyses)[0]
    $srcDir = if ($key -eq 'pgweb-backend') { 'backend/src' } else { 'frontend/src' }
    $recordFile = Join-Path $repoRoot "ci-logs\sonar-$key.sources"
    $recorded = if (Test-Path $recordFile) { (Get-Content $recordFile -TotalCount 1).Trim() } else { '' }
    $current = Get-SourceHash -RepoRoot $repoRoot -Dir $srcDir
    if ($recorded -ne $current) {
        throw "${key}: the last recorded analysis does not cover the current $srcDir (sources changed, or the sonar job did not finish). Run the sonar job first."
    }
    $projects += [pscustomobject]@{
        project = $key; gate = $gate.projectStatus.status; analysedAt = $last.date; analysedRevision = $last.revision
        failedConditions = @($gate.projectStatus.conditions | Where-Object { $_.status -ne 'OK' } | ForEach-Object { "$($_.metricKey) $($_.actualValue)" })
        measures = $measures
    }
}
$server = (Invoke-WebRequest -UseBasicParsing -Uri "$base/api/server/version" -TimeoutSec 30).Content.Trim()   # plain text, not JSON
$dir = Join-Path $repoRoot "docs\evidence\$Tag"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$ok = @($projects | Where-Object { $_.gate -ne 'OK' }).Count -eq 0
$out = Join-Path $dir ('sonar-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
[pscustomobject]@{
    tag = $Tag; when = (Get-Date).ToString('o'); server = "SonarQube Community Build $server"; gate = 'pgweb gate'
    commit = ((& git -C $repoRoot rev-parse --short HEAD) -join ''); overall = $(if ($ok) { 'OK' } else { 'ERROR' }); projects = $projects
} | ConvertTo-Json -Depth 6 | Set-Content -Path $out -Encoding UTF8
foreach ($p in $projects) { Write-Host ("  {0,-15} gate={1}  coverage={2}%  high/blocker issues={3}/{4}" -f $p.project, $p.gate, $p.measures['coverage'], $p.measures['software_quality_high_issues'], $p.measures['software_quality_blocker_issues']) }
Write-Host "Sonar evidence: $out"
if ($ok) { exit 0 } else { exit 1 }
