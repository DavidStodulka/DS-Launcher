#!/system/bin/sh
# ============================================================
#  ROOT flash v4 — patched boot + disable vbmeta
#  ROBUSTNÍ: pevný název, kontrola velikosti, plný výpis USB do logu
#  Zkopíruj jako 8581lsec.sh (a kopii 7862lsec.sh) na trigger USB.
#
#  NA USB MUSÍ BÝT (kořen):
#    lsec6316update, config.txt          (trigger — spouští update flow)
#    boot_patched.img                    (Magiskem patchnutý STOCK boot)
#    vbmeta_disabled.img                 (prázdný vbmeta, 4096 B)
#    fyt_build.prop                      (zapne ADB 5555)
#    lsec_updatesh/{8581lsec.sh,7862lsec.sh}   (tento skript)
#
#  PROČ v4: minule "update success" byl (skript BĚŽEL), ale root ne —
#  na USB CHYBĚL patched boot → find nic nenašel → boot se nepatchnul.
#  v4 to ODHALÍ: loguje CELÝ obsah USB + velikost patched image, a boot
#  flashne JEN když je image přítomný a > 1 MB (jinak by dd zapsalo
#  nesmysl). Vše čteš z lsec.log na PC — bez bootu, bez ADB.
#
#  Pevný název boot_patched.img: Magisk generuje dlouhé jméno s čísly
#  a Windows kopie umí přidat " (1)"/mezery → find je pak vrtkavý.
#  Proto přejmenuj patched boot NA USB přesně na boot_patched.img.
#
#  NEsahá na /system, super, vbmeta_system, vbmeta_vendor → žádný dm-2.
#  Cíle přes /dev/block/by-name/ (ověřená cesta OEM skriptu i reflashe).
#  FYT executor: každý řádek = samostatný sh -c → $D/$f se re-resolvují.
# ============================================================

D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; U=$(dirname "$D" 2>/dev/null); echo "=== lsec root v4 ===" > "$D/lsec.log"; date >> "$D/lsec.log" 2>&1; echo "--- USB root ($U) ---" >> "$D/lsec.log"; ls -la "$U" >> "$D/lsec.log" 2>&1; echo "--- by-name ---" >> "$D/lsec.log"; ls -la /dev/block/by-name >> "$D/lsec.log" 2>&1
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; p=$(find /storage /mnt /udisk -name fyt_build.prop 2>/dev/null | head -1); echo "prop=$p" >> "$D/lsec.log"; [ -n "$p" ] && { cp -f "$p" /oem/app/fyt_build.prop 2>>"$D/lsec.log"; chmod 644 /oem/app/fyt_build.prop 2>>"$D/lsec.log"; echo "prop rc=$?" >> "$D/lsec.log"; }
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; f=$(find /storage /mnt /udisk -name "boot_patched.img" 2>/dev/null | head -1); [ -z "$f" ] && f=$(find /storage /mnt /udisk -name "magisk_patched*.img" 2>/dev/null | head -1); sz=$(wc -c < "$f" 2>/dev/null); echo "patched=$f size=$sz" >> "$D/lsec.log"; if [ -n "$f" ] && [ "${sz:-0}" -gt 1000000 ]; then dd if="$f" of=/dev/block/by-name/boot 2>>"$D/lsec.log"; echo "boot flash rc=$?" >> "$D/lsec.log"; else echo "BOOT SKIP: patched image chybi nebo maly" >> "$D/lsec.log"; fi
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); echo "vbmeta=$v" >> "$D/lsec.log"; if [ -n "$v" ]; then dd if="$v" of=/dev/block/by-name/vbmeta 2>>"$D/lsec.log"; echo "vbmeta rc=$?" >> "$D/lsec.log"; dd if="$v" of=/dev/block/by-name/vbmeta_bak 2>>"$D/lsec.log"; echo "vbmeta_bak rc=$?" >> "$D/lsec.log"; else echo "VBMETA SKIP: img chybi" >> "$D/lsec.log"; fi
sync
sleep 8
# Po doběhu: vytáhni USB → na PC otevři lsec.log. MUSÍ tam být:
#   "patched=/…/boot_patched.img size=<milióny>"  a  "boot flash rc=0".
# Když vidíš "BOOT SKIP" → patched boot NEBYL na USB (to je ta minulá chyba).
# Když by-name nemá "boot" → pošli mi lsec.log, přepneme cíl.
