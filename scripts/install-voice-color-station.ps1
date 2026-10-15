[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.tools\platform-tools\adb.exe'
$apk = Join-Path $projectRoot 'android\voice-color-station\artifacts\voice-color-station-debug.apk'
$logDirectory = Join-Path $projectRoot 'logs\windows'
$logPath = Join-Path $logDirectory 'voice-color-station-install.log'

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
Start-Transcript -Path $logPath -Append | Out-Null
try {
    if (-not (Test-Path -LiteralPath $adb)) { throw "adb.exe not found: $adb" }
    if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }
    if ((& $adb get-state 2>$null | Out-String).Trim() -ne 'device') {
        throw 'Exactly one authorized ADB device must be connected.'
    }

    Get-FileHash -Algorithm SHA256 -LiteralPath $apk | Select-Object Algorithm,Hash
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed: $LASTEXITCODE" }
    & $adb shell am start -n 'local.ivand.voicecolorstation/.MainActivity'
    if ($LASTEXITCODE -ne 0) { throw "Cannot start Voice Color Station: $LASTEXITCODE" }
}
finally {
    Stop-Transcript | Out-Null
}
