#Requires -Version 5.1
<#
.SYNOPSIS
    Runs one CI job. .gitlab-ci.yml calls this script for every job, and Invoke-LocalPipeline.ps1 runs the same
    jobs on a workstation, so the pipeline logic lives in one place (plan section 7.5).

.DESCRIPTION
    Every job works offline: Maven runs with -o and offline\maven-settings.xml (mirror pointed at an unreachable
    address); npm runs `ci --offline` with the registry pointed at an unreachable address. A job that would need
    the network fails instead of fetching.

    Jobs:
      deps-verify       clean offline install of Maven and npm dependencies
      toolchain-check   Java, Maven, Node, Edge and PostgreSQL versions match docs\compatibility-matrix.md pins
      lint-frontend     ESLint, 0 warnings
      lint-backend      Enforcer + compiler -Werror (validate + compile)
      test-frontend     Vitest with coverage thresholds
      test-backend      Surefire + Failsafe + JaCoCo thresholds (mvn verify)
      sonar-backend     SonarQube analysis of the backend, waits for the quality gate
      sonar-frontend    SonarQube analysis of the frontend, waits for the quality gate
      package           SPA build, backend JAR, SBOMs and SHA-256 manifest (offline\New-ReleaseBundle.ps1)
      e2e               Playwright against the packaged app (from Phase 11)

.PARAMETER Seed
    Seed for randomised tests (0 = random).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('deps-verify', 'toolchain-check', 'lint-frontend', 'lint-backend', 'test-frontend', 'test-backend',
        'sonar-backend', 'sonar-frontend', 'package', 'e2e')]
    [string]$Job,
    [int]$Seed = 0,
    [string]$LogDir
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
. (Join-Path $repoRoot 'scripts\toolchain\Use-Toolchain.ps1') -Quiet
. (Join-Path $repoRoot 'scripts\ci\CiCommon.ps1')

if (-not $LogDir) { $LogDir = Join-Path $repoRoot 'ci-logs' }
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum ([int]::MaxValue) }

$backend = Join-Path $repoRoot 'backend'
$frontend = Join-Path $repoRoot 'frontend'
$mvn = "mvn -B -o -s `"$(Join-Path $repoRoot 'offline\maven-settings.xml')`""
Set-OfflineNpmEnvironment

function Step([string]$Name, [string]$CommandLine, [string]$WorkDir) {
    $log = Join-Path $LogDir "$Job-$Name.log"
    $rc = Invoke-LoggedCommand -CommandLine $CommandLine -Log $log -WorkDir $WorkDir
    if ($rc -ne 0) {
        Write-Host "---- last lines of $log ----"
        Get-Content $log -Tail 40 | ForEach-Object { Write-Host $_ }
        throw "$Job/$Name failed (exit $rc). Log: $log"
    }
    Write-Host "  ok  $Job/$Name"
}

switch ($Job) {
    'deps-verify' {
        Step 'npm-ci' 'npm ci --offline --no-audit --no-fund' $frontend
        # Resolve every dependency, then build with every bound plugin (tests compiled, not run: the test jobs run them).
        Step 'maven' "$mvn -q dependency:resolve package -DskipTests" $backend
    }
    'toolchain-check' {
        & (Join-Path $repoRoot 'scripts\ci\Test-ToolchainVersions.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'toolchain-check failed' }
    }
    'lint-frontend' { Step 'eslint' 'npm run lint' $frontend }
    'lint-backend' {
        Step 'text' "powershell -NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $repoRoot 'scripts\ci\Test-TextFiles.ps1')`"" $repoRoot
        Step 'compile' "$mvn -q test-compile" $backend
    }
    'test-frontend' {
        $env:PGWEB_TEST_SEED = "$Seed"
        Step 'vitest' 'npm run test:coverage' $frontend
    }
    'test-backend' {
        Assert-SqlParser -RepoRoot $repoRoot -Log (Join-Path $LogDir "$Job-parser.log")
        Step 'verify' "$mvn verify -Dpgweb.test.seed=$Seed" $backend
    }
    'sonar-backend' {
        Assert-SonarToken
        Assert-SqlParser -RepoRoot $repoRoot -Log (Join-Path $LogDir "$Job-parser.log")
        Step 'verify' "$mvn verify -Dpgweb.test.seed=$Seed" $backend
        Step 'sonar' "$mvn sonar:sonar" $backend
        Set-Content -Path (Join-Path $LogDir 'sonar-pgweb-backend.sources') -Value (Get-SourceHash -RepoRoot $repoRoot -Dir 'backend/src') -Encoding ASCII
    }
    'sonar-frontend' {
        Assert-SonarToken
        $env:PGWEB_TEST_SEED = "$Seed"
        Step 'vitest' 'npm run test:coverage' $frontend
        # SonarScanner for Maven (pinned for the backend), not the npm scanner: no extra npm dependencies (node-forge).
        Step 'sonar' "$mvn -f sonar-pom.xml sonar:sonar" $frontend
        Set-Content -Path (Join-Path $LogDir 'sonar-pgweb-frontend.sources') -Value (Get-SourceHash -RepoRoot $repoRoot -Dir 'frontend/src') -Encoding ASCII
    }
    'package' {
        Assert-SqlParser -RepoRoot $repoRoot -Log (Join-Path $LogDir "$Job-parser.log")
        & (Join-Path $repoRoot 'offline\New-ReleaseBundle.ps1') -LogDir $LogDir
        if ($LASTEXITCODE -ne 0) { throw 'package failed' }
    }
    'e2e' {
        # Real environment (throwaway PostgreSQL with SSPI for the runner's account, packaged JAR, installed Edge).
        Step 'playwright' "powershell -NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $repoRoot 'scripts\test\Invoke-E2E.ps1')`" -Tag ci -Seed $Seed" $repoRoot
    }
}
Write-Host "job $Job passed (seed $Seed)"
