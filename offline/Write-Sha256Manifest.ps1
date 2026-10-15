#Requires -Version 5.1
<#
.SYNOPSIS
    Writes a SHA-256 manifest for every file under -Root (sha256sum format: "<hash>  <relative/path>"), or, with
    -Verify, checks a tree against an existing manifest: every listed file must exist with the same hash and no
    unlisted file may be present. Used for release bundles and air-gap dependency bundles (ADR-0008, intake step 3/5).
    Exit code 0 on success, 1 on any mismatch.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][string]$Manifest,
    [switch]$Verify
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
$manifestFull = [IO.Path]::GetFullPath($Manifest)

function Get-Files {
    Get-ChildItem -LiteralPath $rootFull -Recurse -File -Force |
        Where-Object { $_.FullName -ne $manifestFull } |
        ForEach-Object { $_.FullName.Substring($rootFull.Length).Replace('\', '/') } |
        Sort-Object
}

if (-not $Verify) {
    $lines = foreach ($rel in Get-Files) {
        $h = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $rootFull $rel)).Hash.ToLowerInvariant()
        "$h  $rel"
    }
    [IO.File]::WriteAllLines($manifestFull, [string[]]@($lines), (New-Object Text.UTF8Encoding($false)))
    Write-Host "manifest: $(@($lines).Count) files -> $Manifest"
    exit 0
}

$expected = @{}
foreach ($line in [IO.File]::ReadAllLines($manifestFull)) {
    if (-not $line.Trim()) { continue }
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { Write-Host "malformed manifest line: $line"; exit 1 }
    $expected[$Matches[2]] = $Matches[1]
}
$problems = @()
foreach ($rel in $expected.Keys) {
    $p = Join-Path $rootFull $rel
    if (-not (Test-Path -LiteralPath $p)) { $problems += "missing: $rel"; continue }
    $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant()
    if ($h -ne $expected[$rel]) { $problems += "hash mismatch: $rel" }
}
foreach ($rel in Get-Files) { if (-not $expected.ContainsKey($rel)) { $problems += "not in manifest: $rel" } }
if ($problems.Count -gt 0) { $problems | ForEach-Object { Write-Host "  $_" }; Write-Host "manifest verification FAILED ($($problems.Count) problems)"; exit 1 }
Write-Host "manifest verified: $($expected.Count) files"
exit 0
