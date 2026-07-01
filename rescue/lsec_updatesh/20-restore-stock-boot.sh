#!/system/bin/sh
# ============================================================
#  MEKEDE MN X20 Pro  —  obnova STOCK boot.img  (jen v případě potřeby)
# ------------------------------------------------------------
#  Použij POUZE pokud si nejsi jistý, že boot je opravdu stock
#  (podle poznámek už jsi originál přes dd vracel — pak přeskoč).
#
#  Použití:
#    zkopíruj jako:  lsec_updatesh/8581lsec.sh
#    na USB dej ORIGINÁL:  lsec_updatesh/stock-boot.img
#        z Mekede\6316_1\boot.img
#        SHA1 = 75900f451e43be53ba5334229c0e242551b68053
#
#  ⚠ NEPOUŽÍVEJ new-boot.img z USB — to je Magisk-patched (=bootloop)!
#     Proto se tento skript schválně jmenuje stock-boot.img, ať se
#     omylem nesáhne po patchnutém souboru.
# ============================================================

for f in /storage/*/lsec_updatesh/stock-boot.img /mnt/media_rw/*/lsec_updatesh/stock-boot.img /storage/usb*/lsec_updatesh/stock-boot.img; do [ -f "$f" ] || continue; dd if="$f" of=/dev/block/by-name/boot; break; done
sync
for d in /storage/*/lsec_updatesh /mnt/media_rw/*/lsec_updatesh /storage/usb*/lsec_updatesh /cache; do [ -d "$d" ] || continue; dd if=/dev/block/by-name/boot bs=1M count=32 of="$d/after_boot.img" 2>/dev/null; done
sync
sleep 8
# after_boot.img si můžeš na PC ověřit (sha1sum by měl sedět se
# stock boot.img, resp. s jeho prvními 32 MB podle velikosti boot).
