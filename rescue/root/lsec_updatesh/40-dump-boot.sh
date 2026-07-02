#!/system/bin/sh
# ============================================================
#  Dump AKTUÁLNÍHO boot z jednotky na USB (pro Magisk patch)
#  Zkopíruj jako 8581lsec.sh na trigger USB (viz COWORK — potřebuje
#  lsec6316update + config.txt, aby se update flow spustil).
#  NIC neflashuje, jen čte boot → current-boot.img na USB.
# ============================================================
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; grep -l "PARTNAME=boot$" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if=/dev/block/{} of="$D/current-boot.img"
sync
sleep 5
# Pak: current-boot.img z USB → Magisk app → Patch a File.
