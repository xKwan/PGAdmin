#Requires -Version 5.1
<#
.SYNOPSIS
    Randomised tests for scripts\toolchain\Toolchain.psm1. No network needed: fake packages
    are built on the fly and served from file:// URLs inside a throwaway sandbox.

.DESCRIPTION
    Each run builds 2-4 random packages (random names, versions, file trees, hash algorithms,
    with or without a top-level folder), installs them, then runs every scenario in a random
    order. Any failure resets the consecutive-pass count to 0. Runs until
    RequiredConsecutivePasses runs in a row pass.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\toolchain\tests\Test-Toolchain.ps1
#>
[CmdletBinding()]
param(
    [int]$RequiredConsecutivePasses = 5,
    [int]$MaxRuns = 15,
    [int]$Seed = 0,
    [string]$ResultsDir,
    [switch]$ContinueOnFailure
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ResultsDir) { $ResultsDir = Join-Path $here 'results' }
Import-Module (Join-Path $here '..\Toolchain.psm1') -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw "Assertion failed: $Message" } }

function Assert-Throws([scriptblock]$Action, [string]$Pattern, [string]$Message) {
    $threw = $false; $msg = ''
    try { & $Action } catch { $threw = $true; $msg = $_.Exception.Message }
    Assert-True $threw "$Message (no exception)"
    Assert-True ($msg -match $Pattern) "$Message (message was: $msg)"
}

function New-FakeZip {
    param([string]$Path, [string]$TopDir, [string[]]$EntryNames, [System.Random]$Rng)
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($n in $EntryNames) {
            $full = if ($TopDir) { "$TopDir/$n" } else { $n }
            $e = $zip.CreateEntry($full)
            $s = $e.Open()
            try {
                $bytes = New-Object byte[] ($Rng.Next(1, 4096))
                $Rng.NextBytes($bytes)
                $s.Write($bytes, 0, $bytes.Length)
            } finally { $s.Dispose() }
        }
    } finally { $zip.Dispose() }
}

function New-FakePackage {
    param($Sandbox, [System.Random]$Rng, [string]$Name, [string]$Version)
    if (-not $Name) { $Name = 'pkg' + $Rng.Next(1000, 9999) }
    if (-not $Version) { $Version = "1.$($Rng.Next(0, 20)).$($Rng.Next(0, 50))" }
    $entries = @('bin/tool.cmd')
    for ($i = 0; $i -lt $Rng.Next(3, 15); $i++) {
        $depth = $Rng.Next(0, 3)
        $parts = @(); for ($d = 0; $d -lt $depth; $d++) { $parts += ('d' + $Rng.Next(0, 5)) }
        $entries += ((@($parts) + ('f' + $Rng.Next(0, 100000) + '.bin')) -join '/')
    }
    $entries = @($entries | Select-Object -Unique)
    $topDir = if ($Rng.Next(0, 2) -eq 0) { "$Name-$Version" } else { $null }
    $file = "$Name-$Version.zip"
    $path = Join-Path $Sandbox.Source $file
    New-FakeZip -Path $path -TopDir $topDir -EntryNames $entries -Rng $Rng
    $alg = @('SHA256', 'SHA512')[$Rng.Next(0, 2)]
    $pkg = [pscustomobject]@{
        name = $Name; displayName = "Fake $Name"; resolver = 'direct'; versionSpec = $Version; version = $Version
        url = ([uri]$path).AbsoluteUri; file = $file; hashAlgorithm = $alg; hash = (Get-FileHash -LiteralPath $path -Algorithm $alg).Hash
        homeCheck = 'bin/tool.cmd'; purpose = "Test package $Name"
        extraProperties = [pscustomobject]@{ 'data.dir' = "data/$Name" }
    }
    # Half of the packages carry their own hashSource note (e.g. "pinned at first download").
    if ($Rng.Next(0, 2) -eq 0) { $pkg | Add-Member -NotePropertyName hashSource -NotePropertyValue "pinned at first download (test $($Rng.Next(1000, 9999)))" }
    return $pkg
}

function Save-Manifest($Sandbox, [object[]]$Packages) {
    $obj = [pscustomobject]@{ description = 'test'; packages = @($Packages) }
    [IO.File]::WriteAllText($Sandbox.Ctx.ManifestPath, ($obj | ConvertTo-Json -Depth 6))
}

