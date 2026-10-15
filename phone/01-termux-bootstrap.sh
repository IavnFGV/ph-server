#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PREFIX/bin/applets"
export TMPDIR="$PREFIX/tmp"
export LANG=C.UTF-8
export DEBIAN_FRONTEND=noninteractive

mkdir -p "$HOME/setup-logs"
log_file="$HOME/setup-logs/01-termux-bootstrap.log"
exec > >(tee -a "$log_file") 2>&1

printf '\n=== Termux bootstrap started: %s ===\n' "$(date -Iseconds)"
printf 'Architecture: %s\n' "$(uname -m)"
printf 'Prefix: %s\n' "$PREFIX"

apt-get update
# The GitHub APK bootstrap contains generated default config files. On the
# first upgrade, prefer the current signed package defaults instead of waiting
# for an interactive conffile prompt. Later server configs live under HOME.
dpkg --force-confnew --configure -a
apt-get -o Dpkg::Options::=--force-confnew --fix-broken install -y
apt-get -o Dpkg::Options::=--force-confnew dist-upgrade -y
# apt itself may replace sources.list during the first bootstrap upgrade.
apt-get update
apt-get install -y proot-distro

printf '\nInstalled versions:\n'
dpkg-query -W -f='${Package}\t${Version}\n' termux-tools apt proot proot-distro 2>/dev/null || true
printf '=== Termux bootstrap completed: %s ===\n' "$(date -Iseconds)"
