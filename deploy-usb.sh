#!/bin/bash
# ============================================================
#  CarOS — one-click USB deploy (Linux / macOS)
#
#  Připoj jednotku přes USB kabel a spusť: ./deploy-usb.sh
#  Skript:
#    1. najde připojené USB zařízení (adb)
#    2. stáhne nejnovější APK z GitHub Actions (gh CLI),
#       nebo použije app-debug.apk vedle skriptu
#    3. nainstaluje a spustí CarOS
#
#  Požadavky: adb v PATH (platform-tools)
#             gh CLI přihlášené (volitelné — jen pro stažení APK)
# ============================================================
set -e
cd "$(dirname "$0")"

REPO="DavidStodulka/DS-Launcher"
BRANCH="claude/magical-lovelace-k3nrY"
PACKAGE="com.caros"
APK="app-debug.apk"

echo "=== CarOS USB deploy ==="
echo ""

# -- 1. adb k dispozici? --------------------------------------
if ! command -v adb >/dev/null; then
    echo "CHYBA: adb nenalezeno. Nainstaluj Android platform-tools:"
    echo "       https://developer.android.com/tools/releases/platform-tools"
    exit 1
fi

# -- 2. čekání na USB zařízení --------------------------------
echo "[1/4] Čekám na USB zařízení… (připoj kabel, povol USB ladění)"
adb wait-for-usb-device
DEVICE=$(adb devices | awk 'NR==2 {print $1}')
echo "      Připojeno: $DEVICE"
echo ""

# -- 3. získání APK -------------------------------------------
if [[ -f "$APK" ]]; then
    echo "[2/4] Používám lokální $APK"
else
    if ! command -v gh >/dev/null; then
        echo "CHYBA: $APK neexistuje a gh CLI není nainstalované."
        echo "       Stáhni APK ručně z GitHub Actions a ulož vedle skriptu,"
        echo "       nebo nainstaluj gh: https://cli.github.com"
        exit 1
    fi
    echo "[2/4] Stahuji nejnovější APK z GitHub Actions…"
    RUN_ID=$(gh run list --repo "$REPO" --branch "$BRANCH" --workflow build.yml \
             --status success --limit 1 --json databaseId --jq '.[0].databaseId')
    if [[ -z "$RUN_ID" ]]; then
        echo "CHYBA: žádný úspěšný build na větvi $BRANCH."
        exit 1
    fi
    rm -rf apk_download
    gh run download "$RUN_ID" --repo "$REPO" --pattern "caros-debug-*" --dir apk_download
    find apk_download -name '*.apk' -exec cp {} "$APK" \;
    rm -rf apk_download
    echo "      Staženo: $APK (run $RUN_ID)"
fi
echo ""

# -- 4. instalace ---------------------------------------------
echo "[3/4] Instaluji CarOS…"
adb -s "$DEVICE" install -r -d "$APK"
echo ""

# -- 5. spuštění ----------------------------------------------
echo "[4/4] Spouštím CarOS…"
adb -s "$DEVICE" shell am start -n "$PACKAGE/.MainActivity" \
    --activity-clear-top --activity-single-top
echo ""
echo "=== CarOS nasazeno a spuštěno ==="
