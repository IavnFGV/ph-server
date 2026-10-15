#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PREFIX/bin/applets"
export TMPDIR="$PREFIX/tmp"
export LANG=C.UTF-8
export PD_FORCE_NO_COLORS=true

container=ha-ubuntu
guest_script="$HOME/03-ubuntu-install-home-assistant.sh"

mkdir -p "$HOME/setup-logs"
exec > >(tee -a "$HOME/setup-logs/03-install-home-assistant.log") 2>&1

printf '\n=== Home Assistant orchestration started: %s ===\n' "$(date -Iseconds)"
if [[ ! -f "$guest_script" ]]; then
    printf 'Missing guest script: %s\n' "$guest_script" >&2
    exit 1
fi

proot-distro copy "$guest_script" "$container:/root/03-ubuntu-install-home-assistant.sh"
proot-distro login --isolated "$container" -- \
    /bin/bash /root/03-ubuntu-install-home-assistant.sh
printf '=== Home Assistant orchestration completed: %s ===\n' "$(date -Iseconds)"
