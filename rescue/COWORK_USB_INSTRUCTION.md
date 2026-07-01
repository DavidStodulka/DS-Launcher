# Cowork — přesný pokyn: připrav USB (Tier 1, vypnout verity)

Vlož text v bloku níže do Coworku. Cowork má k dispozici kompletní složku
výrobce (`Mekede\6316_1\`, `Mekede\lsec_updatesh\`, `AllAppUpdate.bin`, …).

---

```
ÚKOL: Připrav mi USB klíč pro záchranu head unitu Mekede MN X20 Pro z bootloopu.
Máš přístup k mé složce s firmwarem výrobce (Mekede\6316_1\, Mekede\lsec_updatesh\,
AllAppUpdate.bin). Zeptej se mě, které písmeno disku je ten USB klíč, než začneš zapisovat.

CÍL USB (Tier 1): vypnout dm-verity pro system+vendor přepsáním vbmeta_system a
vbmeta_vendor prázdným vbmeta. Nic jiného na USB nedávej.

KROK 1 — USB naformátuj na FAT32 a v KOŘENI vytvoř složku:
    lsec_updatesh\

KROK 2 — do lsec_updatesh\ dej PŘESNĚ tyto DVA soubory a nic víc:

  (a) soubor  8581lsec.sh  s tímto přesným obsahem
      DŮLEŽITÉ: ulož jako UTF-8 BEZ BOM a s koncem řádků LF (Unix), NE CRLF!
      (Windows CRLF skript rozbije — každý řádek by dostal \r a dd selže.)
      Ověř, že v souboru nejsou žádné znaky \r (0x0D).

------------------------------------------------------------
#!/system/bin/sh
D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; grep "PARTNAME=" /sys/class/block/*/uevent > "$D/parts.txt" 2>/dev/null; cat /sys/fs/pstore/console-ramoops-0 > "$D/lastlog.txt" 2>/dev/null
f=$(find /storage /mnt /udisk /cache -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=vbmeta_system" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
f=$(find /storage /mnt /udisk /cache -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=vbmeta_vendor" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
sync
sleep 8
------------------------------------------------------------

  (b) soubor  vbmeta_disabled.img
      Zkopíruj existující z Mekede\lsec_updatesh\vbmeta_disabled.img
      (má být ~4096 B). Když ho tam nenajdeš, vygeneruj nový prázdný
      vbmeta přes avbtool:
          avbtool make_vbmeta_image --flags 2 --padding_size 4096 --output vbmeta_disabled.img
      (--flags 2 = HASHTREE_DISABLED; žádné descriptory = žádná verity.)

KROK 3 — KONTROLA (kritické):
  - V lsec_updatesh\ jsou POUZE 8581lsec.sh + vbmeta_disabled.img.
  - Na USB NIKDE není starý „wipe" 8581lsec.sh (ten co mazal metadata/userdata).
  - Na USB NIKDE není new-boot.img (Magisk-patched — způsobil bootloop).
  - Na USB NENÍ AllAppUpdate.bin (ten patří na jiný klíč, viz níže).
  Vypiš mi finální strom USB, ať to vidím.

POZOR – NEDĚLEJ:
  - žádné dd do super / userdata / metadata,
  - žádné mazání dat,
  - neměň názvy partitions natvrdo (skript si je najde sám).

VOLITELNĚ — priprav mi DRUHÝ USB klíč jako zálohu (NEmíchej s prvním!):
  FAT32, do kořene zkopíruj Mekede\AllAppUpdate.bin (plný stock firmware).
  Tenhle použiju jen když Tier 1 selže → plný tovární reflash přes FYT USB update.
```

---

## Co pak udělám já (uživatel)

1. USB #1 (Tier 1) → vlož do jednotky → RST → nech doběhnout „update sucess" →
   vytáhni USB → RST.
2. Pošlu si zpět z USB soubor `parts.txt` (+ `lastlog.txt` když bude) pro kontrolu.
3. Když nenabootuje → USB #2 s `AllAppUpdate.bin` → plný stock reflash.
