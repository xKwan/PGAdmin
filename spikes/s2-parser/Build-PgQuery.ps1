<#
.SYNOPSIS
    Spike S2: builds libpg_query (PostgreSQL parser as a C library) into a Windows x64 DLL with the project-local Zig
    compiler. No network access; no Visual Studio needed.

.DESCRIPTION
    Inputs come from the project toolchain (see configuration.properties):
      zig.home              Zig compiler (zig cc = clang + bundled MinGW-w64 headers and libraries)
      libpg_query-src.home  libpg_query source, pinned by SHA256 in toolchain\software.lock.json
    The source tree is never modified. Objects and the DLL are written to build\ (git-ignored). The script records the
    DLL's SHA256 in build\pg_query.dll.sha256 so the artefact can be verified after it is copied into an air gap.

    The build is reproducible: the same source and Zig version give a byte-identical DLL, wherever the source folder
    is. Source paths are mapped to a fixed prefix (-ffile-prefix-map). Zig has no deterministic-link switch for
    Windows DLLs, so after linking the script zeroes exactly the fields that vary between links: the COFF header
    timestamp, the debug-directory timestamps and the CodeView (RSDS) GUID. Nothing else is changed.

.PARAMETER SourceDir
    libpg_query source folder. Default: libpg_query-src.home from configuration.properties.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s2-parser\Build-PgQuery.ps1
#>
[CmdletBinding()]
param(
    [string]$OutDir,
    [string]$SourceDir,
    [switch]$Clean
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $OutDir) { $OutDir = Join-Path $here 'build' }

function Get-ConfigValue {
    param([string]$Key)
    $line = Get-Content (Join-Path $repoRoot 'configuration.properties') | Where-Object { $_ -match ('^' + [regex]::Escape($Key) + '=') } | Select-Object -First 1
    if (-not $line) { throw "configuration.properties has no '$Key'. Run scripts\toolchain\Install-Toolchain.ps1 first." }
    $value = $line.Substring($Key.Length + 1).Trim()
    if (-not [IO.Path]::IsPathRooted($value)) { $value = Join-Path $repoRoot $value }
    return $value
}

$zig = Join-Path (Get-ConfigValue 'zig.home') 'zig.exe'
$src = if ($SourceDir) { (Resolve-Path $SourceDir).Path } else { Get-ConfigValue 'libpg_query-src.home' }
if (-not (Test-Path $zig)) { throw "zig.exe not found at $zig" }
if (-not (Test-Path (Join-Path $src 'pg_query.h'))) { throw "libpg_query source not found at $src" }

if ($Clean -and (Test-Path $OutDir)) { Remove-Item -Recurse -Force $OutDir }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$objDir = Join-Path $OutDir 'obj'
New-Item -ItemType Directory -Force -Path $objDir | Out-Null

# Same source list and flags as libpg_query's own Makefile (Windows_NT branch).
$sources = @()
$sources += Get-ChildItem (Join-Path $src 'src') -Filter '*.c' -File | ForEach-Object FullName
$sources += Get-ChildItem (Join-Path $src 'src\postgres') -Filter '*.c' -File | ForEach-Object FullName
$sources += @('vendor\xxhash\xxhash.c', 'vendor\upb\upb.c', 'vendor\upb\third_party\utf8_range\utf8_range.c', 'protobuf\pg_query.upb_minitable.c') |
    ForEach-Object { Join-Path $src $_ }

$includes = @('.', 'vendor', 'vendor\upb', 'vendor\upb\third_party\utf8_range', 'src\include', 'src\postgres\include', 'src\postgres\include\port\win32') |
    ForEach-Object { '-I' + (Join-Path $src $_) }
