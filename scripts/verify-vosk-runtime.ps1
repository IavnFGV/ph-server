[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.tools\platform-tools\adb.exe'
$aapt2 = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\34.0.0\aapt2.exe'
$apk = Join-Path $projectRoot `
    'android\voice-color-station\artifacts\voice-color-station-debug.apk'
$logDirectory = Join-Path $projectRoot 'logs\windows'
$logPath = Join-Path $logDirectory 'vosk-runtime-verification.log'
$package = 'local.ivand.voicecolorstation'

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
Start-Transcript -Path $logPath -Append | Out-Null
try {
    foreach ($required in @($adb,$aapt2,$apk)) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "Missing verification dependency: $required"
        }
    }
    if ((& $adb get-state 2>$null | Out-String).Trim() -ne 'device') {
        throw 'Exactly one authorized ADB device must be connected.'
    }

    Write-Host '=== APK identity and permissions ==='
    & $aapt2 dump badging $apk | Select-String 'package:|sdkVersion:|native-code:'
    & $aapt2 dump permissions $apk
    Get-FileHash -Algorithm SHA256 -LiteralPath $apk |
        Select-Object Algorithm,Hash,Path

    Write-Host '=== Runtime process and foreground service ==='
    & $adb shell pidof $package
    & $adb shell dumpsys activity services $package |
        Select-String 'ServiceRecord|isForeground|foregroundNoti|crashCount'

    Write-Host '=== Microphone AppOps ==='
    & $adb shell appops get $package RECORD_AUDIO

    Write-Host '=== Effective notification status ==='
    & $adb shell dumpsys notification --noredact |
        Select-String 'android.text=String \((Жду|Слушаю|Выполнено|Ошибка|Не удалось)'

    Write-Host '=== Recent relevant logcat ==='
    & $adb logcat -d -t 500 |
        Select-String "$package|Vosk|vosk|AndroidRuntime|FATAL EXCEPTION" |
        Select-Object -Last 120
}
finally {
    Stop-Transcript | Out-Null
}
