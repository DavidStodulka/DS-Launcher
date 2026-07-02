#!/system/bin/sh
# ============================================================
#  ROOT flash v3 — patched boot + disable vbmeta, S LOGOVÁNÍM
#  Zkopíruj jako 8581lsec.sh (a kopii 7862lsec.sh) na trigger USB.
#  Na USB: magisk_patched*.img + vbmeta_disabled.img + fyt_build.prop
#          + trigger (lsec6316update + config.txt)
#
#  PROČ v3: v1 (uevent grep discovery) nechala boot NEpatchnutý
#  (Magisk: Installed N/A) — discovery glob nejspíš vrátil prázdno,
#  takže dd dostalo prázdný cíl a neproběhlo. v3:
#    1) LOGUJE vše na USB (přečteš na PC BEZ nutnosti bootu/ADB)
#    2) cíle přes /dev/block/by-name/  (ověřeno: OEM skript i reflash
#       tuto cestu používaly a fungovaly)
#    3) vrací fyt_build.prop → zapne ADB 5555 pro další ladění
#    4) zachytává návratový kód dd → víme, jestli zápis prošel
#
#  NEsahá na /system, super, vbmeta_system, vbmeta_vendor → žádný
#  dm-2 bootloop (system zůstává stock, jeho verity projde).
#
#  FYT executor: každý řádek = samostatný `sh -c`. Proto se cesta k
#  logu ($D) i cíl re-resolvují na KAŽDÉM řádku (proměnné nepřetrvávají).
# ============================================================

D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; echo "=== lsec root v3 start ===" > "$D/lsec.log"; date >> "$D/lsec.log" 2>&1; echo "--- by-name ---" >> "$D/lsec.log"; ls -la /dev/block/by-name >> "$D/lsec.log" 2>&1
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; p=$(find /storage /mnt /udisk -name fyt_build.prop 2>/dev/null | head -1); [ -n "$p" ] && { cp -f "$p" /oem/app/fyt_build.prop 2>>"$D/lsec.log"; chmod 644 /oem/app/fyt_build.prop 2>>"$D/lsec.log"; echo "prop copied from $p rc=$?" >> "$D/lsec.log"; }
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; f=$(find /storage /mnt /udisk -name "magisk_patched*.img" 2>/dev/null | head -1); echo "patched=$f" >> "$D/lsec.log"; [ -n "$f" ] && { dd if="$f" of=/dev/block/by-name/boot 2>>"$D/lsec.log"; echo "boot flash rc=$?" >> "$D/lsec.log"; }
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); echo "vbmeta=$v" >> "$D/lsec.log"; [ -n "$v" ] && { dd if="$v" of=/dev/block/by-name/vbmeta 2>>"$D/lsec.log"; echo "vbmeta rc=$?" >> "$D/lsec.log"; dd if="$v" of=/dev/block/by-name/vbmeta_bak 2>>"$D/lsec.log"; echo "vbmeta_bak rc=$?" >> "$D/lsec.log"; }
sync
sleep 8
# Po doběhu: vytáhni USB → na PC otevři lsec.log. Chceme vidět:
#   by-name obsahuje "boot", "vbmeta"; patched=<cesta>; "boot flash rc=0".
# Pak RST → boot s rootem. Kdyby by-name bylo prázdné, pošli mi lsec.log
# (přepneme cíl na uevent/absolutní mmcblk podle výpisu).