$cflags = @('-target', 'x86_64-windows-gnu', '-O2', '-fno-strict-aliasing', '-fwrapv', '-w',
    "`"-ffile-prefix-map=$src=libpg_query`"") + $includes
$ldflags = @()

# Objects compiled with different flags or from a different source folder are discarded.
$stampFile = Join-Path $objDir 'flags.stamp'
$stamp = (@((& $zig version)) + $cflags + $ldflags) -join "`n"
if ((Test-Path $stampFile) -and ((Get-Content -Raw $stampFile) -ne $stamp)) {
    Get-ChildItem $objDir -File | Remove-Item -Force
}
Set-Content -Path $stampFile -Value $stamp -NoNewline -Encoding UTF8

$env:ZIG_LOCAL_CACHE_DIR = Join-Path $OutDir 'zig-cache'
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $OutDir 'zig-global-cache'

$sw = [Diagnostics.Stopwatch]::StartNew()
$objects = New-Object System.Collections.Generic.List[string]
$failed = @()
$i = 0
foreach ($file in $sources) {
    $i++
    $rel = $file.Substring($src.Length + 1)
    $obj = Join-Path $objDir (($rel -replace '[\\/]', '_') -replace '\.c$', '.o')
    $objects.Add($obj)
    if ((Test-Path $obj) -and ((Get-Item $obj).LastWriteTimeUtc -ge (Get-Item $file).LastWriteTimeUtc)) { continue }
    $errFile = "$obj.err"
    $p = Start-Process -FilePath $zig -ArgumentList (@('cc', '-c') + $cflags + @('-o', "`"$obj`"", "`"$file`"")) `
        -NoNewWindow -Wait -PassThru -RedirectStandardError $errFile -RedirectStandardOutput "$obj.out"
    if ($p.ExitCode -ne 0) { $failed += $rel; Write-Host "FAILED: $rel"; Get-Content $errFile | Select-Object -First 15 | ForEach-Object { Write-Host "  $_" } }
    if ($i % 25 -eq 0) { Write-Host ("compiled {0}/{1} ({2:n0}s)" -f $i, $sources.Count, $sw.Elapsed.TotalSeconds) }
}
if ($failed.Count -gt 0) { throw "$($failed.Count) source file(s) failed to compile." }

$dll = Join-Path $OutDir 'pg_query.dll'
$rsp = Join-Path $OutDir 'link.rsp'
# Response file: the object list is too long for one command line.
Set-Content -Path $rsp -Encoding ASCII -Value ($objects | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' })
$p = Start-Process -FilePath $zig -ArgumentList (@('cc', '-target', 'x86_64-windows-gnu', '-shared', '-o', "`"$dll`"", "@`"$rsp`"", '-Wl,--export-all-symbols') + $ldflags) `
    -NoNewWindow -Wait -PassThru -RedirectStandardError (Join-Path $OutDir 'link.err') -RedirectStandardOutput (Join-Path $OutDir 'link.out')
if ($p.ExitCode -ne 0) { Get-Content (Join-Path $OutDir 'link.err') | Select-Object -First 30; throw 'Linking pg_query.dll failed.' }

# Zero the link-time fields that differ between otherwise identical links (see .DESCRIPTION).
function Clear-PeVolatileFields {
    param([string]$Path)
    $b = [IO.File]::ReadAllBytes($Path)
    $pe = [BitConverter]::ToInt32($b, 0x3C)
    if ([Text.Encoding]::ASCII.GetString($b, $pe, 4) -ne "PE`0`0") { throw 'Not a PE file.' }
    [Array]::Clear($b, $pe + 8, 4)                                  # COFF TimeDateStamp
    $sections = [BitConverter]::ToUInt16($b, $pe + 6)
    $optSize = [BitConverter]::ToUInt16($b, $pe + 20)
    $opt = $pe + 24
    if ([BitConverter]::ToUInt16($b, $opt) -ne 0x20B) { throw 'Expected a PE32+ (x64) image.' }
    if ([BitConverter]::ToUInt32($b, $opt + 64) -ne 0) { throw 'PE checksum is set; refusing to patch.' }
    $debugRva = [BitConverter]::ToUInt32($b, $opt + 112 + 6 * 8)
    $debugSize = [BitConverter]::ToUInt32($b, $opt + 112 + 6 * 8 + 4)
    if ($debugRva -eq 0) { return }
    $secTable = $opt + $optSize
    $debugOff = -1
    for ($i = 0; $i -lt $sections; $i++) {
        $s = $secTable + $i * 40
        $va = [BitConverter]::ToUInt32($b, $s + 12); $vsize = [BitConverter]::ToUInt32($b, $s + 8); $raw = [BitConverter]::ToUInt32($b, $s + 20)
        if ($debugRva -ge $va -and $debugRva -lt $va + $vsize) { $debugOff = $raw + ($debugRva - $va) }
    }
    if ($debugOff -lt 0) { throw 'Debug directory not inside any section.' }
    for ($e = 0; $e -lt [int]($debugSize / 28); $e++) {
        $d = $debugOff + $e * 28
        [Array]::Clear($b, $d + 4, 4)                               # debug entry TimeDateStamp
        $type = [BitConverter]::ToUInt32($b, $d + 12)
        $ptr = [BitConverter]::ToUInt32($b, $d + 24)
        if ($type -eq 2 -and [Text.Encoding]::ASCII.GetString($b, $ptr, 4) -eq 'RSDS') {
            [Array]::Clear($b, $ptr + 4, 16)                        # CodeView GUID
        }
    }
    [IO.File]::WriteAllBytes($Path, $b)
}
Clear-PeVolatileFields -Path $dll

$hash = (Get-FileHash $dll -Algorithm SHA256).Hash
Set-Content -Path "$dll.sha256" -Value "$hash  pg_query.dll" -Encoding ASCII
Write-Host ("Built {0} ({1:n0} KB) in {2:n0}s. SHA256 {3}" -f $dll, ((Get-Item $dll).Length / 1KB), $sw.Elapsed.TotalSeconds, $hash)
