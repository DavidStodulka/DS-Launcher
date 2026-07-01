#!/system/bin/sh
# ============================================================
#  MEKEDE MN X20 Pro  —  TIER 1 FIX  (nejpravděpodobnější oprava)
#  Vypnout dm-verity pro system + vendor
# ------------------------------------------------------------
#  Použití:
#    zkopíruj tento soubor na USB jako:  lsec_updatesh/8581lsec.sh
#    na USB musí ležet:  lsec_updatesh/vbmeta_disabled.img
#        (prázdný vbmeta, 4096 B, avbtool --flags 3; ten co už máš)
#    spusť přes lsec/FYT update flow, počkej ~15 s, pak RST.
#
#  Co dělá a PROČ to funguje:
#    Přepíše vbmeta_system a vbmeta_vendor prázdným vbmeta.
#    Tím se z nich ODEBERE hashtree descriptor pro system/vendor.
#    → fs_mgr už nemá z čeho postavit dm-verity pro system
#    → dm-2 (root/system) se vytvoří jako čisté dm-linear
#    → boot projde i s Magiskem modifikovaným /system.
#
#    DŮLEŽITÉ (ověřeno z AVB specifikace):
#    DISABLE flagy v top-level vbmeta se NEDĚDÍ na chained
#    vbmeta_system / vbmeta_vendor. Proto NESTAČILO vypnout jen
#    vbmeta (p35) + vbmeta_bak (p36) — vbmeta_system pořád nesl
#    hash původního nezměněného systému → verity neshoda → dm-2
#    se nevytvořil → bootloop. Tenhle skript to dořeší.
#
#    Toto zařízení NEMÁ vbmeta_system_bak ani vbmeta_vendor_bak
#    (viz block_devices.txt) — stačí tyto dvě partitions.
#
#  FYT executor: každý řádek = samostatný `sh -c`. Každá operace
#  je proto na jednom řádku. by-name je dostupné (původní skript
#  ho používal). Cesta k USB se hledá globem (sdcard1/usb*/media_rw).
# ============================================================

for f in /storage/*/lsec_updatesh/vbmeta_disabled.img /mnt/media_rw/*/lsec_updatesh/vbmeta_disabled.img /storage/usb*/lsec_updatesh/vbmeta_disabled.img; do [ -f "$f" ] || continue; dd if="$f" of=/dev/block/by-name/vbmeta_system; dd if="$f" of=/dev/block/by-name/vbmeta_vendor; break; done
sync
for d in /storage/*/lsec_updatesh /mnt/media_rw/*/lsec_updatesh /storage/usb*/lsec_updatesh /cache; do [ -d "$d" ] || continue; dd if=/dev/block/by-name/vbmeta_system bs=256 count=1 of="$d/after_vbmeta_system.bin" 2>/dev/null; dd if=/dev/block/by-name/vbmeta_vendor bs=256 count=1 of="$d/after_vbmeta_vendor.bin" 2>/dev/null; done
sync
sleep 10
# after_vbmeta_*.bin = kontrola, že zápis proběhl (má odpovídat
# prvním 256 B vbmeta_disabled.img). Pak vytáhni USB a RST.
