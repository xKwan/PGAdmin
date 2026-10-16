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

function Assert-SqlParser {
    <#
      Makes sure native\libpg_query\build\pg_query.dll exists and is the expected, reproducible build
      (native\libpg_query\pg_query.dll.sha256). Builds it from the pinned sources when it is missing; a DLL with
      another hash is never used.
    #>
    param([string]$RepoRoot, [string]$Log)
    $dir = Join-Path $RepoRoot 'native\libpg_query'
    $dll = Join-Path $dir 'build\pg_query.dll'
    $expected = ((Get-Content (Join-Path $dir 'pg_query.dll.sha256') -TotalCount 1) -split '\s+')[0].ToUpperInvariant()
    if (-not (Test-Path $dll)) {
        Write-Host '  building the SQL parser (pg_query.dll) from pinned sources; this takes several minutes'
        $rc = Invoke-LoggedCommand -CommandLine "powershell -NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $dir 'Build-PgQuery.ps1')`"" -Log $Log -WorkDir $RepoRoot
        if ($rc -ne 0) { throw "building pg_query.dll failed (exit $rc). Log: $Log" }
    }
    $actual = (Get-FileHash $dll -Algorithm SHA256).Hash
    if ($actual -ne $expected) { throw "pg_query.dll has SHA256 $actual, expected $expected. Delete native\libpg_query\build and rebuild." }
}

function Get-SourceHash {
    <#
      SHA256 over the files under $Dir (git-tracked or new, not ignored): relative path and content of each, in
      order. Sonar jobs record it next to their analysis; Export-SonarGate.ps1 refuses to export a gate whose
      analysed sources differ from the working tree (finding P6-4).
    #>
    param([string]$RepoRoot, [string]$Dir)
    $files = @(& git -C $RepoRoot ls-files --cached --others --exclude-standard -- $Dir | Sort-Object)
    $sha = [Security.Cryptography.SHA256]::Create()
    $ms = New-Object IO.MemoryStream
    foreach ($rel in $files) {
        $path = Join-Path $RepoRoot $rel
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        $name = [Text.Encoding]::UTF8.GetBytes($rel + "`n")
        $ms.Write($name, 0, $name.Length)
        $h = $sha.ComputeHash([IO.File]::ReadAllBytes($path))
        $ms.Write($h, 0, $h.Length)
    }
    return ([BitConverter]::ToString($sha.ComputeHash($ms.ToArray())) -replace '-', '')
}

function Assert-SonarToken {
    if (-not $env:SONAR_TOKEN) { throw 'SONAR_TOKEN is not set (masked, protected CI variable; locally: scripts\sonar\Start-LocalSonar.ps1 prints how to create one).' }
}
