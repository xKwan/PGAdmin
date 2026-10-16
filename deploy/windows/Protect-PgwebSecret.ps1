#Requires -Version 5.1
<#
.SYNOPSIS
    Encrypts a secret with Windows DPAPI and prints it as a "dpapi:<base64>" value for pgweb.properties
    (audit.password, audit.hmacKey, audit.migrator.password, server.tls.keystorePassword).

.DESCRIPTION
    -Scope LocalMachine (default): any process on THIS server can decrypt it, so the config file's ACL is what
      protects it (Install-Pgweb.ps1 restricts it to SYSTEM, Administrators and the service account).
    -Scope CurrentUser: only the account that runs this script can decrypt it. Run it as the service account
      (not possible for a gMSA interactively; use LocalMachine there).
    The value works only on the machine (or for the user) it was made on. Make it on the target server.
    The secret is read without echo; it is never written to disk or to the console.

.EXAMPLE
    .\Protect-PgwebSecret.ps1                # prompts for the secret
    .\Protect-PgwebSecret.ps1 -NewHmacKey    # generates a new 48-byte random HMAC key and protects it
#>
[CmdletBinding()]
param(
    [ValidateSet('LocalMachine', 'CurrentUser')][string]$Scope = 'LocalMachine',
    [switch]$NewHmacKey
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
if ($NewHmacKey) {
    $bytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $plain = [Text.Encoding]::UTF8.GetBytes([Convert]::ToBase64String($bytes))
} else {
    $secure = Read-Host -AsSecureString -Prompt 'Secret'
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $plain = [Text.Encoding]::UTF8.GetBytes([Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
if ($plain.Length -eq 0) { throw 'Empty secret.' }
$scopeValue = [Security.Cryptography.DataProtectionScope]::$Scope
$protected = [Security.Cryptography.ProtectedData]::Protect($plain, $null, $scopeValue)
[Array]::Clear($plain, 0, $plain.Length)
Write-Output ('dpapi:' + [Convert]::ToBase64String($protected))
