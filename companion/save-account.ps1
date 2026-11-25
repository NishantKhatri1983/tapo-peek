# Save the Tapo local Camera Account once, encrypted for the current Windows user.
#
# Usage (Windows PowerShell 5.1 or PowerShell 7):
#   .\companion\save-account.ps1 -CameraHost CAMERA_LAN_IP -Username CAMERA_USERNAME
#
# The password is typed into PowerShell's own secure prompt, so it is never
# echoed, never in command history, and never passed on the command line.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CameraHost,
    [Parameter(Mandatory = $true)][string]$Username,
    [string]$Python = (Join-Path $PSScriptRoot '.runtime\python\python.exe')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "Python not found at '$Python'. Pass -Python <path-to-python.exe>."
}

$secure = Read-Host 'Camera account password' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

if ([string]::IsNullOrEmpty($plain)) {
    throw 'Empty password; nothing was saved.'
}

try {
    $plain | & $Python -B -m companion save-account --host $CameraHost --username $Username --password-stdin
    exit $LASTEXITCODE
}
finally {
    Remove-Variable plain -ErrorAction SilentlyContinue
}
