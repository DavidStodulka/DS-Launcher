@echo off
REM ============================================================
REM  CarOS — one-click USB deploy (Windows)
REM
REM  Pripoj jednotku pres USB kabel a poklepej na tento soubor.
REM  Skript:
REM    1. najde pripojene USB zarizeni (adb)
REM    2. stahne nejnovejsi APK z GitHub Actions (gh CLI),
REM       nebo pouzije app-debug.apk vedle skriptu
REM    3. nainstaluje a spusti CarOS
REM
REM  Pozadavky: adb v PATH (platform-tools)
REM             gh CLI prihlasene (volitelne — jen pro stazeni APK)
REM ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set REPO=DavidStodulka/DS-Launcher
set BRANCH=claude/magical-lovelace-k3nrY
set PACKAGE=com.caros
set APK=app-debug.apk

echo === CarOS USB deploy ===
echo.

REM -- 1. adb k dispozici? --------------------------------------
where adb >nul 2>nul
if errorlevel 1 (
    echo CHYBA: adb nenalezeno. Nainstaluj Android platform-tools
    echo        a pridej je do PATH: https://developer.android.com/tools/releases/platform-tools
    pause & exit /b 1
)

REM -- 2. cekani na USB zarizeni --------------------------------
echo [1/4] Cekam na USB zarizeni... (pripoj kabel, povol USB ladeni)
adb wait-for-usb-device
for /f "skip=1 tokens=1" %%D in ('adb devices') do (
    if not "%%D"=="" set DEVICE=%%D& goto :found
)
:found
echo       Pripojeno: %DEVICE%
echo.

REM -- 3. ziskani APK -------------------------------------------
if exist "%APK%" (
    echo [2/4] Pouzivam lokalni %APK%
) else (
    where gh >nul 2>nul
    if errorlevel 1 (
        echo CHYBA: %APK% neexistuje a gh CLI neni nainstalovane.
        echo        Stahni APK rucne z GitHub Actions a uloz vedle skriptu,
        echo        nebo nainstaluj gh: https://cli.github.com
        pause & exit /b 1
    )
    echo [2/4] Stahuji nejnovejsi APK z GitHub Actions...
    for /f %%R in ('gh run list --repo %REPO% --branch %BRANCH% --workflow build.yml --status success --limit 1 --json databaseId --jq ".[0].databaseId"') do set RUN_ID=%%R
    if "!RUN_ID!"=="" (
        echo CHYBA: zadny uspesny build na vetvi %BRANCH%.
        pause & exit /b 1
    )
    if exist apk_download rmdir /s /q apk_download
    gh run download !RUN_ID! --repo %REPO% --pattern "caros-debug-*" --dir apk_download
    for /r apk_download %%F in (*.apk) do copy /y "%%F" "%APK%" >nul
    rmdir /s /q apk_download
    echo       Stazeno: %APK% (run !RUN_ID!)
)
echo.

REM -- 4. instalace ---------------------------------------------
echo [3/4] Instaluji CarOS...
adb -s %DEVICE% install -r -d "%APK%"
if errorlevel 1 (
    echo CHYBA: instalace selhala.
    pause & exit /b 1
)
echo.

REM -- 5. spusteni ----------------------------------------------
echo [4/4] Spoustim CarOS...
adb -s %DEVICE% shell am start -n %PACKAGE%/.MainActivity --activity-clear-top --activity-single-top
echo.
echo === CarOS nasazeno a spusteno ===
pause
