#!/bin/bash
# CarOS deploy script — build APK and push to Mekede MN X20 Pro via WiFi ADB
# Usage: ./deploy.sh [device_ip:port]
# Example: ./deploy.sh 192.168.1.100:5555

set -e

IP="${1:-192.168.1.100:5555}"
PACKAGE="com.caros"
ACTIVITY="com.caros.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "=== CarOS Deploy ==="
echo "Target: $IP"
echo ""

# Build
echo "[1/4] Building CarOS..."
./gradlew assembleDebug --build-cache --parallel
echo "Build OK"
echo ""

# Connect
echo "[2/4] Connecting to $IP..."
adb connect "$IP"
sleep 1

# Check connection
if ! adb -s "$IP" get-state > /dev/null 2>&1; then
    echo "ERROR: Cannot connect to $IP"
    echo "Make sure WiFi ADB is enabled: adb tcpip 5555"
    exit 1
fi
echo "Connected"
echo ""

# Install
echo "[3/4] Installing APK..."
adb -s "$IP" install -r -d "$APK_PATH"
echo "Install OK"
echo ""

# Launch
echo "[4/4] Launching CarOS..."
adb -s "$IP" shell am start -n "${PACKAGE}/.MainActivity" \
    --activity-clear-top --activity-single-top
echo ""

echo "=== CarOS deployed and launched ==="
echo "Device: $IP"
echo "Package: $PACKAGE"
echo ""
echo "Useful commands:"
echo "  adb -s $IP logcat -s CarOS:V    # filter CarOS logs"
echo "  adb -s $IP shell dumpsys window  # window state"
echo "  adb -s $IP shell top             # CPU usage"
