# LG V50S: базовая неразрушающая диагностика

Собрано 2026-10-15 в часовом поясе Europe/Kiev. Серийный номер, IMEI,
Android ID, MAC-адреса и IP-адреса не запрашивались и не сохранялись.

## Устройство и ОС

```text
manufacturer=LGE
model=LM-V510N
device=mh2lm
product=mh2lm_5g
hardware=mh2lm
board_platform=msmnile
abi=arm64-v8a
abi_list=arm64-v8a,armeabi-v7a,armeabi
android=12
sdk=31
security_patch=2022-09-01
build_id=SKQ1.211103.001
build_type=user
lg_software_version=V510N40f
build_description=mh2lm_5g-user 12 SKQ1.211103.001 SKT_KR release-keys
active_slot=_a
```

## Ядро

```text
Linux localhost 4.14.190-perf+ #1 SMP PREEMPT Tue Nov 15 13:44:11 KST 2022 aarch64
Linux version 4.14.190-perf+ (jenkins@MCSBS9R20)
(clang version 10.0.7 for Android NDK) #1 SMP PREEMPT
```

## Загрузчик и Verified Boot

```text
ro.boot.verifiedbootstate=orange
ro.boot.flash.locked=0
ro.boot.vbmeta.device_state=unlocked
ro.boot.veritymode=enforcing
sys.oem_unlock_allowed=0
```

Вывод: загруженная система сообщает о разблокированном загрузчике. Значение
`sys.oem_unlock_allowed=0` не отменяет текущего состояния; это разрешение на
инициацию разблокировки из Android, тогда как `flash.locked=0` и состояние
VBMeta описывают уже загруженную цепочку.

## Root и SELinux

```text
shell_uid=2000
shell_context=u:r:shell:s0
SELinux=Enforcing
selinux_enforce=1
su_path=not-found
magisk_path=not-found
su -c id: inaccessible or not found (exit 127)
```

Вывод: доступного root и признаков установленного Magisk нет.

## Память и хранилище

```text
RAM total=7.2 GiB
RAM available including cache≈1.2 GiB at collection time
Swap total=3.6 GiB
Swap used≈592 MiB at collection time
/data size=215 GiB, used=4.3 GiB, available=210 GiB
/system root size=3.9 GiB, used=91%
```

Основные разделы Android используют `ext4`; пользовательское представление
хранилища доступно через FUSE.

## Namespaces, cgroups и OverlayFS

Доступные записи `/proc/self/ns`:

```text
cgroup
mnt
net
uts
```

Отсутствуют как минимум `pid`, `ipc` и `user`. Смонтированы:

```text
cgroup v1: blkio, cpu, cpuset, memory, schedtune
cgroup v2: /sys/fs/cgroup (список cgroup.controllers пуст)
```

Контроллеры `devices`, `pids` и `freezer` не обнаружены. `overlay` отсутствует
в `/proc/filesystems`; `/proc/config.gz` обычному shell недоступен.

Доступные утилиты:

```text
unshare=/system/bin/unshare
nsenter=/system/bin/nsenter
iptables=/system/bin/iptables
ip6tables=/system/bin/ip6tables
nft=not-found
```

Предварительный вывод: штатное ядро не предоставляет наблюдаемый набор
namespaces, cgroup-контроллеров и OverlayFS, необходимый стандартному Docker.
Даже после получения root Docker/containerd на этом ядре не следует считать
поддерживаемым без модификации ядра и отдельных функциональных тестов.

## Аккумулятор и температура

```text
AC powered=true
status=Charging
health=Good
level=47%
temperature=28.4 C
voltage≈3.917 V
current_now≈282714 (единицы sysfs производителя)
charge_full=4027000
charge_full_design=4027000
cycle_count=0
Thermal Status=0
Thermal HAL Ready=false
```

Показание `cycle_count=0` нельзя интерпретировать как доказательство нового
аккумулятора: прошивка могла не вести или сбросить этот счётчик.

