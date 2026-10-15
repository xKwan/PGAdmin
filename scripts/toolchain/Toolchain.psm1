#Requires -Version 5.1
<#
    Project-local toolchain management.

    Everything the project installs lives under the tools root (default: <repo>\tools,
    gitignored). Nothing is written to the registry, system PATH or other folders,
    so removing the tools root reverses every install.

    Files:
      toolchain\software.json        what to install (committed)
      toolchain\software.lock.json   exact versions, URLs and checksums (committed; offline bundle pin)
      configuration.properties       generated: installed paths and versions (committed)
      software-list.md               generated: human-readable list (committed)
      tools\.toolchain-state.json    what is actually installed on this machine (not committed)
      tools\downloads\               verified archives (not committed; copy for offline installs)
#>

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# ---------------------------------------------------------------------------
# Context and file helpers
# ---------------------------------------------------------------------------

function New-ToolchainContext {
    [CmdletBinding()]
    param(
        [string]$RepoRoot,
        [string]$ToolsRoot,
        [string]$ManifestPath,
        [string]$LockPath,
        [string]$ConfigPath,
        [string]$ListPath,
        [switch]$NoPreexisting
    )
    if (-not $RepoRoot) { $RepoRoot = Join-Path $PSScriptRoot '..\..' }
    $RepoRoot = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
    if (-not $ToolsRoot) { $ToolsRoot = Join-Path $RepoRoot 'tools' }
    if (-not $ManifestPath) { $ManifestPath = Join-Path $RepoRoot 'toolchain\software.json' }
    if (-not $LockPath) { $LockPath = Join-Path $RepoRoot 'toolchain\software.lock.json' }
    if (-not $ConfigPath) { $ConfigPath = Join-Path $RepoRoot 'configuration.properties' }
    if (-not $ListPath) { $ListPath = Join-Path $RepoRoot 'software-list.md' }
    $ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot).TrimEnd('\')
    [pscustomobject]@{
        RepoRoot      = $RepoRoot
        ToolsRoot     = $ToolsRoot
        Downloads     = (Join-Path $ToolsRoot 'downloads')
        Staging       = (Join-Path $ToolsRoot '.staging')
        StatePath     = (Join-Path $ToolsRoot '.toolchain-state.json')
        ManifestPath  = [IO.Path]::GetFullPath($ManifestPath)
        LockPath      = [IO.Path]::GetFullPath($LockPath)
        ConfigPath    = [IO.Path]::GetFullPath($ConfigPath)
        ListPath      = [IO.Path]::GetFullPath($ListPath)
        NoPreexisting = [bool]$NoPreexisting
    }
}

function Read-JsonFile {
    param([string]$Path, $Default)
    if (-not (Test-Path $Path)) { return $Default }
    $text = [IO.File]::ReadAllText($Path)
    if ($text.Trim() -eq '') { return $Default }
    return ($text | ConvertFrom-Json)
}

function Write-TextFile {
    param([string]$Path, [string]$Text)
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [IO.File]::WriteAllText($Path, $Text.Replace("`r`n", "`n"), $script:Utf8NoBom)
}

function Write-JsonFile {
    param([string]$Path, $Object)
    Write-TextFile -Path $Path -Text (($Object | ConvertTo-Json -Depth 6) + "`n")
}

