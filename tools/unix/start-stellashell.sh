#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Bash 3.2+ (including macOS). adb and scrcpy must be installed separately.
set -euo pipefail
address=''; resolution=1920x1080; dpi=160; keyboard=uhid; ime=local
setup=false; batch=false; fullscreen=true
usage() {
    cat <<'HELP'
StellaShell — Linux / macOS session launcher
  ./start-stellashell.sh                       Interactive wireless setup
  ./start-stellashell.sh --address IP:PORT      Try a paired endpoint first
  --setup          Always show the pairing/connection menu
  --non-interactive  Fail instead of prompting
  --resolution WIDTHxHEIGHT  --dpi NUMBER
  --keyboard uhid|sdk  --ime local|fallback|hide  --windowed
ADB and SCRCPY environment variables may specify executable paths.
No tcpip 5555 command is issued. Shizuku must be set up separately.
HELP
}
while (($#)); do
    case "$1" in
        --address|--resolution|--dpi|--keyboard|--ime)
            (($# >= 2)) || { echo "Missing value: $1" >&2; exit 2; }
            case "$1" in
                --address) address=$2;; --resolution) resolution=$2;; --dpi) dpi=$2;;
                --keyboard) keyboard=$2;; --ime) ime=$2;;
            esac; shift 2;;
        --setup) setup=true; shift;; --non-interactive) batch=true; shift;;
        --windowed) fullscreen=false; shift;;
        -h|--help) usage; exit 0;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2;;
    esac
done
adb=${ADB:-adb}; scrcpy=${SCRCPY:-scrcpy}
command -v "$adb" >/dev/null || { echo 'Install Android platform-tools (adb).' >&2; exit 1; }
command -v "$scrcpy" >/dev/null || { echo 'Install scrcpy (virtual-display support required).' >&2; exit 1; }
[[ $resolution =~ ^[1-9][0-9]*x[1-9][0-9]*$ && $dpi =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid resolution / dpi.' >&2; exit 2; }
[[ $keyboard == uhid || $keyboard == sdk ]] || exit 2
[[ $ime == local || $ime == fallback || $ime == hide ]] || exit 2
valid_endpoint() {
    local endpoint=$1 port
    [[ $endpoint =~ ^([a-zA-Z0-9][a-zA-Z0-9.-]*|\[[a-fA-F0-9:]+\]):([0-9]{1,5})$ ]] || return 1
    port=${BASH_REMATCH[2]}; ((10#$port >= 1 && 10#$port <= 65535))
}
# Portable timeout: no GNU timeout dependency on macOS. Only this adb client is
# terminated, never the adb server or other sessions.
bounded_adb() {
    local pid guard code=0
    "$adb" "$@" & pid=$!
    (
        timer=
        trap 'if [[ -n $timer ]]; then kill "$timer" 2>/dev/null || :; wait "$timer" 2>/dev/null || :; fi; exit 0' TERM INT
        sleep 12 & timer=$!
        wait "$timer" || exit 0
        kill "$pid" 2>/dev/null || :
    ) >/dev/null 2>&1 & guard=$!
    wait "$pid" || code=$?
    kill "$guard" 2>/dev/null || :
    wait "$guard" 2>/dev/null || :
    return "$code"
}
connect_endpoint() {
    valid_endpoint "$1" || return 1
    bounded_adb connect "$1" || return 1
    local state
    state=$(bounded_adb -s "$1" get-state 2>/dev/null) || return 1
    [[ ${state//$'\r'/} == device ]]
}
read_endpoint() {
    local value
    while true; do
        printf '%s (IP:port, q = cancel): ' "$1" >&2
        IFS= read -r value || return 1
        [[ $value != q ]] || return 1
        if valid_endpoint "$value"; then endpoint=$value; return 0; fi
        echo 'Use host:port, port 1-65535. IPv6: [address]:port.' >&2
    done
}
ready=false
if ! $setup && connect_endpoint "$address"; then ready=true; fi
if ! $ready && $batch; then echo 'ADB unavailable; run without --non-interactive to pair/connect.' >&2; exit 1; fi
while ! $ready; do
    cat <<'MENU'

=== Android wireless debugging ===
Android 11+: Settings > Developer options > Wireless debugging > ON.
Use the same Wi-Fi or a network/VPN that can reach the phone.
1  Pair this computer (first time / pairing removed)
2  Already paired / TCP ADB ready: enter connection IP:port
q  Cancel
MENU
    printf 'Choose 1 / 2 / q: '
    IFS= read -r choice || exit 1
    case "$choice" in
        q) exit 1;;
        1)
            echo 'Phone: Pair device with pairing code. Keep the dialog open.'
            read_endpoint 'PAIRING address shown in the dialog' || exit 1
            echo 'Enter the six-digit code when adb asks. No pairing code is saved.'
            if ! "$adb" pair "$endpoint"; then echo 'Pairing failed; open a fresh pairing dialog and retry.'; continue; fi;;
        2) :;;
        *) continue;;
    esac
    echo 'Return to the main Wireless debugging screen: IP address & port.'
    echo 'Use its CONNECTION port, not the PAIRING port. No :5555 required.'
    read_endpoint 'CONNECTION address (or existing TCP :5555 endpoint)' || exit 1
    address=$endpoint
    if connect_endpoint "$address"; then ready=true
    else echo 'Not ready. Check the current port, network and phone authorization prompt.'; fi
done
printf '\nConnected: %s\n' "$address"
args=(-s "$address" "--keyboard=$keyboard" "--new-display=$resolution/$dpi"
    --no-vd-destroy-content "--display-ime-policy=$ime" --no-vd-system-decorations
    --start-app=net.fuyumori.stellashell)
if $fullscreen; then args+=(--fullscreen); fi
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ -f $script_dir/../windows/stellashell.png ]]; then export SCRCPY_ICON_PATH="$script_dir/../windows/stellashell.png"; fi
exec "$scrcpy" "${args[@]}"
