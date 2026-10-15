#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PREFIX/bin/applets"
export TMPDIR="$PREFIX/tmp"
export LANG=C.UTF-8
export PD_FORCE_NO_COLORS=true

image='ubuntu:resolute-20260912'
container='ha-ubuntu'
container_dir="$PREFIX/var/lib/proot-distro/containers/$container"

mkdir -p "$HOME/setup-logs"
log_file="$HOME/setup-logs/02-install-ubuntu.log"
exec > >(tee -a "$log_file") 2>&1

printf '\n=== Ubuntu PRoot install started: %s ===\n' "$(date -Iseconds)"
printf 'Image: %s\nContainer: %s\n' "$image" "$container"

if [[ ! -d "$container_dir/rootfs" ]]; then
    proot-distro install "$image" --name "$container" --architecture aarch64
else
    printf 'Container already exists; skipping image extraction.\n'
fi

printf '\nContainer inventory:\n'
proot-distro list
printf '\nGuest verification:\n'
proot-distro login "$container" -- /bin/sh -c \
    'cat /etc/os-release; printf "Architecture: "; dpkg --print-architecture; python3 --version || true'
printf '=== Ubuntu PRoot install completed: %s ===\n' "$(date -Iseconds)"
