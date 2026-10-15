#Requires -Version 5.1
<#
.SYNOPSIS
    package job (plan section 7.5): builds the SPA, the backend JAR with the SPA inside, both CycloneDX SBOMs,
    and a release folder plus zip with a SHA-256 manifest. Offline only.

.DESCRIPTION
    Output: release\pgweb-<version>\
              pgweb.jar            backend (Main-Class pgweb.Main, Class-Path lib/*)
              lib\                 runtime dependencies
              native\              pg_query.dll when built (native\libpg_query)
              deploy\              Windows service scripts and config templates (when present)
              sbom\                pgweb-backend-sbom.json, pgweb-frontend-sbom.json
              SHA256SUMS.txt       one line per file: <sha256>  <relative path>
            release\pgweb-<version>.zip and .zip.sha256
    Tests are not run here: the package stage runs after the test stages have passed.
#>
[CmdletBinding()]
param([string]$LogDir, [string]$OutDir)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
. (Join-Path $repoRoot 'scripts\ci\CiCommon.ps1')
Set-OfflineNpmEnvironment
if (-not $LogDir) { $LogDir = Join-Path $repoRoot 'ci-logs' }
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'release' }
New-Item -ItemType Directory -Force -Path $LogDir, $OutDir | Out-Null

$frontend = Join-Path $repoRoot 'frontend'
$backend = Join-Path $repoRoot 'backend'
function Run([string]$Name, [string]$CommandLine, [string]$WorkDir) {
    $log = Join-Path $LogDir "package-$Name.log"
    $rc = Invoke-LoggedCommand -CommandLine $CommandLine -Log $log -WorkDir $WorkDir
    if ($rc -ne 0) { Get-Content $log -Tail 40 | ForEach-Object { Write-Host $_ }; throw "package/$Name failed (exit $rc). Log: $log" }
    Write-Host "  ok  package/$Name"
}

Run 'spa-build' 'npm run build' $frontend
Run 'spa-sbom' 'npm run sbom' $frontend
$settings = Join-Path $repoRoot 'offline\maven-settings.xml'
Run 'jar' "mvn -B -o -q -s `"$settings`" -Pwith-spa -DskipTests package" $backend

[xml]$pom = Get-Content -Raw (Join-Path $backend 'pom.xml')
$version = $pom.project.version
$name = "pgweb-$version"
$stage = Join-Path $OutDir $name
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'sbom') | Out-Null

$jar = Join-Path $backend "target\pgweb-backend-$version.jar"
Copy-Item $jar (Join-Path $stage 'pgweb.jar')
Copy-Item (Join-Path $backend 'target\lib') (Join-Path $stage 'lib') -Recurse
Copy-Item (Join-Path $backend 'target\pgweb-backend-sbom.json') (Join-Path $stage 'sbom')
Copy-Item (Join-Path $frontend 'dist-sbom\pgweb-frontend-sbom.json') (Join-Path $stage 'sbom')
$dll = Join-Path $repoRoot 'native\libpg_query\build\pg_query.dll'
if (Test-Path $dll) {
    New-Item -ItemType Directory -Force -Path (Join-Path $stage 'native') | Out-Null
    Copy-Item $dll (Join-Path $stage 'native')
}
$deploy = Join-Path $repoRoot 'deploy'
if (Test-Path $deploy) { Copy-Item $deploy (Join-Path $stage 'deploy') -Recurse }

# The JAR must contain the SPA.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead((Join-Path $stage 'pgweb.jar'))
try { $hasSpa = @($z.Entries | Where-Object { $_.FullName -eq 'static/index.html' }).Count -eq 1 } finally { $z.Dispose() }
if (-not $hasSpa) { throw 'pgweb.jar does not contain static/index.html (SPA missing).' }

& (Join-Path $repoRoot 'offline\Write-Sha256Manifest.ps1') -Root $stage -Manifest (Join-Path $stage 'SHA256SUMS.txt')
$zip = Join-Path $OutDir "$name.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
$h = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
Set-Content -Path "$zip.sha256" -Value "$h  $name.zip" -Encoding ASCII
Write-Host "release bundle: $zip ($h)"
