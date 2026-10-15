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
    'lint-backend' { Step 'compile' "$mvn -q test-compile" $backend }
    'test-frontend' {
        $env:PGWEB_TEST_SEED = "$Seed"
        Step 'vitest' 'npm run test:coverage' $frontend
    }
    'test-backend' { Step 'verify' "$mvn verify -Dpgweb.test.seed=$Seed" $backend }
    'sonar-backend' {
        Assert-SonarToken
        Step 'verify' "$mvn verify -Dpgweb.test.seed=$Seed" $backend
        Step 'sonar' "$mvn sonar:sonar" $backend
    }
    'sonar-frontend' {
        Assert-SonarToken
        $env:PGWEB_TEST_SEED = "$Seed"
        Step 'vitest' 'npm run test:coverage' $frontend
        Step 'sonar' 'node sonar-project.js' $frontend
    }
    'package' {
        & (Join-Path $repoRoot 'offline\New-ReleaseBundle.ps1') -LogDir $LogDir
        if ($LASTEXITCODE -ne 0) { throw 'package failed' }
    }
    'e2e' {
        $e2e = Join-Path $repoRoot 'e2e'
        if (-not (Test-Path (Join-Path $e2e 'package.json'))) {
            Write-Host '  e2e: no end-to-end suite yet (added in Phase 11); nothing to run.'
        } else {
            Step 'npm-ci' 'npm ci --offline --no-audit --no-fund' $e2e
            Step 'playwright' 'npx playwright test' $e2e
        }
    }
}
Write-Host "job $Job passed (seed $Seed)"
