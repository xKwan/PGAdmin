<#
.SYNOPSIS
    Fails if a tracked text file contains control characters (NUL, BEL, backspace, ...), which have slipped into
    sources and docs before (finding P7-4).

.DESCRIPTION
    Checks every file git tracks (plus new, untracked files that aren't ignored), except known binary types. Tab,
    line feed and carriage return are allowed. Exit 0 when clean, 1 with a list of offending files and offsets.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\Test-TextFiles.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..'))
$binary = @('.png', '.jpg', '.jpeg', '.gif', '.ico', '.woff', '.woff2', '.ttf', '.zip', '.jar', '.dll', '.exe', '.pdf')

$files = @(& git -C $repoRoot ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'git ls-files failed' }
$bad = @()
foreach ($rel in $files) {
    if ($binary -contains [IO.Path]::GetExtension($rel).ToLowerInvariant()) { continue }
    $path = Join-Path $repoRoot $rel
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $bytes = [IO.File]::ReadAllBytes($path)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $b = $bytes[$i]
        if ($b -lt 32 -and $b -ne 9 -and $b -ne 10 -and $b -ne 13) {
            $bad += ('{0}: byte 0x{1:X2} at offset {2}' -f $rel, $b, $i)
            break
        }
    }
}
if ($bad.Count -gt 0) {
    $bad | ForEach-Object { Write-Host "  control character: $_" }
    Write-Host "$($bad.Count) file(s) contain control characters."
    exit 1
}
Write-Host "text check: $($files.Count) files, no control characters"
exit 0
