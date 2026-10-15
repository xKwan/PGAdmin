#Requires -Version 5.1
<#
.SYNOPSIS
    Dot-source to use the project's tools in the CURRENT PowerShell session only:
        . .\scripts\toolchain\Use-Toolchain.ps1
    Sets JAVA_HOME, MAVEN_HOME, MAVEN_ARGS (local Maven repository inside tools\), the dependency
    cache variables listed as cache.<name>.env (e.g. npm_config_cache) and prepends the bin folders to
    PATH. Nothing is changed permanently.
#>
[CmdletBinding()]
param([switch]$Quiet)

$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
$propsPath = Join-Path $repoRoot 'configuration.properties'
if (-not (Test-Path $propsPath)) { throw "configuration.properties not found. Run scripts\toolchain\Install-Toolchain.ps1 first." }

$props = @{}
foreach ($line in [IO.File]::ReadAllLines($propsPath)) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $k, $v = $line -split '=', 2
    $props[$k.Trim()] = $v.Trim()
}

function Resolve-ProjectPath([string]$Value) {
    $native = $Value.Replace('/', '\')
    if ([IO.Path]::IsPathRooted($native)) { return $native }
    return (Join-Path $repoRoot $native)
}

$prepend = @()
if ($props.ContainsKey('jdk.home')) {
    $env:JAVA_HOME = Resolve-ProjectPath $props['jdk.home']
    $prepend += (Join-Path $env:JAVA_HOME 'bin')
}
if ($props.ContainsKey('maven.home')) {
    $env:MAVEN_HOME = Resolve-ProjectPath $props['maven.home']
    $prepend += (Join-Path $env:MAVEN_HOME 'bin')
    if ($props.ContainsKey('maven.repo.local')) {
        $env:MAVEN_ARGS = '-Dmaven.repo.local=' + (Resolve-ProjectPath $props['maven.repo.local'])
    }
}
# Dependency caches: cache.<name>=<path> and cache.<name>.env=<variable>
foreach ($key in @($props.Keys | Where-Object { $_ -match '^cache\.[^.]+$' })) {
    $envKey = "$key.env"
    if ($props.ContainsKey($envKey) -and $props[$envKey]) {
        [Environment]::SetEnvironmentVariable($props[$envKey], (Resolve-ProjectPath $props[$key]), 'Process')
    }
}
if ($props.ContainsKey('cache.playwright-browsers')) { $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1' }

if ($prepend.Count -gt 0) {
    $rest = @($env:PATH -split ';' | Where-Object { $_ -and ($prepend -notcontains $_) })
    $env:PATH = (@($prepend) + $rest) -join ';'
}
if (-not $Quiet) {
    Write-Host "JAVA_HOME  = $env:JAVA_HOME"
    Write-Host "MAVEN_HOME = $env:MAVEN_HOME"
    Write-Host "MAVEN_ARGS = $env:MAVEN_ARGS"
    foreach ($key in @($props.Keys | Where-Object { $_ -match '^cache\.[^.]+\.env$' } | Sort-Object)) {
        Write-Host ("{0,-10} = {1}" -f $props[$key], [Environment]::GetEnvironmentVariable($props[$key], 'Process'))
    }
}
