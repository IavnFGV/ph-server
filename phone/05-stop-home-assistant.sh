#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PREFIX/bin/applets"
export TMPDIR="$PREFIX/tmp"
export PD_FORCE_NO_COLORS=true

container=ha-ubuntu
mkdir -p "$HOME/setup-logs"
exec > >(tee -a "$HOME/setup-logs/05-stop-home-assistant.log") 2>&1

printf '\n=== Home Assistant stop requested: %s ===\n' "$(date -Iseconds)"
proot-distro kill "$container" || printf 'No active Home Assistant PRoot session.\n'
