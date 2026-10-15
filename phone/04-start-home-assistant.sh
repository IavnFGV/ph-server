#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PREFIX/bin/applets"
export TMPDIR="$PREFIX/tmp"
export LANG=C.UTF-8
export PD_FORCE_NO_COLORS=true

container=ha-ubuntu
url=http://127.0.0.1:8123/
mkdir -p "$HOME/setup-logs"
exec > >(tee -a "$HOME/setup-logs/04-start-home-assistant.log") 2>&1

printf '\n=== Home Assistant start requested: %s ===\n' "$(date -Iseconds)"
existing_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --max-time 3 "$url" || true)"
if [[ "$existing_code" != '000' ]]; then
    printf 'Home Assistant is already reachable with HTTP %s.\n' "$existing_code"
else
    # Clear a stale dedicated session that exists but does not answer HTTP.
    proot-distro kill "$container" >/dev/null 2>&1 || true
    proot-distro login --isolated --user homeassistant --detach "$container" -- \
        /bin/bash -c \
        'exec /srv/homeassistant/venv/bin/hass -c /srv/homeassistant/config >>/srv/homeassistant/runtime.log 2>&1'
fi

for attempt in $(seq 1 36); do
    code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --max-time 3 "$url" || true)"
    if [[ "$code" != '000' ]]; then
        printf 'HTTP ready: %s (attempt %s)\n' "$code" "$attempt"
        proot-distro ps
        exit 0
    fi
    sleep 5
done

printf 'Home Assistant did not answer within 180 seconds.\n' >&2
proot-distro ps || true
exit 1
