[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\.tools\lg-usb-driver'),
    [switch]$UseVerifiedMirror,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$officialUrl = 'https://tool.cdn.gdms.lge.com/dn/downloader.dev?fileKey=UW00120120425'
$mirrorUrl = 'https://octoplusbox.com/files/drivers/LGMobileDriver_WHQL_Ver_4.8.0.exe'
$downloadUrl = if ($UseVerifiedMirror) { $mirrorUrl } else { $officialUrl }
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$installerPath = Join-Path $destinationPath 'LGMobileDriver.exe'

New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null

if ((Test-Path -LiteralPath $installerPath) -and -not $Force) {
    Write-Host "Installer already present: $installerPath"
}
else {
    if ($UseVerifiedMirror) {
        Write-Warning 'Downloading an archived copy from a third-party mirror. Authenticode verification is mandatory.'
    }
    else {
        Write-Host 'Downloading LG Universal Mobile Driver from the official LG CDN'
    }
    Invoke-WebRequest -Uri $downloadUrl -OutFile $installerPath
}

$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $installerPath
$signature = Get-AuthenticodeSignature -LiteralPath $installerPath
$file = Get-Item -LiteralPath $installerPath

Write-Host "Path: $installerPath"
Write-Host "Size: $($file.Length) bytes"
Write-Host "SHA-256: $($hash.Hash)"
Write-Host "Signature status: $($signature.Status)"
Write-Host "Signer: $($signature.SignerCertificate.Subject)"

if ($signature.Status -ne 'Valid') {
    throw "LG driver installer has no valid Authenticode signature: $($signature.Status)"
}

if ($signature.SignerCertificate.Subject -notmatch 'LG Electronics') {
    throw "Unexpected installer signer: $($signature.SignerCertificate.Subject)"
}

Write-Output $installerPath

