[CmdletBinding()]
param(
    [string]$AdbPath = (Join-Path $PSScriptRoot '..\.tools\platform-tools\adb.exe')
)

$ErrorActionPreference = 'Stop'
$adb = [System.IO.Path]::GetFullPath($AdbPath)
if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb.exe not found: $adb"
}

$state = (& $adb get-state 2>$null).Trim()
if ($state -ne 'device') {
    throw "ADB device is not ready (state: $state)."
}

function Invoke-SafeAdbShell {
    param(
        [Parameter(Mandatory)] [string]$Title,
        [Parameter(Mandatory)] [string]$Command
    )

    Write-Output "## $Title"
    Write-Output ''
    Write-Output '```text'
    # adb shell accepts the whole string as a remote shell program. Supplying
    # a separate `sh -c` loses quoting on Windows for multiline commands.
    $output = & $adb shell $Command 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $output) {
        $output = "Command unavailable or access denied (exit $LASTEXITCODE)."
    }
    $output | ForEach-Object {
        $line = [string]$_
        # Defense in depth. The allowlisted commands should not emit these values.
        $line = $line -replace '(?i)(serial(no|number)?\s*[:=]\s*)\S+', '$1<redacted>'
        $line = $line -replace '(?i)(android[_ -]?id\s*[:=]\s*)\S+', '$1<redacted>'
        $line = $line -replace '\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b', '<redacted-mac>'
        Write-Output $line
    }
    Write-Output '```'
    Write-Output ''
}

Write-Output '# LG V50S: неразрушающая диагностика'
Write-Output ''
Write-Output ('Собрано: {0} (Europe/Kiev)' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
Write-Output ''
Write-Output 'Идентификаторы устройства намеренно не запрашивались.'
Write-Output ''

Invoke-SafeAdbShell 'Идентификация устройства' @'
printf 'manufacturer='; getprop ro.product.manufacturer
printf 'model='; getprop ro.product.model
printf 'device='; getprop ro.product.device
printf 'product='; getprop ro.product.name
printf 'hardware='; getprop ro.hardware
printf 'board_platform='; getprop ro.board.platform
printf 'abi='; getprop ro.product.cpu.abi
printf 'abi_list='; getprop ro.product.cpu.abilist
'@

Invoke-SafeAdbShell 'Android и прошивка' @'
printf 'android='; getprop ro.build.version.release
printf 'sdk='; getprop ro.build.version.sdk
printf 'security_patch='; getprop ro.build.version.security_patch
printf 'build_id='; getprop ro.build.id
printf 'build_type='; getprop ro.build.type
printf 'lg_software_version='; getprop ro.lge.swversion
printf 'lge_factory_version='; getprop ro.lge.factoryversion
'@

Invoke-SafeAdbShell 'Ядро' @'
uname -a
cat /proc/version 2>/dev/null
'@

Invoke-SafeAdbShell 'Verified Boot и блокировка загрузчика (свойства Android)' @'
for p in ro.boot.verifiedbootstate ro.boot.flash.locked ro.boot.vbmeta.device_state ro.boot.veritymode ro.oem_unlock_supported sys.oem_unlock_allowed ro.boot.secureboot; do
  printf '%s=' "$p"
  getprop "$p"
done
'@

Invoke-SafeAdbShell 'SELinux и текущий пользователь shell' @'
id
getenforce 2>/dev/null || true
printf 'selinux_enforce='; cat /sys/fs/selinux/enforce 2>/dev/null || echo unavailable
'@

Invoke-SafeAdbShell 'Признаки root и Magisk без повышения привилегий' @'
printf 'su_path='; command -v su 2>/dev/null || echo not-found
printf 'magisk_path='; command -v magisk 2>/dev/null || echo not-found
pm list packages com.topjohnwu.magisk 2>/dev/null || true
'@

Invoke-SafeAdbShell 'Память' @'
free -h 2>/dev/null || head -n 20 /proc/meminfo
'@

Invoke-SafeAdbShell 'Хранилище' @'
df -h /data /system /storage/emulated 2>/dev/null || df -h
'@

Invoke-SafeAdbShell 'Поддерживаемые файловые системы' @'
cat /proc/filesystems
'@

Invoke-SafeAdbShell 'Основные точки монтирования' @'
mount | grep -E ' on /( |system |system_root |vendor |product |data |storage/emulated )| type (overlay|cgroup|cgroup2) '
'@

Invoke-SafeAdbShell 'Namespaces текущего процесса' @'
for n in /proc/self/ns/*; do printf '%s -> ' "${n##*/}"; readlink "$n" 2>/dev/null || echo unavailable; done
'@

Invoke-SafeAdbShell 'cgroups' @'
echo '[proc/cgroups]'
cat /proc/cgroups 2>/dev/null || echo unavailable
echo '[proc/self/cgroup]'
cat /proc/self/cgroup 2>/dev/null || echo unavailable
echo '[cgroup mounts]'
mount | grep -E ' type cgroup2? ' || true
'@

Invoke-SafeAdbShell 'OverlayFS' @'
grep -w overlay /proc/filesystems 2>/dev/null || echo 'overlay not listed in /proc/filesystems'
if [ -r /proc/config.gz ]; then
  zcat /proc/config.gz | grep -E '^CONFIG_OVERLAY_FS=|^CONFIG_OVERLAY_FS_' || true
else
  echo '/proc/config.gz unavailable without additional access'
fi
'@

Invoke-SafeAdbShell 'Аккумулятор' @'
dumpsys battery | grep -E '^  (AC powered|USB powered|Wireless powered|status|health|level|scale|voltage|temperature|technology|max charging current|max charging voltage|charge counter)'
for f in capacity status health temp voltage_now current_now charge_full charge_full_design cycle_count; do
  if [ -r "/sys/class/power_supply/battery/$f" ]; then
    printf '%s=' "$f"
    cat "/sys/class/power_supply/battery/$f"
  fi
done
'@

Invoke-SafeAdbShell 'Тепловое состояние' @'
dumpsys thermalservice 2>/dev/null | head -n 80 || echo unavailable
'@

