[CmdletBinding()]
param(
    [switch]$ConfirmRemoval
)

$ErrorActionPreference = 'Stop'
if (-not $ConfirmRemoval) {
    throw 'Rollback deletes Termux application data. Re-run with -ConfirmRemoval only after backing up needed data.'
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $projectRoot '.tools\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    throw 'ADB is missing.'
}
if ((& $adb get-state 2>&1 | Out-String).Trim() -ne 'device') {
    throw 'Exactly one authorized ADB device must be connected.'
}

# Remove the plugin first, then the host application. Uninstalling com.termux
# deletes its private home directory and all future Home Assistant data there.
foreach ($package in @('com.termux.boot', 'com.termux')) {
    $installed = & $adb shell pm path $package 2>&1
    if (($installed | Out-String) -match '^package:') {
        & $adb shell am force-stop $package | Out-Null
        & $adb uninstall $package
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to uninstall $package."
        }
    } else {
        Write-Host "$package is not installed; skipping."
    }
}

Write-Host 'Termux:Boot and Termux have been removed.'
