#!/bin/bash
set -Eeuo pipefail

export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8

log_dir=/var/log/ha-setup
mkdir -p "$log_dir"
exec > >(tee -a "$log_dir/03-install-home-assistant.log") 2>&1

printf '\n=== Home Assistant guest install started: %s ===\n' "$(date -Iseconds)"
printf 'Guest: %s\n' "$(. /etc/os-release; printf '%s %s' "$PRETTY_NAME" "$(dpkg --print-architecture)")"

apt-get update
apt-get upgrade -y
apt-get install -y --no-install-recommends \
    build-essential \
    ca-certificates \
    ffmpeg \
    libffi-dev \
    libjpeg-dev \
    libopenjp2-7-dev \
    libpcap0.8t64 \
    libssl-dev \
    libtiff-dev \
    libturbojpeg0 \
    pkg-config \
    python3.14 \
    python3.14-dev \
    python3.14-venv \
    zlib1g-dev

if ! id homeassistant >/dev/null 2>&1; then
    useradd --system --create-home --home-dir /srv/homeassistant --shell /bin/bash homeassistant
fi
install -d -o homeassistant -g homeassistant /srv/homeassistant/config

if [[ ! -x /srv/homeassistant/venv/bin/python ]]; then
    python3.14 -m venv /srv/homeassistant/venv
fi

/srv/homeassistant/venv/bin/python -m pip install --upgrade pip wheel
/srv/homeassistant/venv/bin/python -m pip install 'homeassistant==2026.10.1'

/srv/homeassistant/venv/bin/python -c \
    'import importlib.metadata as m; print("Home Assistant", m.version("homeassistant"))'
printf '=== Home Assistant guest install completed: %s ===\n' "$(date -Iseconds)"
