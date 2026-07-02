#!/system/bin/sh
# ============================================================
#  MEKEDE MN X20 Pro  —  lsec DIAGNOSTIKA  (Tier 0, NIC nemění)
# ------------------------------------------------------------
#  Použití:
#    zkopíruj tento soubor na USB jako:  lsec_updatesh/8581lsec.sh
#    spusť přes normální lsec/FYT update flow (RST → auto-recovery)
#
#  Co dělá:
#    Sesbírá kernel log z minulého bootu (kde je přesná chyba
#    fs_mgr / dm-2), tabulku partitions, by-name mapping, cmdline,
#    props a hlavičky vbmeta partitions. Vše zapíše ZPĚT na USB
#    (a do /cache) — přečteš na PC.
#
#  Proč zpět na USB:
#    FYT executor potlačuje stdout i stderr → na obrazovce nic
#    neuvidíš. Jediná cesta jak získat výstup je soubor.
#
#  FYT executor pravidlo:
#    Každý ŘÁDEK běží jako samostatný `sh -c "<řádek>"`.
#    Proměnné mezi řádky NEpřetrvávají. Proto je každá dávka
#    operací na JEDNOM řádku (proměnná $d žije jen v rámci řádku).
# ============================================================

for d in /storage/*/lsec_updatesh /mnt/media_rw/*/lsec_updatesh /storage/usb*/lsec_updatesh /cache; do [ -d "$d" ] || continue; cat /proc/partitions > "$d/diag_partitions.txt" 2>/dev/null; ls -la /dev/block/by-name > "$d/diag_byname.txt" 2>/dev/null; cat /proc/cmdline > "$d/diag_cmdline.txt" 2>/dev/null; getprop > "$d/diag_props.txt" 2>/dev/null; dmesg > "$d/diag_dmesg_now.txt" 2>/dev/null; done
for d in /storage/*/lsec_updatesh /mnt/media_rw/*/lsec_updatesh /storage/usb*/lsec_updatesh /cache; do [ -d "$d" ] || continue; cat /proc/last_kmsg > "$d/diag_last_kmsg.txt" 2>/dev/null; cat /sys/fs/pstore/console-ramoops-0 > "$d/diag_pstore_console.txt" 2>/dev/null; cat /sys/fs/pstore/dmesg-ramoops-0 > "$d/diag_pstore_dmesg.txt" 2>/dev/null; done
for d in /storage/*/lsec_updatesh /mnt/media_rw/*/lsec_updatesh /storage/usb*/lsec_updatesh /cache; do [ -d "$d" ] || continue; dd if=/dev/block/by-name/vbmeta        bs=256 count=1 of="$d/diag_vbmeta_head.bin"        2>/dev/null; dd if=/dev/block/by-name/vbmeta_system bs=256 count=1 of="$d/diag_vbmeta_system_head.bin" 2>/dev/null; dd if=/dev/block/by-name/vbmeta_vendor bs=256 count=1 of="$d/diag_vbmeta_vendor_head.bin" 2>/dev/null; dd if=/dev/block/by-name/super bs=512 count=16 of="$d/diag_super_head.bin" 2>/dev/null; done
sync
sleep 8
# Po doběhnutí: vytáhni USB → na PC otevři soubory diag_*.txt.
# Nejdůležitější je diag_pstore_console.txt / diag_last_kmsg.txt —
# tam je řádek s "Failed to open '/dev/block/dm-2'" a KONTEXT kolem
# (verity? avb? dm-linear/super metadata?). Pošli mi ho.
