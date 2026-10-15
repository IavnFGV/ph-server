[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\.tools'),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$downloadUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$platformToolsPath = Join-Path $destinationPath 'platform-tools'
$adbPath = Join-Path $platformToolsPath 'adb.exe'

if ((Test-Path -LiteralPath $adbPath) -and -not $Force) {
    Write-Host "Platform Tools already present: $platformToolsPath"
    & $adbPath version
    exit 0
}

New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    'lg-v50s-platform-tools-' + [guid]::NewGuid().ToString('N')
)
$archivePath = Join-Path $temporaryRoot 'platform-tools.zip'
$extractPath = Join-Path $temporaryRoot 'extracted'

try {
    New-Item -ItemType Directory -Force -Path $temporaryRoot, $extractPath | Out-Null
    Write-Host "Downloading official Android SDK Platform Tools from $downloadUrl"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath

    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath
    Write-Host "Downloaded SHA-256: $($hash.Hash)"

    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractPath -Force
    $extractedPlatformTools = Join-Path $extractPath 'platform-tools'
    if (-not (Test-Path -LiteralPath (Join-Path $extractedPlatformTools 'adb.exe'))) {
        throw 'Downloaded archive does not contain platform-tools\adb.exe.'
    }

    if (Test-Path -LiteralPath $platformToolsPath) {
        Remove-Item -LiteralPath $platformToolsPath -Recurse -Force
    }
    Move-Item -LiteralPath $extractedPlatformTools -Destination $platformToolsPath
    & $adbPath version
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

