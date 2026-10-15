[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.tools\platform-tools\adb.exe'
$outputDirectory = Join-Path $projectRoot 'logs\phone'

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb.exe not found: $adb"
}
if ((& $adb get-state 2>$null | Out-String).Trim() -ne 'device') {
    throw 'Exactly one authorized ADB device must be connected.'
}

$termuxHome = '/data/data/com.termux/files/home'
$termuxPrefix = '/data/data/com.termux/files/usr'
$rootfs = "$termuxPrefix/var/lib/proot-distro/containers/ha-ubuntu/rootfs"
$files = [ordered]@{
    "$termuxHome/setup-logs/01-termux-bootstrap.log" = '01-termux-bootstrap.log'
    "$termuxHome/setup-logs/02-install-ubuntu.log" = '02-install-ubuntu.log'
    "$termuxHome/setup-logs/03-install-home-assistant.log" = '03-install-home-assistant.log'
    "$rootfs/var/log/ha-setup/03-install-home-assistant.log" = '03-ubuntu-install-home-assistant.log'
    "$termuxHome/setup-logs/04-start-home-assistant.log" = '04-start-home-assistant.log'
    "$termuxHome/setup-logs/05-stop-home-assistant.log" = '05-stop-home-assistant.log'
    "$rootfs/srv/homeassistant/runtime.log" = 'home-assistant-runtime.log'
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

foreach ($entry in $files.GetEnumerator()) {
    $source = $entry.Key
    $destination = Join-Path $outputDirectory $entry.Value
    $temporary = "/data/local/tmp/codex-ha-$($entry.Value)"

    & $adb shell run-as com.termux /system/bin/test -f $source
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Phone log not found, skipped: $source"
        continue
    }

    & $adb shell "rm -f '$temporary'; touch '$temporary'; chmod 666 '$temporary'"
    if ($LASTEXITCODE -ne 0) { throw "Cannot prepare temporary file: $temporary" }

    try {
        & $adb shell run-as com.termux /system/bin/dd "if=$source" "of=$temporary"
        if ($LASTEXITCODE -ne 0) { throw "Cannot stage phone log: $source" }
        & $adb pull $temporary $destination
        if ($LASTEXITCODE -ne 0) { throw "Cannot export phone log: $source" }
    }
    finally {
        & $adb shell rm -f $temporary | Out-Null
    }
}

Get-ChildItem -LiteralPath $outputDirectory -File |
    Sort-Object Name |
    Select-Object Name,Length,LastWriteTime
