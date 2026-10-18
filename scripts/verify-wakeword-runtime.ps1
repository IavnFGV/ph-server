[CmdletBinding()]
param(
    [ValidateSet('generic','personal-v2')][string]$ExpectedProfile = 'generic',
    [switch]$RequireBackgroundPermissions
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.tools\platform-tools\adb.exe'
$apk = Join-Path $root 'android\voice-color-station\artifacts\voice-color-station-debug.apk'
$log = Join-Path $root 'logs\windows\wakeword-runtime-verification.log'
$package = 'local.ivand.voicecolorstation'
Start-Transcript -Path $log -Append | Out-Null
try {
    if ((& $adb get-state 2>$null | Out-String).Trim() -ne 'device') { throw 'Connect exactly one authorized device.' }
    $processId = (& $adb shell pidof $package | Out-String).Trim()
    if (-not $processId) { throw 'Voice station is not running.' }
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $startup = & $adb logcat -d --pid=$processId -s Pomogator:V AndroidRuntime:E
        if ($startup -match 'Audio pipeline failed|FATAL EXCEPTION') { throw 'Audio pipeline failed during startup.' }
        if ($startup -match 'READY:') { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $readyDeadline)
    if (-not ($startup -match 'READY:')) { throw 'Timed out waiting for model and microphone startup.' }
    & $adb shell dumpsys package $package | Select-String 'versionCode=|versionName=' | ForEach-Object { Write-Host $_.Line }
    & $adb shell dumpsys activity services $package | Select-String 'ServiceRecord|isForeground|foregroundNoti' | ForEach-Object { Write-Host $_.Line }
    & $adb shell appops get $package RECORD_AUDIO
    $overlayState = (& $adb shell appops get $package SYSTEM_ALERT_WINDOW | Out-String)
    Write-Host $overlayState.Trim()
    $batteryWhitelist = & $adb shell dumpsys deviceidle whitelist | Where-Object {
        $_ -match "^(user|system),$([regex]::Escape($package)),"
    }
    Write-Host "Battery exemption currently present: $([bool]$batteryWhitelist)"
    $wakeLocks = & $adb shell dumpsys power | Select-String 'PARTIAL_WAKE_LOCK.*VoiceColorStation:microphone'
    $wakeLocks | ForEach-Object { Write-Host $_.Line }
    if (-not $wakeLocks) { throw 'Microphone CPU wake lock is not held.' }
    & $adb shell dumpsys meminfo $package | Select-String 'TOTAL PSS:|TOTAL RSS:|Java Heap:|Native Heap:' | ForEach-Object { Write-Host $_.Line }
    $events = & $adb logcat -d --pid=$processId -s Pomogator:V AndroidRuntime:E
    $events | Select-Object -Last 50
    if (-not ($events -match 'SELF_TEST passed')) { throw 'No successful ONNX self-test for this process.' }
    if (-not ($events -match 'READY:')) { throw 'Microphone pipeline did not become ready.' }
    if (-not ($events -match 'READY:.*preroll=0, ASR=after-wake')) { throw 'Post-wake-only ASR pipeline not confirmed.' }
    if (-not ($events -match 'READY:.*confirmFrames=3')) { throw 'Three-frame wake confirmation not confirmed.' }
    if ($RequireBackgroundPermissions -and
            ($overlayState -notmatch 'SYSTEM_ALERT_WINDOW: allow' -or -not $batteryWhitelist)) {
        throw 'Grant overlay and battery exemption, then repeat verification.'
    }
    $profiles = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'training/pomogator/android-profiles.json') | ConvertFrom-Json
    $expected = $profiles.$ExpectedProfile
    $identity = "profile=$ExpectedProfile; model=$($expected.model_sha256); threshold=$($expected.threshold)"
    if (-not ($events | Where-Object { $_.Contains('SELF_TEST passed') -and $_.Contains($identity) })) {
        throw "Installed wake profile does not match expected: $identity"
    }
    if ($events -match 'Audio pipeline failed|FATAL EXCEPTION') { throw 'Runtime error detected.' }
    Write-Host "APK SHA256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)"
} finally { Stop-Transcript | Out-Null }
