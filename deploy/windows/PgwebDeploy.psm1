#Requires -Version 5.1
# Shared functions for the pgweb deployment scripts (plan Phase 12, section 7.6). Windows PowerShell 5.1, no modules
# from the internet. Layout:
#   <InstallRoot>\releases\<version>\   one folder per release (pgweb.jar, lib, runtime, native, service, deploy)
#   <InstallRoot>\current               junction to the active release
#   <DataRoot>\config\pgweb.properties   configuration (ACL: service account, Administrators, SYSTEM)
#   <DataRoot>\{logs,spool,work,backup}  logs, audit spool, Tomcat work dir, audit backups

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Write-Step([string]$Text) { Write-Host "  - $Text" }

function Get-BundleRoot {
    <# Expands a release zip (or accepts an expanded folder) into a staging folder and returns the release folder. #>
    param([Parameter(Mandatory)][string]$Bundle, [Parameter(Mandatory)][string]$Staging)
    if (Test-Path -LiteralPath $Bundle -PathType Container) { return (Resolve-Path -LiteralPath $Bundle).Path }
    if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { throw "Bundle not found: $Bundle" }
    $sha = "$Bundle.sha256"
    if (Test-Path -LiteralPath $sha) {
        $expected = ((Get-Content -LiteralPath $sha -TotalCount 1) -split '\s+')[0]
        $actual = (Get-FileHash -LiteralPath $Bundle -Algorithm SHA256).Hash
        if ($actual -ne $expected.ToUpperInvariant()) { throw "Bundle SHA256 mismatch: $actual, expected $expected ($sha)." }
        Write-Step "bundle checksum OK ($actual)"
    } else {
        Write-Warning "No $sha next to the bundle: its checksum was not verified."
    }
    if (Test-Path -LiteralPath $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $Staging | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Bundle)
    try {
        $root = [IO.Path]::GetFullPath($Staging) + [IO.Path]::DirectorySeparatorChar
        foreach ($e in $zip.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $Staging $e.FullName))
            if (-not $target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe path in bundle: $($e.FullName)" }
            if ($e.FullName.EndsWith('/')) { New-Item -ItemType Directory -Force -Path $target | Out-Null; continue }
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
        }
    } finally { $zip.Dispose() }
    $dirs = @(Get-ChildItem -LiteralPath $Staging -Directory)
    if ($dirs.Count -ne 1) { throw 'The bundle must contain exactly one top-level folder (pgweb-<version>).' }
    return $dirs[0].FullName
}

