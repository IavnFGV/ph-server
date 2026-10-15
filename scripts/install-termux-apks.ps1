[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $projectRoot '.tools\platform-tools\adb.exe'
$apkDirectory = Join-Path $projectRoot '.tools\termux-apks'
$termux = Join-Path $apkDirectory 'termux-app_v0.118.3+github-debug_universal.apk'
$boot = Join-Path $apkDirectory 'termux-boot-app_v0.8.1+github.debug.apk'

$expected = @{
    $termux = '7600078440C3C34EF050BC009B00FC3215CB87EC4A449E01A696F74CF4249DB2'
    $boot = '97E6D336F05B59F7CB25E8EFA7173E0A6E665F3022B75C6D55A69B6399ECACC8'
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw 'ADB is missing. Run install-platform-tools.ps1 first.'
}
if ((& $adb get-state 2>&1 | Out-String).Trim() -ne 'device') {
    throw 'Exactly one authorized ADB device must be connected.'
}

foreach ($apk in @($termux, $boot)) {
    if (-not (Test-Path -LiteralPath $apk)) {
        throw "Missing APK: $apk. Run download-termux-apks.ps1 first."
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
    if ($actual -ne $expected[$apk]) {
        throw "SHA-256 mismatch for $(Split-Path $apk -Leaf)."
    }
}

foreach ($apk in @($termux, $boot)) {
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) {
        throw "ADB installation failed for $(Split-Path $apk -Leaf)."
    }
}

Write-Host 'Installed Termux 0.118.3 and Termux:Boot 0.8.1.'
