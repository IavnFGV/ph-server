[CmdletBinding()]
param([switch]$DefaultUsbBackend)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.tools\platform-tools\adb.exe'
$log = Join-Path $root 'logs\windows\lg-adb-backend.log'
Start-Transcript -Path $log -Append | Out-Null
try {
    & $adb version
    & $adb kill-server
    if ($DefaultUsbBackend) {
        [Environment]::SetEnvironmentVariable('ADB_USB_LEGACY', $null, 'Process')
        Write-Host 'Starting ADB with default USB backend (rollback).'
    } else {
        $env:ADB_USB_LEGACY = '1'
        Write-Host 'Starting ADB with documented ADB_USB_LEGACY=1 compatibility backend.'
    }
    & $adb start-server
    if ($LASTEXITCODE -ne 0) { throw 'ADB server failed to start.' }
    & $adb devices -l
    Write-Host 'If unauthorized: unlock LG and approve this computer on the USB debugging prompt.'
} finally { Stop-Transcript | Out-Null }