function Test-ReleaseManifest {
    <# Verifies every file of an expanded release against its SHA256SUMS.txt; any difference or extra file fails. #>
    param([Parameter(Mandatory)][string]$Release)
    $manifest = Join-Path $Release 'SHA256SUMS.txt'
    if (-not (Test-Path $manifest)) { throw "SHA256SUMS.txt missing in $Release" }
    $expected = @{}
    foreach ($line in Get-Content $manifest) {
        if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') { $expected[$Matches[2].Replace('/', '\')] = $Matches[1].ToUpperInvariant() }
    }
    $root = [IO.Path]::GetFullPath($Release).TrimEnd('\') + '\'
    $seen = 0
    foreach ($f in Get-ChildItem -LiteralPath $Release -Recurse -File) {
        $rel = $f.FullName.Substring($root.Length)
        if ($rel -eq 'SHA256SUMS.txt') { continue }
        if (-not $expected.ContainsKey($rel)) { throw "File not in the manifest: $rel" }
        if ((Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash -ne $expected[$rel]) { throw "Checksum mismatch: $rel" }
        $seen++
    }
    if ($seen -ne $expected.Count) { throw "Manifest lists $($expected.Count) files, the release has $seen." }
    Write-Step "release manifest OK ($seen files)"
}

function Get-ReleaseVersion {
    param([Parameter(Mandatory)][string]$Release)
    $v = Join-Path $Release 'VERSION'
    if (-not (Test-Path $v)) { throw "VERSION missing in $Release" }
    return (Get-Content -LiteralPath $v -TotalCount 1).Trim()
}

function Get-JavaExe {
    param([Parameter(Mandatory)][string]$Release)
    $bundled = Join-Path $Release 'runtime\bin\java.exe'
    if (Test-Path $bundled) { return $bundled }
    throw "No Java runtime in $Release (runtime\bin\java.exe)."
}

function Invoke-Pgweb {
    <# Runs a pgweb CLI command of a release (check-config, migrate, verify-audit-chain, version); returns the exit code. #>
    param([Parameter(Mandatory)][string]$Release, [Parameter(Mandatory)][string]$Config, [Parameter(Mandatory)][string]$Command, [string]$Log)
    $java = Get-JavaExe -Release $Release
    $jar = Join-Path $Release 'pgweb.jar'
    $out = if ($Log) { $Log } else { [IO.Path]::GetTempFileName() }
    $p = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"", $Command, '--config', "`"$Config`"") -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err" -WorkingDirectory $Release
    Get-Content $out, "$out.err" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
    return $p.ExitCode
}

function Initialize-DataRoot {
    <# Creates the data folders and restricts them to SYSTEM, Administrators and the service account. #>
    param([Parameter(Mandatory)][string]$DataRoot, [Parameter(Mandatory)][string]$Account)
    foreach ($d in 'config', 'logs', 'spool', 'work', 'backup') { New-Item -ItemType Directory -Force -Path (Join-Path $DataRoot $d) | Out-Null }
    $r = & icacls.exe $DataRoot /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' "${Account}:(OI)(CI)M" /T /C /Q 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Setting the ACL on $DataRoot failed: $r" }
    Write-Step "data folders and ACL set (${DataRoot}: SYSTEM, Administrators, $Account)"
}

function Set-CurrentRelease {
    <# Points the 'current' junction at a release folder. #>
    param([Parameter(Mandatory)][string]$InstallRoot, [Parameter(Mandatory)][string]$Version)
    $target = Join-Path $InstallRoot "releases\$Version"
    if (-not (Test-Path $target)) { throw "Release $Version is not installed ($target)." }
    $current = Join-Path $InstallRoot 'current'
    if (Test-Path -LiteralPath $current) {
        $item = Get-Item -LiteralPath $current -Force
        if (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "$current exists and is not a junction." }
        [IO.Directory]::Delete($current) # removes the junction only, never the release it points to
    }
    $r = & cmd.exe /d /c "mklink /J `"$current`" `"$target`"" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Creating the junction failed: $r" }
    Write-Step "current -> releases\$Version"
}

function Get-CurrentRelease {
    param([Parameter(Mandatory)][string]$InstallRoot)
    $current = Join-Path $InstallRoot 'current'
    if (-not (Test-Path -LiteralPath $current)) { return $null }
    $t = (Get-Item -LiteralPath $current -Force).Target
    if ($t -is [array]) { $t = $t[0] }
    return Split-Path -Leaf $t
}

function Get-ConfigValue {
    param([Parameter(Mandatory)][string]$Config, [Parameter(Mandatory)][string]$Key, [string]$Default)
    $line = Get-Content -LiteralPath $Config | Where-Object { $_ -match ('^\s*' + [regex]::Escape($Key) + '\s*=') } | Select-Object -First 1
    if ($line) { return ($line -split '=', 2)[1].Trim() } else { return $Default }
}

function Get-BaseUrl {
    param([Parameter(Mandatory)][string]$Config)
    $port = Get-ConfigValue -Config $Config -Key 'server.port' -Default '8443'
    $tls = Get-ConfigValue -Config $Config -Key 'server.tls.keystore'
    $bindHost = Get-ConfigValue -Config $Config -Key 'server.bind' -Default '127.0.0.1'
    if ($bindHost -eq '0.0.0.0') { $bindHost = '127.0.0.1' }
    $scheme = if ($tls) { 'https' } else { 'http' }
    return "${scheme}://${bindHost}:$port"
}

function Wait-PgwebReady {
    <# Smoke test: /api/health (process up) and /api/ready (audit database reachable) both answer 200. #>
    param([Parameter(Mandatory)][string]$BaseUrl, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $health = $false; $ready = $false
    while ((Get-Date) -lt $deadline -and -not ($health -and $ready)) {
        Start-Sleep -Milliseconds 500
        try { $health = (Invoke-WebRequest -UseBasicParsing "$BaseUrl/api/health" -TimeoutSec 5).StatusCode -eq 200 } catch { $health = $false }
        if ($health) { try { $ready = (Invoke-WebRequest -UseBasicParsing "$BaseUrl/api/ready" -TimeoutSec 5).StatusCode -eq 200 } catch { $ready = $false } }
    }
    if (-not $health) { throw "Smoke test failed: $BaseUrl/api/health did not answer 200 within $TimeoutSeconds s." }
    if (-not $ready) { throw "Smoke test failed: $BaseUrl/api/ready did not answer 200 (audit database?)." }
    Write-Step "smoke test OK ($BaseUrl/api/health, /api/ready)"
}

function Write-ServiceConfig {
    <# Writes the WinSW configuration next to the service executable of a release. #>
    param([Parameter(Mandatory)][string]$Release, [Parameter(Mandatory)][string]$InstallRoot, [Parameter(Mandatory)][string]$DataRoot,
          [Parameter(Mandatory)][string]$ServiceName, [string]$Account, [string]$JavaOptions = '-Xms256m -Xmx1g')
    $template = Get-Content -Raw (Join-Path $Release 'deploy\windows\pgweb-service.xml.template')
    $current = Join-Path $InstallRoot 'current'
    $accountXml = ''
    if ($Account -and $Account -notmatch '^(LocalSystem|NT AUTHORITY\\SYSTEM)$') {
        # A gMSA (name ending in $) has no password; WinSW then logs on without one.
        $accountXml = "<serviceaccount><username>$([Security.SecurityElement]::Escape($Account))</username><allowservicelogon>true</allowservicelogon></serviceaccount>"
    }
    $xml = $template.Replace('@SERVICE_NAME@', [Security.SecurityElement]::Escape($ServiceName)).Replace('@CURRENT@', [Security.SecurityElement]::Escape($current)).
        Replace('@DATA_ROOT@', [Security.SecurityElement]::Escape($DataRoot)).Replace('@JAVA_OPTIONS@', [Security.SecurityElement]::Escape($JavaOptions)).
        Replace('<!--@SERVICE_ACCOUNT@-->', $accountXml)
    $path = Join-Path $Release 'service\pgweb-service.xml'
    Set-Content -Path $path -Value $xml -Encoding UTF8
    return $path
}

function Stop-PgwebProcess {
    <#
      Stops a pgweb process the way the Windows service wrapper does: a console Ctrl+C, so the shutdown hook closes
      every database session (PostgreSQL rolls back open transactions); after the timeout it is killed.
    #>
    param([Parameter(Mandatory)][int]$ProcessId, [int]$TimeoutSeconds = 30)
    $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $p) { return }
    $helper = @"
Add-Type -Namespace W -Name K -MemberDefinition '[DllImport("kernel32.dll")] public static extern bool FreeConsole(); [DllImport("kernel32.dll")] public static extern bool AttachConsole(uint p); [DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(System.IntPtr h, bool add); [DllImport("kernel32.dll")] public static extern bool GenerateConsoleCtrlEvent(uint e, uint g);'
[W.K]::FreeConsole() | Out-Null
if (-not [W.K]::AttachConsole($ProcessId)) { exit 2 }
[W.K]::SetConsoleCtrlHandler([IntPtr]::Zero, `$true) | Out-Null
[W.K]::GenerateConsoleCtrlEvent(0, 0) | Out-Null
Start-Sleep -Milliseconds 500
exit 0
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($helper))
    $h = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded) -WindowStyle Hidden -PassThru -Wait
    if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
        Write-Warning "pgweb (pid $ProcessId) did not stop within $TimeoutSeconds s; killing it."
        Stop-Process -Id $ProcessId -Force
    } else {
        Write-Step "pgweb stopped gracefully (pid $ProcessId, Ctrl+C helper exit $($h.ExitCode))"
    }
}

function Start-PgwebInstance {
    <#
      Starts the current release: as the Windows service, or with -NoService as a process of the calling user (only
      for rehearsals and tests). The process gets its own hidden console, so a later Ctrl+C reaches only pgweb.
    #>
    param([Parameter(Mandatory)][string]$InstallRoot, [Parameter(Mandatory)][string]$DataRoot, [Parameter(Mandatory)][string]$ServiceName, [switch]$NoService)
    $current = Join-Path $InstallRoot 'current'
    if ($NoService) {
        $java = Join-Path $current 'runtime\bin\java.exe'
        $config = Join-Path $DataRoot 'config\pgweb.properties'
        $p = Start-Process -FilePath $java -WindowStyle Hidden -PassThru -WorkingDirectory $current `
            -ArgumentList @('-XX:-HeapDumpOnOutOfMemoryError', '-Dfile.encoding=UTF-8', '-jar', "`"$current\pgweb.jar`"", 'serve', '--config', "`"$config`"")
        Set-Content -Path (Join-Path $DataRoot 'work\pgweb.pid') -Value $p.Id -Encoding ASCII
        Write-Step "started pgweb as a process (pid $($p.Id); -NoService)"
    } else {
        Start-Service -Name $ServiceName
        Write-Step "service $ServiceName started"
    }
}

function Stop-PgwebInstance {
    param([Parameter(Mandatory)][string]$DataRoot, [Parameter(Mandatory)][string]$ServiceName, [switch]$NoService)
    if ($NoService) {
        $pidFile = Join-Path $DataRoot 'work\pgweb.pid'
        if (Test-Path $pidFile) {
            Stop-PgwebProcess -ProcessId ([int](Get-Content $pidFile -TotalCount 1))
            Remove-Item $pidFile -Force
        }
    } elseif (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
        Stop-Service -Name $ServiceName # WinSW: Ctrl+C, graceful shutdown, kill after stoptimeout
        Write-Step "service $ServiceName stopped"
    }
}

function Add-DeployHistory {
    param([Parameter(Mandatory)][string]$DataRoot, [Parameter(Mandatory)][string]$Action, [Parameter(Mandatory)][string]$Version, [string]$From, [double]$Seconds)
    $file = Join-Path $DataRoot 'deploy-history.json'
    $history = @()
    # PowerShell 5 emits a JSON array as one object: ForEach-Object unrolls it.
    if (Test-Path $file) { $history = @(Get-Content -Raw $file | ConvertFrom-Json | ForEach-Object { $_ }) }
    $history += [pscustomobject]@{ action = $Action; version = $Version; from = $From; when = (Get-Date).ToString('o')
        by = "$env:USERDOMAIN\$env:USERNAME"; host = $env:COMPUTERNAME; seconds = [math]::Round($Seconds, 1) }
    ConvertTo-Json -InputObject $history -Depth 4 | Set-Content -Path $file -Encoding UTF8
}

function Get-DeployHistory {
    param([Parameter(Mandatory)][string]$DataRoot)
    $file = Join-Path $DataRoot 'deploy-history.json'
    if (Test-Path $file) { return @(Get-Content -Raw $file | ConvertFrom-Json | ForEach-Object { $_ }) } else { return @() }
}

function Assert-Administrator {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this from an elevated PowerShell (Run as administrator). Installing and controlling the service needs it.'
    }
}

Export-ModuleMember -Function Write-Step, Get-BundleRoot, Test-ReleaseManifest, Get-ReleaseVersion, Get-JavaExe, Invoke-Pgweb, Initialize-DataRoot,
    Set-CurrentRelease, Get-CurrentRelease, Get-ConfigValue, Get-BaseUrl, Wait-PgwebReady, Write-ServiceConfig, Stop-PgwebProcess,
    Start-PgwebInstance, Stop-PgwebInstance, Add-DeployHistory, Get-DeployHistory, Assert-Administrator
