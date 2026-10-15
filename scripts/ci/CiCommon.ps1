# Shared helpers for the CI scripts. Dot-source it.

function Invoke-LoggedCommand {
    <#
      Runs a command line through cmd.exe with stdin closed and output to a log file. cmd keeps Windows
      PowerShell 5.1 from turning native stderr into error records; closing stdin (< NUL) stops tools such as
      npm from waiting for console input when the host has no console.
    #>
    param([string]$CommandLine, [string]$Log, [string]$WorkDir)
    Push-Location $WorkDir
    try {
        & cmd.exe /d /c "$CommandLine < NUL > `"$Log`" 2>&1"
        return $LASTEXITCODE
    } finally { Pop-Location }
}

function Set-OfflineNpmEnvironment {
    # Any registry access goes to a closed port and fails fast, so "offline" is enforced, not just requested.
    $env:npm_config_registry = 'http://127.0.0.1:9/'
    $env:npm_config_offline = 'true'
    $env:npm_config_audit = 'false'
    $env:npm_config_fund = 'false'
    $env:npm_config_update_notifier = 'false'
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
    $env:CI = 'true'
}

function Assert-SonarToken {
    if (-not $env:SONAR_TOKEN) { throw 'SONAR_TOKEN is not set (masked, protected CI variable; locally: scripts\sonar\Start-LocalSonar.ps1 prints how to create one).' }
}
