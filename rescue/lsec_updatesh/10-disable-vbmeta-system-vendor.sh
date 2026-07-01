#!/system/bin/sh
# ============================================================
#  MEKEDE MN X20 Pro  —  TIER 1 FIX  (SPRÁVNÝ skript!)
#  Vypnout dm-verity pro system + vendor  →  spraví dm-2
# ------------------------------------------------------------
#  ⚠ POZOR: NEZAMĚŇ s wipe-metadata/userdata skriptem! Ten jen
#     dělá factory reset a dm-2 (=system) NEspraví. Na USB nech
#     JEN tenhle soubor (jako 8581lsec.sh) + vbmeta_disabled.img.
#
#  Použití:
#    na USB:  lsec_updatesh/8581lsec.sh          (tento soubor)
#             lsec_updatesh/vbmeta_disabled.img  (4096 B, flags=3)
#    vlož USB → RST → FYT „Android update" proběhne →
#    „update sucess! Pls remove device" → vytáhni USB → RST.
#
#  Co dělá:
#    1) Zachytí mapu partitions + kernel log z minulého bootu
#       ZPĚT na USB (a /cache) — kdyby to nenabootovalo, máme data
#       hned, bez dalšího kola.
#    2) Přepíše vbmeta_system + vbmeta_vendor prázdným vbmeta →
#       odebere hashtree descriptor → verity se pro /system
#       nenastaví → dm-2 (root/system) se vytvoří → boot projde.
#
#  Discovery partitions: `grep -l PARTNAME ... | xargs dirname |
#  xargs basename` — přesně metoda, kterou používá tovární lsec
#  skript tohoto zařízení (ověřeno ze zachyceného trace), takže
#  nezávisí na /dev/block/by-name.
#
#  FYT executor: každý řádek = samostatný `sh -c`. Proto je každá
#  operace (i s proměnnou) na JEDNOM řádku. stdout je potlačen →
#  výstup jde do souboru. `reboot` nefunguje → končí sync+sleep.
# ============================================================

D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; grep "PARTNAME=" /sys/class/block/*/uevent > "$D/parts.txt" 2>/dev/null; ls -la /dev/block/by-name > "$D/byname.txt" 2>/dev/null; cat /proc/cmdline > "$D/cmdline.txt" 2>/dev/null; cat /sys/fs/pstore/console-ramoops-0 > "$D/lastlog.txt" 2>/dev/null; cat /proc/last_kmsg > "$D/last_kmsg.txt" 2>/dev/null
f=$(find /storage /mnt /udisk /cache -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=vbmeta_system" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
f=$(find /storage /mnt /udisk /cache -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=vbmeta_vendor" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
sync
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; grep -l "PARTNAME=vbmeta_system" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if=/dev/block/{} bs=256 count=1 of="$D/after_vbmeta_system.bin" 2>/dev/null; grep -l "PARTNAME=vbmeta_vendor" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if=/dev/block/{} bs=256 count=1 of="$D/after_vbmeta_vendor.bin" 2>/dev/null
sync
sleep 8
# Po „update sucess" vytáhni USB, RST. Když nenabootuje, pošli mi
# z USB: parts.txt, lastlog.txt/last_kmsg.txt, after_vbmeta_*.bin
