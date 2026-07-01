#!/system/bin/sh
# ============================================================
#  ROOT flash přes lsec: patched boot + disable top-level vbmeta
#  Zkopíruj jako 8581lsec.sh na trigger USB.
#  Na USB musí být:  magisk_patched.img  +  vbmeta_disabled.img
#
#  NEsahá na /system, vbmeta_system ani vbmeta_vendor — jen boot +
#  top-level vbmeta/vbmeta_bak. Proto se dm-2 bootloop NEopakuje
#  (system zůstává stock → jeho verity projde).
#
#  Discovery přes grep -l PARTNAME (ověřená metoda tohoto zařízení).
#  Před během si nech dumpnout current boot (40-dump-boot.sh) jako zálohu.
# ============================================================
f=$(find /storage /mnt /udisk -name magisk_patched.img 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=boot$" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$v" ] && grep -l "PARTNAME=vbmeta$" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$v" of=/dev/block/{}
v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$v" ] && grep -l "PARTNAME=vbmeta_bak$" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$v" of=/dev/block/{}
sync
sleep 8
# Po doběhu: vytáhni USB, reboot. Bootne s rootem. Kdyby bootloop →
# 20-restore-stock-boot.sh, nebo reflash USB.