function Get-DisplayPath {
    # Repo-relative with forward slashes when inside the repo, otherwise absolute with forward slashes.
    param($Ctx, [string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = $Ctx.RepoRoot + '\'
    if ($full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { $full = $full.Substring($prefix.Length) }
    return $full.Replace('\', '/')
}

function Assert-UnderToolsRoot {
    param($Ctx, [string]$Path, [switch]$AllowRoot)
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if ($AllowRoot -and $full -ieq $Ctx.ToolsRoot) { return $full }
    if (-not $full.StartsWith($Ctx.ToolsRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to touch '$full': it is outside the tools root '$($Ctx.ToolsRoot)'."
    }
    return $full
}

function Get-SafeChildPath {
    # Recorded folder/file names must be plain names directly under the tools root (or its
    # downloads folder). Anything else means the state file was tampered with: refuse it,
    # whether or not the target exists.
    param($Ctx, [string]$Parent, [string]$ChildName)
    if (-not $ChildName -or $ChildName -match '[\\/:]' -or $ChildName -eq '.' -or $ChildName -eq '..' -or $ChildName.Contains('..')) {
        throw "Refusing to use '$ChildName': it is outside the tools root '$($Ctx.ToolsRoot)'."
    }
    return (Assert-UnderToolsRoot -Ctx $Ctx -Path (Join-Path $Parent $ChildName))
}

function Remove-UnderToolsRoot {
    param($Ctx, [string]$Path, [switch]$AllowRoot)
    $full = Assert-UnderToolsRoot -Ctx $Ctx -Path $Path -AllowRoot:$AllowRoot
    for ($i = 0; $i -lt 20 -and (Test-Path -LiteralPath $full); $i++) {
        try { Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop } catch { Start-Sleep -Milliseconds 250 }
    }
    if (Test-Path -LiteralPath $full) { throw "Could not remove '$full'." }
}

# ---------------------------------------------------------------------------
# Lock and state (arrays of entries keyed by name)
# ---------------------------------------------------------------------------

function Get-Entries {
    param($Container)
    if ($null -eq $Container) { return @() }
    if ($Container.PSObject.Properties['entries']) { return @($Container.entries | Where-Object { $_ }) }
    return @()
}

function Set-Entry {
    param([object[]]$Entries, $Entry)
    @(@($Entries | Where-Object { $_.name -ne $Entry.name }) + $Entry)
}

# ---------------------------------------------------------------------------
# Resolution (online) and download/verification
# ---------------------------------------------------------------------------

function Enable-Tls12 {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

function Get-UrlText {
    param([string]$Url)
    Enable-Tls12
    $wc = New-Object Net.WebClient
    try { return $wc.DownloadString($Url) } finally { $wc.Dispose() }
}

function Resolve-ToolchainPackage {
    param($Pkg)
    $now = (Get-Date).ToUniversalTime().ToString('o')
    switch ($Pkg.resolver) {
        'adoptium' {
            $api = 'https://api.adoptium.net/v3/assets/feature_releases/' + $Pkg.versionSpec +
                '/ga?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&page=0&page_size=1&project=jdk&sort_method=DATE&sort_order=DESC&vendor=eclipse'
            $release = @((Get-UrlText $api) | ConvertFrom-Json)[0]
            $bin = @($release.binaries | Where-Object { $_.package.name -like '*.zip' })[0]
            if (-not $bin) { throw "Adoptium returned no Windows x64 zip for JDK $($Pkg.versionSpec)." }
            return [pscustomobject]@{
                name = $Pkg.name; versionSpec = $Pkg.versionSpec; version = ($release.release_name -replace '^jdk-', '')
                url = $bin.package.link; file = $bin.package.name; hashAlgorithm = 'SHA256'; hash = $bin.package.checksum.ToUpperInvariant()
                hashSource = 'Adoptium API (HTTPS)'; resolvedAt = $now
            }
        }
        'maven-central' {
            $base = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/'
            $meta = [xml](Get-UrlText ($base + 'maven-metadata.xml'))
            $pattern = '^' + [regex]::Escape($Pkg.versionSpec) + '\.\d+$'
            $version = @($meta.metadata.versioning.versions.version | Where-Object { $_ -match $pattern } |
                    Sort-Object { [version]$_ } -Descending)[0]
            if (-not $version) { throw "No Maven release matches $($Pkg.versionSpec).x" }
            $file = "apache-maven-$version-bin.zip"
            $url = "$base$version/$file"
            $sha512 = ((Get-UrlText "$url.sha512").Trim() -split '\s+')[0].ToUpperInvariant()
            return [pscustomobject]@{
                name = $Pkg.name; versionSpec = $Pkg.versionSpec; version = $version
                url = $url; file = $file; hashAlgorithm = 'SHA512'; hash = $sha512
                hashSource = 'Maven Central .sha512 (HTTPS)'; resolvedAt = $now
            }
        }
        'ziglang' {
            # Official release index; SHA256 per artifact is published in the same HTTPS document.
            $index = (Get-UrlText 'https://ziglang.org/download/index.json') | ConvertFrom-Json
            $pattern = '^' + [regex]::Escape($Pkg.versionSpec) + '(\.\d+)*$'
            $version = @($index.PSObject.Properties.Name | Where-Object { $_ -match $pattern } |
                    Sort-Object { [version]$_ } -Descending)[0]
            if (-not $version) { throw "No Zig release matches $($Pkg.versionSpec)" }
            $artifact = $index.$version.'x86_64-windows'
            if (-not $artifact) { throw "Zig $version has no x86_64-windows build." }
            return [pscustomobject]@{
                name = $Pkg.name; versionSpec = $Pkg.versionSpec; version = $version
                url = $artifact.tarball; file = (Split-Path ([uri]$artifact.tarball).LocalPath -Leaf)
                hashAlgorithm = 'SHA256'; hash = $artifact.shasum.ToUpperInvariant()
                hashSource = 'ziglang.org download index (HTTPS)'; resolvedAt = $now
            }
        }
        'direct' {
            $file = if ($Pkg.PSObject.Properties['file']) { $Pkg.file } else { Split-Path ([uri]$Pkg.url).LocalPath -Leaf }
            return [pscustomobject]@{
                name = $Pkg.name; versionSpec = $Pkg.versionSpec; version = $Pkg.version
                url = $Pkg.url; file = $file; hashAlgorithm = $Pkg.hashAlgorithm.ToUpperInvariant(); hash = $Pkg.hash.ToUpperInvariant()
                hashSource = $(if ($Pkg.PSObject.Properties['hashSource']) { $Pkg.hashSource } else { 'manifest' }); resolvedAt = $now
            }
        }
        default { throw "Unknown resolver '$($Pkg.resolver)' for package '$($Pkg.name)'." }
    }
}

function Test-ArchiveHash {
    param([string]$Path, $LockEntry)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm $LockEntry.hashAlgorithm).Hash
    return ($actual -ieq $LockEntry.hash)
}

function Save-VerifiedArchive {
    param($Ctx, $LockEntry, [switch]$Offline)
    $archive = Get-SafeChildPath -Ctx $Ctx -Parent $Ctx.Downloads -ChildName $LockEntry.file
    if (Test-Path -LiteralPath $archive) {
        if (Test-ArchiveHash -Path $archive -LockEntry $LockEntry) { return $archive }
        Write-Warning "Checksum mismatch for cached $($LockEntry.file); discarding it."
        Remove-Item -LiteralPath $archive -Force
    }
    if ($Offline) { throw "Offline mode: verified archive '$($LockEntry.file)' is not in $($Ctx.Downloads)." }
    if (-not (Test-Path $Ctx.Downloads)) { New-Item -ItemType Directory -Force -Path $Ctx.Downloads | Out-Null }
    $partial = "$archive.partial"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    Enable-Tls12
    $wc = New-Object Net.WebClient
    try { $wc.DownloadFile($LockEntry.url, $partial) }
    catch { if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }; throw "Download failed for $($LockEntry.url): $($_.Exception.Message)" }
    finally { $wc.Dispose() }
    if (-not (Test-ArchiveHash -Path $partial -LockEntry $LockEntry)) {
        Remove-Item -LiteralPath $partial -Force
        throw "Checksum mismatch for downloaded $($LockEntry.file) (expected $($LockEntry.hashAlgorithm) $($LockEntry.hash))."
    }
    Move-Item -LiteralPath $partial -Destination $archive -Force
    return $archive
}

# ---------------------------------------------------------------------------
# Extraction (validates every entry before writing anything: no zip-slip)
# ---------------------------------------------------------------------------

function Expand-ZipSafely {
    param([string]$Archive, [string]$Destination)
    $destFull = [IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $plan = @()
        foreach ($e in $zip.Entries) {
            $name = $e.FullName.Replace('/', '\')
            if ($name -match '^[\\]' -or $name -match ':') { throw "Unsafe path in archive: $($e.FullName)" }
            $target = [IO.Path]::GetFullPath($destFull + $name)
            if (-not $target.StartsWith($destFull, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe path in archive: $($e.FullName)" }
            $plan += , @($e, $target)
        }
        foreach ($item in $plan) {
            $e = $item[0]; $target = $item[1]
            if ($e.FullName.EndsWith('/') -or $e.FullName.EndsWith('\')) {
                if (-not (Test-Path -LiteralPath $target)) { New-Item -ItemType Directory -Force -Path $target | Out-Null }
                continue
            }
            $dir = Split-Path $target -Parent
            if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
        }
    } finally { $zip.Dispose() }
}

# ---------------------------------------------------------------------------
# Install / uninstall
# ---------------------------------------------------------------------------

function Install-ToolchainPackage {
    param($Ctx, $Pkg, $LockEntry, [switch]$Offline)
    $archive = Save-VerifiedArchive -Ctx $Ctx -LockEntry $LockEntry -Offline:$Offline
    $dirName = "$($Pkg.name)-$($LockEntry.version)"
    $dest = Get-SafeChildPath -Ctx $Ctx -Parent $Ctx.ToolsRoot -ChildName $dirName
    $marker = Join-Path $dest '.toolchain-install.json'
    $existing = Read-JsonFile -Path $marker -Default $null
    if ($existing -and $existing.hash -ieq $LockEntry.hash -and (Test-Path -LiteralPath (Join-Path $dest $Pkg.homeCheck))) {
        return [pscustomobject]@{ Entry = $existing; Changed = $false }
    }
    if (Test-Path -LiteralPath $dest) { Remove-UnderToolsRoot -Ctx $Ctx -Path $dest }

    $stage = Join-Path $Ctx.Staging ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    try {
        Expand-ZipSafely -Archive $archive -Destination $stage
        $children = @(Get-ChildItem -LiteralPath $stage -Force)
        $homeDir = if ($children.Count -eq 1 -and $children[0].PSIsContainer) { $children[0].FullName } else { $stage }
        if (-not (Test-Path -LiteralPath (Join-Path $homeDir $Pkg.homeCheck))) {
            throw "Package '$($Pkg.name)' is missing '$($Pkg.homeCheck)' after extraction."
        }
        Move-Item -LiteralPath $homeDir -Destination $dest
    } finally {
        if (Test-Path -LiteralPath $stage) { Remove-UnderToolsRoot -Ctx $Ctx -Path $stage }
        if ((Test-Path -LiteralPath $Ctx.Staging) -and @(Get-ChildItem -LiteralPath $Ctx.Staging -Force).Count -eq 0) {
            Remove-UnderToolsRoot -Ctx $Ctx -Path $Ctx.Staging
        }
    }
    $entry = [pscustomobject]@{
        name = $Pkg.name; version = $LockEntry.version; dir = $dirName; file = $LockEntry.file
        hashAlgorithm = $LockEntry.hashAlgorithm; hash = $LockEntry.hash
        installedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    Write-JsonFile -Path $marker -Object $entry
    return [pscustomobject]@{ Entry = $entry; Changed = $true }
}

function Install-Toolchain {
    <#
    .SYNOPSIS Installs (or verifies) every package in the manifest into the tools root.
    .PARAMETER Update  Re-resolve versions online instead of using the lock file.
    .PARAMETER Offline Never contact the network: requires lock entries and cached archives.
    #>
    [CmdletBinding()]
    param($Ctx, [string[]]$Name, [switch]$Update, [switch]$Offline)
    if ($Update -and $Offline) { throw '-Update and -Offline cannot be combined.' }
    $manifest = Read-JsonFile -Path $Ctx.ManifestPath -Default $null
    if (-not $manifest) { throw "Manifest not found: $($Ctx.ManifestPath)" }
    $lockEntries = @(Get-Entries (Read-JsonFile -Path $Ctx.LockPath -Default $null))
    $stateEntries = @(Get-Entries (Read-JsonFile -Path $Ctx.StatePath -Default $null))
    $results = @()
    try {
        foreach ($pkg in @($manifest.packages)) {
            if ($Name -and ($Name -notcontains $pkg.name)) { continue }
            $lock = @($lockEntries | Where-Object { $_.name -eq $pkg.name })[0]
            $needsResolve = $Update -or (-not $lock) -or ($lock.versionSpec -ne $pkg.versionSpec) -or ($pkg.resolver -eq 'direct')
            if ($needsResolve) {
                if ($Offline -and $pkg.resolver -ne 'direct') { throw "Offline mode: '$($pkg.name)' has no usable lock entry." }
                $lock = Resolve-ToolchainPackage $pkg
                $lockEntries = @(Set-Entry $lockEntries $lock)
            }
            $r = Install-ToolchainPackage -Ctx $Ctx -Pkg $pkg -LockEntry $lock -Offline:$Offline
            # Upgrade: remove a previously installed, different version of the same package.
            $old = @($stateEntries | Where-Object { $_.name -eq $pkg.name -and $_.dir -ne $r.Entry.dir })
            foreach ($o in $old) {
                $oldDir = Get-SafeChildPath -Ctx $Ctx -Parent $Ctx.ToolsRoot -ChildName $o.dir
                if (Test-Path -LiteralPath $oldDir) { Remove-UnderToolsRoot -Ctx $Ctx -Path $oldDir }
            }
            $stateEntries = @(Set-Entry $stateEntries $r.Entry)
            $results += [pscustomobject]@{ Name = $pkg.name; Version = $r.Entry.version; Changed = $r.Changed }
        }
    } finally {
        # Keep the lock in step with the manifest: drop entries for packages no longer listed.
        $known = @($manifest.packages | ForEach-Object { $_.name })
        $lockEntries = @($lockEntries | Where-Object { $known -contains $_.name })
        Write-JsonFile -Path $Ctx.LockPath -Object ([pscustomobject]@{ entries = @($lockEntries | Sort-Object name) })
        if (Test-Path $Ctx.ToolsRoot) {
            Write-JsonFile -Path $Ctx.StatePath -Object ([pscustomobject]@{ entries = @($stateEntries | Sort-Object name) })
        }
        Update-ToolchainFiles -Ctx $Ctx
    }
    return $results
}

function Uninstall-Toolchain {
    <#
    .SYNOPSIS Removes installed packages. -All removes every package; -Purge also deletes cached
              archives (and with -All, the whole tools root including the Maven repository).
    #>
    [CmdletBinding()]
    param($Ctx, [string[]]$Name, [switch]$All, [switch]$Purge)
    if (-not $All -and -not $Name) { throw 'Specify -Name <package> or -All.' }
    $stateEntries = @(Get-Entries (Read-JsonFile -Path $Ctx.StatePath -Default $null))
    $targets = @(if ($All) { $stateEntries } else { $stateEntries | Where-Object { $Name -contains $_.name } })
    # Validate every target before deleting anything, so a tampered entry aborts the whole operation.
    $plan = @(foreach ($t in $targets) {
            [pscustomobject]@{
                Dir     = (Get-SafeChildPath -Ctx $Ctx -Parent $Ctx.ToolsRoot -ChildName $t.dir)
                Archive = (Get-SafeChildPath -Ctx $Ctx -Parent $Ctx.Downloads -ChildName $t.file)
            }
        })
    foreach ($item in $plan) {
        if (Test-Path -LiteralPath $item.Dir) { Remove-UnderToolsRoot -Ctx $Ctx -Path $item.Dir }
        if ($Purge -and (Test-Path -LiteralPath $item.Archive)) { Remove-UnderToolsRoot -Ctx $Ctx -Path $item.Archive }
    }
    $remaining = @($stateEntries | Where-Object { $targets -notcontains $_ })
    if ($All -and $Purge) {
        if (Test-Path -LiteralPath $Ctx.ToolsRoot) { Remove-UnderToolsRoot -Ctx $Ctx -Path $Ctx.ToolsRoot -AllowRoot }
    } elseif (Test-Path $Ctx.ToolsRoot) {
        Write-JsonFile -Path $Ctx.StatePath -Object ([pscustomobject]@{ entries = @($remaining | Sort-Object name) })
    }
    Update-ToolchainFiles -Ctx $Ctx
    return @($targets | ForEach-Object { $_.name })
}

# ---------------------------------------------------------------------------
# Pre-existing software detection
# ---------------------------------------------------------------------------

function Invoke-NativeTool {
    # Runs a console program and returns its combined output and exit code. Many tools (java,
    # git) write version text to stderr; Windows PowerShell 5.1 turns that into a terminating
    # error under ErrorActionPreference=Stop, so it is relaxed only for this call.
    param([string]$Exe, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& $Exe @Arguments 2>&1 | ForEach-Object { "$_" }) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    [pscustomobject]@{ ExitCode = $code; Output = $out.Trim() }
}

function Find-PreexistingTool {
    param($Item)
    $exe = $null
    $cmd = Get-Command $Item.command -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { $exe = $cmd.Source }
    if (-not $exe -and $Item.PSObject.Properties['searchPaths']) {
        foreach ($pattern in @($Item.searchPaths)) {
            $expanded = [Environment]::ExpandEnvironmentVariables($pattern)
            $hit = @(Get-ChildItem -Path $expanded -ErrorAction SilentlyContinue | Sort-Object FullName -Descending)[0]
            if ($hit) { $exe = $hit.FullName; break }
        }
    }
    if (-not $exe) { return $null }
    $version = 'unknown'
    try {
        $r = Invoke-NativeTool -Exe $exe -Arguments @($Item.versionArgs)
        if ($r.Output -match $Item.versionPattern) { $version = $Matches[1] }
    } catch { }
    [pscustomobject]@{ name = $Item.name; displayName = $Item.displayName; version = $version; dir = (Split-Path $exe -Parent); purpose = $Item.purpose }
}

# ---------------------------------------------------------------------------
# Generated files
# ---------------------------------------------------------------------------

function Update-ToolchainFiles {
    param($Ctx)
    $manifest = Read-JsonFile -Path $Ctx.ManifestPath -Default $null
    $lockEntries = @(Get-Entries (Read-JsonFile -Path $Ctx.LockPath -Default $null))
    $stateEntries = @(Get-Entries (Read-JsonFile -Path $Ctx.StatePath -Default $null))
    $pre = @()
    if (-not $Ctx.NoPreexisting -and $manifest -and $manifest.PSObject.Properties['preexisting']) {
        $pre = @(@($manifest.preexisting) | ForEach-Object { Find-PreexistingTool $_ } | Where-Object { $_ })
    }
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm')

    # configuration.properties
    $p = New-Object System.Collections.Generic.List[string]
    $p.Add('# Generated by scripts/toolchain/Install-Toolchain.ps1. Do not edit by hand.')
    $p.Add("# Last updated: $stamp")
    $p.Add('# Paths are relative to the repository root unless absolute. Forward slashes are used throughout.')
    $p.Add('# Project-installed software lives in the tools root (not committed); remove it with')
    $p.Add('#   scripts/toolchain/Uninstall-Toolchain.ps1 -All [-Purge]')
    $p.Add('')
    $p.Add('toolchain.root=' + (Get-DisplayPath $Ctx $Ctx.ToolsRoot))
    $p.Add('toolchain.downloads=' + (Get-DisplayPath $Ctx $Ctx.Downloads))
    $p.Add('toolchain.installed=' + ((@($stateEntries | Sort-Object name | ForEach-Object { $_.name })) -join ','))
    foreach ($s in @($stateEntries | Sort-Object name)) {
        $p.Add('')
        $p.Add("$($s.name).version=$($s.version)")
        $p.Add("$($s.name).home=" + (Get-DisplayPath $Ctx (Join-Path $Ctx.ToolsRoot $s.dir)))
        $pkg = @($manifest.packages | Where-Object { $_.name -eq $s.name })[0]
        if ($pkg -and $pkg.PSObject.Properties['extraProperties']) {
            foreach ($prop in $pkg.extraProperties.PSObject.Properties) {
                $p.Add("$($s.name).$($prop.Name)=" + (Get-DisplayPath $Ctx (Join-Path $Ctx.ToolsRoot $prop.Value)))
            }
        }
    }
    $caches = @()
    if ($manifest -and $manifest.PSObject.Properties['caches']) { $caches = @($manifest.caches) }
    if ($caches.Count -gt 0) {
        $p.Add('')
        $p.Add('# Dependency caches inside the tools root (set for the session by Use-Toolchain.ps1)')
        foreach ($c in $caches) {
            $p.Add("cache.$($c.name)=" + (Get-DisplayPath $Ctx (Get-SafeChildPath -Ctx $Ctx -Parent $Ctx.ToolsRoot -ChildName $c.path)))
            $p.Add("cache.$($c.name).env=$($c.envVar)")
        }
    }
    if ($pre.Count -gt 0) {
        $p.Add('')
        $p.Add('# Pre-existing software detected on this machine (not installed or managed by the project)')
        foreach ($t in $pre) {
            $p.Add("preexisting.$($t.name).version=$($t.version)")
            $p.Add("preexisting.$($t.name).path=" + $t.dir.Replace('\', '/'))
        }
    }
    Write-TextFile -Path $Ctx.ConfigPath -Text (($p -join "`n") + "`n")

    # software-list.md
    $m = New-Object System.Collections.Generic.List[string]
    $m.Add('# Software List')
    $m.Add('')
    $m.Add("> Generated by ``scripts/toolchain/Install-Toolchain.ps1``. Do not edit by hand. Last updated: $stamp.")
    $m.Add('> Machine-readable paths: `configuration.properties`. Pinned versions and checksums: `toolchain/software.lock.json`.')
    $m.Add('')
    $m.Add('## Installed by this project')
    $m.Add('')
    $m.Add("Everything below lives in ``$(Get-DisplayPath $Ctx $Ctx.ToolsRoot)/``, which is **not committed**. Nothing is written to the registry, the system PATH or other folders.")
    $m.Add('')
    if ($stateEntries.Count -eq 0) {
        $m.Add('_Nothing is currently installed._')
    } else {
        $m.Add('| Software | Version | Purpose | Location | Source | Checksum | Installed (UTC) |')
        $m.Add('|---|---|---|---|---|---|---|')
        foreach ($s in @($stateEntries | Sort-Object name)) {
            $pkg = @($manifest.packages | Where-Object { $_.name -eq $s.name })[0]
            $lock = @($lockEntries | Where-Object { $_.name -eq $s.name })[0]
            $display = if ($pkg) { $pkg.displayName } else { $s.name }
            $purpose = if ($pkg) { $pkg.purpose } else { '' }
            $source = if ($lock) { "$($lock.url) (checksum from $($lock.hashSource))" } else { '' }
            $m.Add("| $display | $($s.version) | $purpose | ``$(Get-DisplayPath $Ctx (Join-Path $Ctx.ToolsRoot $s.dir))`` | $source | $($s.hashAlgorithm) ``$($s.hash)`` | $($s.installedAt) |")
        }
    }
    if ($caches.Count -gt 0) {
        $m.Add('')
        $m.Add('## Dependency caches (inside the tools root, not committed)')
        $m.Add('')
        $m.Add('| Cache | Location | Environment variable | Size | Purpose |')
        $m.Add('|---|---|---|---|---|')
        foreach ($c in $caches) {
            $dir = Get-SafeChildPath -Ctx $Ctx -Parent $Ctx.ToolsRoot -ChildName $c.path
            $size = 'empty'
            if (Test-Path -LiteralPath $dir) {
                $bytes = (@(Get-ChildItem -LiteralPath $dir -Recurse -Force -File -ErrorAction SilentlyContinue) | Measure-Object -Property Length -Sum).Sum
                if ($bytes) { $size = '{0:n1} MB' -f ($bytes / 1MB) }
            }
            $m.Add("| $($c.name) | ``$(Get-DisplayPath $Ctx $dir)`` | ``$($c.envVar)`` | $size | $($c.purpose) |")
        }
    }
    $m.Add('')
    $m.Add('## Pre-existing on this machine (detected, not managed)')
    $m.Add('')
    if ($pre.Count -eq 0) {
        $m.Add('_None detected (or detection disabled)._')
    } else {
        $m.Add('| Software | Version | Location | Relevance to the project |')
        $m.Add('|---|---|---|---|')
        foreach ($t in $pre) { $m.Add("| $($t.displayName) | $($t.version) | ``$($t.dir)`` | $($t.purpose) |") }
    }
    $m.Add('')
    $m.Add('## Commands')
    $m.Add('')
    $m.Add('Run from the repository root in Windows PowerShell 5.1 or later:')
    $m.Add('')
    $m.Add('| Task | Command |')
    $m.Add('|---|---|')
    $m.Add('| Install or verify everything (uses pinned versions) | `powershell -ExecutionPolicy Bypass -File scripts\toolchain\Install-Toolchain.ps1` |')
    $m.Add('| Install with no network access (air-gapped) | `... Install-Toolchain.ps1 -Offline` (needs `tools\downloads\` and `toolchain\software.lock.json`) |')
    $m.Add('| Move to newer versions within the manifest''s version spec | `... Install-Toolchain.ps1 -Update` |')
    $m.Add('| Use the tools in the current PowerShell session only | `. .\scripts\toolchain\Use-Toolchain.ps1` |')
    $m.Add('| Remove one package | `... scripts\toolchain\Uninstall-Toolchain.ps1 -Name maven` |')
    $m.Add('| Remove everything the project installed, including downloads and the Maven repository | `... scripts\toolchain\Uninstall-Toolchain.ps1 -All -Purge` |')
    $m.Add('')
    $m.Add('For an air-gapped machine, copy the repository plus `tools\downloads\` and the dependency caches (`tools\m2\`, `tools
pm-cache\`) once they exist, then run the offline install.')
    Write-TextFile -Path $Ctx.ListPath -Text (($m -join "`n") + "`n")
}

Export-ModuleMember -Function New-ToolchainContext, Install-Toolchain, Uninstall-Toolchain, Update-ToolchainFiles, Expand-ZipSafely, Read-JsonFile, Invoke-NativeTool