function Get-State($Sandbox) {
    $s = Read-JsonFile -Path $Sandbox.Ctx.StatePath -Default $null
    if ($null -eq $s) { return @() }
    return @($s.entries | Where-Object { $_ })
}

function Get-Props($Sandbox) {
    $h = @{}
    foreach ($line in [IO.File]::ReadAllLines($Sandbox.Ctx.ConfigPath)) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $k, $v = $line -split '=', 2; $h[$k] = $v
    }
    return $h
}

function Get-TreeFingerprint([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    (@(Get-ChildItem -LiteralPath $Path -Recurse -Force -File | Sort-Object FullName | ForEach-Object {
                $_.FullName.Substring($Path.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }) -join "`n")
}

function Assert-Installed($Sandbox, $Pkg) {
    $dir = Join-Path $Sandbox.Ctx.ToolsRoot "$($Pkg.name)-$($Pkg.version)"
    Assert-True (Test-Path -LiteralPath (Join-Path $dir 'bin\tool.cmd')) "$($Pkg.name) home check missing"
    Assert-True (Test-Path -LiteralPath (Join-Path $dir '.toolchain-install.json')) "$($Pkg.name) install marker missing"
    $st = @(Get-State $Sandbox | Where-Object { $_.name -eq $Pkg.name })
    Assert-True ($st.Count -eq 1 -and $st[0].version -eq $Pkg.version) "$($Pkg.name) state entry wrong"
    $props = Get-Props $Sandbox
    Assert-True ($props["$($Pkg.name).version"] -eq $Pkg.version) "$($Pkg.name).version property wrong"
    Assert-True ($props["$($Pkg.name).home"] -eq "tools/$($Pkg.name)-$($Pkg.version)") "$($Pkg.name).home property wrong: $($props["$($Pkg.name).home"])"
    Assert-True ($props["$($Pkg.name).data.dir"] -eq "tools/data/$($Pkg.name)") "$($Pkg.name) extra property wrong"
    $list = [IO.File]::ReadAllText($Sandbox.Ctx.ListPath)
    Assert-True ($list -match [regex]::Escape("| Fake $($Pkg.name) | $($Pkg.version) |")) "$($Pkg.name) missing from software list"
    Assert-True ($list -match [regex]::Escape($Pkg.hash)) "$($Pkg.name) checksum missing from software list"
}

function Assert-NotInstalled($Sandbox, $Pkg) {
    $dir = Join-Path $Sandbox.Ctx.ToolsRoot "$($Pkg.name)-$($Pkg.version)"
    Assert-True (-not (Test-Path -LiteralPath $dir)) "$($Pkg.name) directory still present"
    Assert-True (@(Get-State $Sandbox | Where-Object { $_.name -eq $Pkg.name }).Count -eq 0) "$($Pkg.name) still in state"
    $props = Get-Props $Sandbox
    Assert-True (-not $props.ContainsKey("$($Pkg.name).home")) "$($Pkg.name).home property still present"
    $list = [IO.File]::ReadAllText($Sandbox.Ctx.ListPath)
    Assert-True ($list -notmatch [regex]::Escape("| Fake $($Pkg.name) |")) "$($Pkg.name) still in software list"
}

function Assert-AllInstalled($Sandbox) { foreach ($p in $Sandbox.Packages) { Assert-Installed $Sandbox $p } }

function Assert-NoStaging($Sandbox) {
    Assert-True (-not (Test-Path -LiteralPath $Sandbox.Ctx.Staging)) 'staging folder left behind'
    Assert-True (@(Get-ChildItem -LiteralPath $Sandbox.Ctx.Downloads -Filter '*.partial' -ErrorAction SilentlyContinue).Count -eq 0) 'partial download left behind'
}

function Hide-Source($Sandbox, $Pkg) {
    $src = Join-Path $Sandbox.Source $Pkg.file
    Move-Item -LiteralPath $src -Destination "$src.hidden"
}
function Show-Source($Sandbox, $Pkg) {
    $src = Join-Path $Sandbox.Source $Pkg.file
    if (Test-Path -LiteralPath "$src.hidden") { Move-Item -LiteralPath "$src.hidden" -Destination $src }
}

# ---------------------------------------------------------------------------
# Scenarios: each starts and ends with every sandbox package installed.
# ---------------------------------------------------------------------------

$Scenarios = [ordered]@{

    'idempotent-reinstall' = {
        param($S, $Rng)
        $before = Get-TreeFingerprint $S.Ctx.ToolsRoot
        $r = @(Install-Toolchain -Ctx $S.Ctx)
        Assert-True (@($r | Where-Object { $_.Changed }).Count -eq 0) 'reinstall reported changes'
        $after = Get-TreeFingerprint $S.Ctx.ToolsRoot
        # The state file is rewritten with identical content; everything else must be byte-identical.
        Assert-True ($before -eq $after) 'tools tree changed on an idempotent reinstall'
        "packages=$($S.Packages.Count)"
    }

    'corrupt-cache-redownloads' = {
        param($S, $Rng)
        $p = $S.Packages[$Rng.Next(0, $S.Packages.Count)]
        $archive = Join-Path $S.Ctx.Downloads $p.file
        [IO.File]::AppendAllText($archive, 'corruption' + $Rng.Next())
        $null = Install-Toolchain -Ctx $S.Ctx -WarningAction SilentlyContinue
        Assert-True ((Get-FileHash -LiteralPath $archive -Algorithm $p.hashAlgorithm).Hash -eq $p.hash) 'corrupted archive was not replaced'
        Assert-AllInstalled $S; Assert-NoStaging $S
        "corrupted=$($p.name)"
    }

    'offline-reinstall-from-cache' = {
        param($S, $Rng)
        $p = $S.Packages[$Rng.Next(0, $S.Packages.Count)]
        Remove-Item -LiteralPath (Join-Path $S.Ctx.ToolsRoot "$($p.name)-$($p.version)") -Recurse -Force
        Hide-Source $S $p
        try { $null = Install-Toolchain -Ctx $S.Ctx -Offline } finally { Show-Source $S $p }
        Assert-AllInstalled $S; Assert-NoStaging $S
        "reinstalled=$($p.name) source=hidden"
    }

    'corrupt-without-source-fails-cleanly' = {
        param($S, $Rng)
        $p = $S.Packages[$Rng.Next(0, $S.Packages.Count)]
        $others = @($S.Packages | Where-Object { $_.name -ne $p.name })
        $null = Uninstall-Toolchain -Ctx $S.Ctx -Name $p.name
        [IO.File]::WriteAllText((Join-Path $S.Ctx.Downloads $p.file), 'garbage')
        Hide-Source $S $p
        try {
            Assert-Throws { Install-Toolchain -Ctx $S.Ctx -Name $p.name -WarningAction SilentlyContinue } 'Download failed' 'install should fail with no valid archive and no source'
        } finally { Show-Source $S $p }
        Assert-NotInstalled $S $p
        foreach ($o in $others) { Assert-Installed $S $o }
        Assert-NoStaging $S
        $null = Install-Toolchain -Ctx $S.Ctx
        Assert-AllInstalled $S
        "failed-cleanly=$($p.name)"
    }

    'uninstall-one-then-reinstall' = {
        param($S, $Rng)
        $p = $S.Packages[$Rng.Next(0, $S.Packages.Count)]
        $removed = @(Uninstall-Toolchain -Ctx $S.Ctx -Name $p.name)
        Assert-True ($removed -contains $p.name) 'uninstall did not report the package'
        Assert-NotInstalled $S $p
        Assert-True (Test-Path -LiteralPath (Join-Path $S.Ctx.Downloads $p.file)) 'archive removed without -Purge'
        foreach ($o in @($S.Packages | Where-Object { $_.name -ne $p.name })) { Assert-Installed $S $o }
        $null = Install-Toolchain -Ctx $S.Ctx -Offline
        Assert-AllInstalled $S
        "removed-and-restored=$($p.name)"
    }

    'single-file-package' = {
        # A package that is one self-contained file (WinSW-x64.exe): verified by hash, copied as is, removable.
        param($S, $Rng)
        $exe = 'tool' + $Rng.Next(100, 999) + '-x64.exe'
        $file = "single-1.0.$($Rng.Next(0, 50)).exe"
        $path = Join-Path $S.Source $file
        $bytes = New-Object byte[] ($Rng.Next(1, 65536)); $Rng.NextBytes($bytes)
        [IO.File]::WriteAllBytes($path, $bytes)
        $single = [pscustomobject]@{
            name = 'single'; displayName = 'Fake single'; resolver = 'direct'; versionSpec = '1.0.0'; version = '1.0.0'
            url = ([uri]$path).AbsoluteUri; file = $file; hashAlgorithm = 'SHA256'; hash = (Get-FileHash $path -Algorithm SHA256).Hash
            homeCheck = $exe; purpose = 'single-file test package'
        }
        $home1 = Join-Path $S.Ctx.ToolsRoot 'single-1.0.0'
        Save-Manifest $S (@($S.Packages) + $single)
        try {
            $null = Install-Toolchain -Ctx $S.Ctx -Name 'single'
            $installed = Join-Path $home1 $exe
            Assert-True (Test-Path -LiteralPath $installed) 'single-file package not installed under its home'
            Assert-True ((Get-FileHash $installed -Algorithm SHA256).Hash -eq $single.hash) 'installed file differs from the verified download'
            Assert-True ((Get-Props $S)['single.home'] -eq 'tools/single-1.0.0') 'single.home property wrong'
            # A download whose hash doesn't match is refused, as for archives.
            $null = Uninstall-Toolchain -Ctx $S.Ctx -Name 'single'
            [IO.File]::WriteAllBytes($path, [byte[]]($bytes + [byte]1))
            Remove-Item -LiteralPath (Join-Path $S.Ctx.Downloads $file) -Force -ErrorAction SilentlyContinue
            Assert-Throws { Install-Toolchain -Ctx $S.Ctx -Name 'single' -WarningAction SilentlyContinue } 'Download failed|hash|checksum' 'a tampered single-file download was accepted'
            Assert-True (-not (Test-Path -LiteralPath $home1)) 'tampered single-file package left a home folder'
        } finally {
            try { $null = Uninstall-Toolchain -Ctx $S.Ctx -Name 'single' } catch { Write-Verbose "single not installed: $_" }
            Save-Manifest $S $S.Packages
            $null = Install-Toolchain -Ctx $S.Ctx
        }
        Assert-NoStaging $S
        Assert-AllInstalled $S
        "file=$exe bytes=$($bytes.Length)"
    }

    'zip-slip-rejected' = {
        param($S, $Rng)
        $escapeName = 'escape-' + $Rng.Next(100000, 999999) + '.txt'
        $variant = @("../$escapeName", "../../$escapeName", "bin/../../$escapeName")[$Rng.Next(0, 3)]
        $file = 'evil-1.0.0.zip'
        $path = Join-Path $S.Source $file
        New-FakeZip -Path $path -TopDir $null -EntryNames @('bin/tool.cmd', $variant) -Rng $Rng
        $evil = [pscustomobject]@{
            name = 'evil'; displayName = 'Fake evil'; resolver = 'direct'; versionSpec = '1.0.0'; version = '1.0.0'
            url = ([uri]$path).AbsoluteUri; file = $file; hashAlgorithm = 'SHA256'; hash = (Get-FileHash $path -Algorithm SHA256).Hash
            homeCheck = 'bin/tool.cmd'; purpose = 'malicious test archive'
        }
        Save-Manifest $S (@($S.Packages) + $evil)
        try {
            Assert-Throws { Install-Toolchain -Ctx $S.Ctx -Name 'evil' } 'Unsafe path' 'zip-slip archive was not rejected'
        } finally { Save-Manifest $S $S.Packages }
        $leaked = @(Get-ChildItem -LiteralPath $S.Root -Recurse -Force -Filter $escapeName -ErrorAction SilentlyContinue)
        Assert-True ($leaked.Count -eq 0) "escaped file was written: $($leaked | ForEach-Object FullName)"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $S.Ctx.ToolsRoot 'evil-1.0.0'))) 'evil package directory created'
        Assert-NoStaging $S
        $null = Install-Toolchain -Ctx $S.Ctx
        $lock = Read-JsonFile -Path $S.Ctx.LockPath -Default $null
        Assert-True (@($lock.entries | Where-Object { $_.name -eq 'evil' }).Count -eq 0) 'lock kept an entry for a package no longer in the manifest'
        Assert-AllInstalled $S
        "entry=$variant"
    }

    'refuses-to-delete-outside-tools' = {
        param($S, $Rng)
        $statePath = $S.Ctx.StatePath
        $original = [IO.File]::ReadAllText($statePath)
        $target = @('..\..\outside', '..\outside', 'C:\Windows')[$Rng.Next(0, 3)]
        $tampered = [pscustomobject]@{ entries = @(@(Get-State $S) + [pscustomobject]@{ name = 'tamper'; version = 'x'; dir = $target; file = 'none.zip'; hashAlgorithm = 'SHA256'; hash = 'X'; installedAt = '' }) }
        [IO.File]::WriteAllText($statePath, ($tampered | ConvertTo-Json -Depth 6))
        try {
            Assert-Throws { Uninstall-Toolchain -Ctx $S.Ctx -Name 'tamper' } 'outside the tools root' 'uninstall did not refuse a path outside the tools root'
        } finally { [IO.File]::WriteAllText($statePath, $original) }
        Assert-True ((Get-TreeFingerprint $S.Outside) -eq $S.OutsideFingerprint) 'files outside the tools root changed'
        $null = Install-Toolchain -Ctx $S.Ctx
        Assert-AllInstalled $S
        "target=$target"
    }

    'upgrade-replaces-old-version' = {
        param($S, $Rng)
        $i = $Rng.Next(0, $S.Packages.Count)
        $old = $S.Packages[$i]
        $parts = $old.version.Split('.')
        $newVersion = "$($parts[0]).$([int]$parts[1] + 1).$($Rng.Next(0, 50))"
        $new = New-FakePackage -Sandbox $S -Rng $Rng -Name $old.name -Version $newVersion
        $S.Packages[$i] = $new
        Save-Manifest $S $S.Packages
        $null = Install-Toolchain -Ctx $S.Ctx
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $S.Ctx.ToolsRoot "$($old.name)-$($old.version)"))) 'old version directory not removed'
        Assert-AllInstalled $S
        "$($old.name): $($old.version) -> $newVersion"
    }

    'hash-source-recorded' = {
        param($S, $Rng)
        $lock = @((Read-JsonFile -Path $S.Ctx.LockPath -Default $null).entries)
        $list = [IO.File]::ReadAllText((Join-Path $S.Repo 'software-list.md'))
        foreach ($p in $S.Packages) {
            $entry = @($lock | Where-Object { $_.name -eq $p.name })[0]
            $want = if ($p.PSObject.Properties['hashSource']) { $p.hashSource } else { 'manifest' }
            Assert-True ($entry.hashSource -eq $want) "$($p.name): lock hashSource '$($entry.hashSource)', expected '$want'"
            Assert-True ($list.Contains("checksum from $want")) "$($p.name): software-list.md does not show the hash source"
        }
        "custom=$(@($S.Packages | Where-Object { $_.PSObject.Properties['hashSource'] }).Count)/$($S.Packages.Count)"
    }

    'caches-recorded-and-confined' = {
        param($S, $Rng)
        $caches = @()
        for ($i = 0; $i -lt $Rng.Next(1, 4); $i++) {
            $caches += [pscustomobject]@{ name = "c$i"; path = "cache-$($Rng.Next(100, 999))-$i"; envVar = "TEST_CACHE_$i"; purpose = "test cache $i" }
        }
        # One cache gets content so the size column is exercised.
        $withData = Join-Path $S.Ctx.ToolsRoot $caches[0].path
        New-Item -ItemType Directory -Force -Path $withData | Out-Null
        [IO.File]::WriteAllBytes((Join-Path $withData 'blob.bin'), (New-Object byte[] ($Rng.Next(1, 4) * 1MB)))
        $obj = [pscustomobject]@{ description = 'test'; packages = @($S.Packages); caches = $caches }
        [IO.File]::WriteAllText($S.Ctx.ManifestPath, ($obj | ConvertTo-Json -Depth 6))
        try {
            Update-ToolchainFiles -Ctx $S.Ctx
            $props = Get-Props $S
            $list = [IO.File]::ReadAllText((Join-Path $S.Repo 'software-list.md'))
            foreach ($c in $caches) {
                Assert-True ($props["cache.$($c.name)"] -eq "tools/$($c.path)") "cache.$($c.name) = '$($props["cache.$($c.name)"])'"
                Assert-True ($props["cache.$($c.name).env"] -eq $c.envVar) "cache.$($c.name).env"
                Assert-True ($list.Contains("``tools/$($c.path)``")) "software-list.md lists $($c.name)"
            }
            Assert-True ($list -match "\| c0 \| ``tools/[^``]+`` \| ``TEST_CACHE_0`` \| \d+\.\d MB \|") 'size shown for a non-empty cache'
            # A cache path that escapes the tools root is refused.
            $evil = [pscustomobject]@{ description = 'test'; packages = @($S.Packages); caches = @([pscustomobject]@{ name = 'evil'; path = '..\outside'; envVar = 'X'; purpose = 'x' }) }
            [IO.File]::WriteAllText($S.Ctx.ManifestPath, ($evil | ConvertTo-Json -Depth 6))
            Assert-Throws { Update-ToolchainFiles -Ctx $S.Ctx } '' 'cache path outside the tools root is refused'
        } finally {
            Save-Manifest $S $S.Packages
            Update-ToolchainFiles -Ctx $S.Ctx
            Remove-Item -Recurse -Force $withData
        }
        "caches=$($caches.Count)"
    }

    'properties-point-at-real-homes' = {
        param($S, $Rng)
        $props = Get-Props $S
        $installed = @($props['toolchain.installed'] -split ',' | Where-Object { $_ })
        Assert-True ($installed.Count -eq $S.Packages.Count) "toolchain.installed lists $($installed.Count), expected $($S.Packages.Count)"
        foreach ($k in @($props.Keys | Where-Object { $_ -like '*.home' })) {
            # Not $home: that is a read-only automatic variable in PowerShell.
            $pkgHome = Join-Path $S.Repo ($props[$k].Replace('/', '\'))
            Assert-True (Test-Path -LiteralPath (Join-Path $pkgHome 'bin\tool.cmd')) "$k does not point at an installed package"
        }
        "homes=$(@($props.Keys | Where-Object { $_ -like '*.home' }).Count)"
    }
}

# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

function Invoke-OneRun([int]$RunSeed) {
    $rng = New-Object System.Random($RunSeed)
    $root = Join-Path ([IO.Path]::GetTempPath()) ('pgweb-toolchain-test-' + [guid]::NewGuid().ToString('N'))
    $repo = Join-Path $root 'repo'
    $S = [pscustomobject]@{
        Root = $root; Repo = $repo; Source = (Join-Path $root 'source'); Outside = (Join-Path $root 'outside')
        Ctx = $null; Packages = @(); OutsideFingerprint = ''
    }
    $results = @()
    try {
        New-Item -ItemType Directory -Force -Path (Join-Path $repo 'toolchain'), $S.Source, $S.Outside | Out-Null
        for ($i = 0; $i -lt $rng.Next(2, 6); $i++) { [IO.File]::WriteAllText((Join-Path $S.Outside "keep$i.txt"), "do not touch $($rng.Next())") }
        $S.OutsideFingerprint = Get-TreeFingerprint $S.Outside
        $S.Ctx = New-ToolchainContext -RepoRoot $repo -NoPreexisting
        $names = @{}
        for ($i = 0; $i -lt $rng.Next(2, 5); $i++) {
            do { $n = 'pkg' + $rng.Next(1000, 9999) } while ($names.ContainsKey($n))
            $names[$n] = $true
            $S.Packages += New-FakePackage -Sandbox $S -Rng $rng -Name $n
        }
        Save-Manifest $S $S.Packages

        $sw = [Diagnostics.Stopwatch]::StartNew()
        $r = @(Install-Toolchain -Ctx $S.Ctx)
        Assert-True (@($r | Where-Object { $_.Changed }).Count -eq $S.Packages.Count) 'fresh install did not install every package'
        Assert-AllInstalled $S; Assert-NoStaging $S
        $results += [pscustomobject]@{ Scenario = 'fresh-install'; Passed = $true; Ms = $sw.ElapsedMilliseconds; Detail = "packages=$($S.Packages.Count)" }

        foreach ($name in @($Scenarios.Keys | Sort-Object { $rng.Next() })) {
            $sw = [Diagnostics.Stopwatch]::StartNew()
            try {
                $detail = & $Scenarios[$name] $S $rng
                Assert-True ((Get-TreeFingerprint $S.Outside) -eq $S.OutsideFingerprint) 'files outside the tools root changed'
                $results += [pscustomobject]@{ Scenario = $name; Passed = $true; Ms = $sw.ElapsedMilliseconds; Detail = [string]$detail }
            } catch {
                $results += [pscustomobject]@{ Scenario = $name; Passed = $false; Ms = $sw.ElapsedMilliseconds; Detail = $_.Exception.Message }
                throw
            }
        }

        $sw = [Diagnostics.Stopwatch]::StartNew()
        $null = Uninstall-Toolchain -Ctx $S.Ctx -All -Purge
        Assert-True (-not (Test-Path -LiteralPath $S.Ctx.ToolsRoot)) 'tools root still exists after -All -Purge'
        $props = Get-Props $S
        Assert-True ($props['toolchain.installed'] -eq '') 'properties still list installed packages'
        Assert-True ([IO.File]::ReadAllText($S.Ctx.ListPath) -match 'Nothing is currently installed') 'software list not emptied'
        Assert-True ((Get-TreeFingerprint $S.Outside) -eq $S.OutsideFingerprint) 'files outside the tools root changed'
        $results += [pscustomobject]@{ Scenario = 'remove-everything'; Passed = $true; Ms = $sw.ElapsedMilliseconds; Detail = 'tools root deleted' }
    } finally {
        $script:LastRunResults = $results
        for ($i = 0; $i -lt 20 -and (Test-Path -LiteralPath $root); $i++) {
            try { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction Stop } catch { Start-Sleep -Milliseconds 250 }
        }
    }
}

if ($Seed -eq 0) { $Seed = [Environment]::TickCount -band 0x7FFFFFFF }
$master = New-Object System.Random($Seed)
Write-Host "Toolchain tests | master seed: $Seed | goal: $RequiredConsecutivePasses consecutive passes (max $MaxRuns runs)`n"
$runs = @(); $consecutive = 0
for ($run = 1; $run -le $MaxRuns; $run++) {
    $runSeed = $master.Next()
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $passed = $true; $err = ''
    try { Invoke-OneRun $runSeed } catch { $passed = $false; $err = $_.Exception.Message + "`n" + $_.ScriptStackTrace }
    if ($passed) { $consecutive++ } else { $consecutive = 0 }
    $runs += [pscustomobject]@{ Run = $run; Seed = $runSeed; Passed = $passed; Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1); Consecutive = $consecutive; Error = $err; Scenarios = $script:LastRunResults }
    Write-Host ("Run {0,2} seed {1,10}  {2}  {3,5}s  consecutive={4}" -f $run, $runSeed, $(if ($passed) { 'PASS' } else { 'FAIL' }), [math]::Round($sw.Elapsed.TotalSeconds, 1), $consecutive)
    foreach ($s in $script:LastRunResults) { Write-Host ("        {0} {1,-38} {2,6}ms  {3}" -f $(if ($s.Passed) { 'ok  ' } else { 'FAIL' }), $s.Scenario, $s.Ms, $s.Detail) }
    if (-not $passed) { Write-Host "        error: $err" }
    if ($consecutive -ge $RequiredConsecutivePasses) { break }
    if (-not $passed -and -not $ContinueOnFailure) { break }
}
$ok = $consecutive -ge $RequiredConsecutivePasses
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$reportPath = Join-Path $ResultsDir ('toolchain-' + (Get-Date).ToString('yyyyMMdd-HHmmss') + '.json')
[pscustomobject]@{
    Suite = 'toolchain'; Timestamp = (Get-Date).ToString('o'); PowerShell = $PSVersionTable.PSVersion.ToString()
    MasterSeed = $Seed; RequiredConsecutivePasses = $RequiredConsecutivePasses; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Runs = $runs
} | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "`nResult: $(if ($ok) { 'PASS' } else { 'FAIL' }) ($consecutive consecutive passes). Report: $reportPath"
if ($ok) { exit 0 } else { exit 1 }
