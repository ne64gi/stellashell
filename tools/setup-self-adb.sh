#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "======================================"
echo "  Android Self-ADB Setup"
echo "======================================"
echo

# 1. Termux packages

echo "[1/5] Updating Termux packages..."
pkg update -y
pkg upgrade -y

echo
echo "[2/5] Installing dependencies..."
pkg install -y android-tools nmap

echo
echo "ADB:"
adb version | head -n 1

# 2. Pairing

echo
echo "======================================"
echo " Wireless Debugging Pairing"
echo "======================================"
echo
echo "Androidで以下を開いてください:"
echo
echo "  開発者向けオプション"
echo "    -> ワイヤレスデバッグ"
echo "    -> ペア設定コードによるデバイスのペア設定"
echo

read -rp "Pairing port (例: 37125): " PAIR_PORT

if [[ -z "$PAIR_PORT" ]]; then
    echo "Pairing skipped."
else
    echo
    echo "6桁のペア設定コードを聞かれます。"
    adb pair "127.0.0.1:${PAIR_PORT}"
fi

# 3. Find Wireless ADB port

echo
echo "[3/5] Searching localhost for Wireless ADB..."

# Android Wireless Debuggingはランダムなhigh portを使用するため
# localhostをnmapで探索
OPEN_PORTS=$(
    nmap -p 30000-50000 127.0.0.1 --open 2>/dev/null |
        awk '/^[0-9]+\/tcp/ {split($1,a,"/"); print a[1]}'
)

ADB_PORT=""

for PORT in $OPEN_PORTS; do
    echo "Trying 127.0.0.1:${PORT}..."
    adb connect "127.0.0.1:${PORT}" >/dev/null 2>&1 || true

    if adb -s "127.0.0.1:${PORT}" get-state 2>/dev/null | grep -qx "device"; then
        ADB_PORT="$PORT"
        break
    fi
done

# 自動検出できなかった場合は手入力
if [[ -z "$ADB_PORT" ]]; then
    echo
    echo "Wireless ADB portを自動検出できませんでした。"
    echo "ワイヤレスデバッグ画面に表示されている"
    echo "'IPアドレスとポート' のポート番号を入力してください。"
    echo
    read -rp "ADB port: " ADB_PORT
    adb connect "127.0.0.1:${ADB_PORT}"
fi

echo
echo "[+] Wireless ADB found:"
echo "    127.0.0.1:${ADB_PORT}"

# 4. Switch adbd to TCP/5555

echo
echo "[4/5] Switching adbd to TCP port 5555..."
adb -s "127.0.0.1:${ADB_PORT}" tcpip 5555
sleep 2

echo
echo "Connecting to localhost ADB (until adbd restarts)..."
adb connect 127.0.0.1:5555

# 5. Verify

echo
echo "[5/5] Verifying Self-ADB..."
STATE=$(adb -s 127.0.0.1:5555 get-state 2>/dev/null || true)

if [[ "$STATE" == "device" ]]; then
    MODEL=$(adb -s 127.0.0.1:5555 shell getprop ro.product.model | tr -d '\r')
    DEVICE=$(adb -s 127.0.0.1:5555 shell getprop ro.product.device | tr -d '\r')
    ANDROID=$(adb -s 127.0.0.1:5555 shell getprop ro.build.version.release | tr -d '\r')

    echo
    echo "======================================"
    echo " SELF-ADB READY"
    echo "======================================"
    echo
    echo "Model   : $MODEL"
    echo "Device  : $DEVICE"
    echo "Android : $ANDROID"
    echo
    echo "ADB:"
    echo "  127.0.0.1:5555"
    echo
    echo "Test:"
    echo "  adb -s 127.0.0.1:5555 shell"
    echo
else
    echo
    echo "======================================"
    echo " SELF-ADB SETUP FAILED"
    echo "======================================"
    echo
    echo "adb devices:"
    adb devices
    echo
    exit 1
fi
