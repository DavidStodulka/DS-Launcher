# Cowork — root USB (lsec kanál, jistá cesta)

Nejdřív ty (uživatel) na telefonu: Magisk app → Install → **Select and Patch a
File** → vyber **boot.img z 6316_1.zip** (ten flashnutý stock, SHA1 75900f45…) →
výsledek `magisk_patched-….img` přenes do PC. ⚠ NIKDY *Direct Install* / *Direct
Install System*.

Pak vlož Coworku:

---

```
ÚKOL: Sestav ROOT USB pro Mekede MN X20 Pro (uis8581a) přes lsec kanál. Jednotka
běží stock, chci systemless Magisk root BEZ bootloopu. Zeptej se na písmeno USB.

PRINCIP: přes lsec update flow spustit post-flash skript, který flashne
magisk_patched boot a vypne top-level vbmeta. NEsahá na /system → žádný dm-2.

KROK 1 — naformátuj USB FAT32 (MBR).

KROK 2 — do KOŘENE USB zkopíruj TRIGGER (bez něj se nic nespustí):
  - lsec6316update        (z Mekede\, flashovací binárka = spouštěč)
  - config.txt            (z Mekede\)
  NEDÁVEJ 6316_1.zip ani AllAppUpdate.bin ani updatecfg.txt — nechceme full
  reflash ani reset apps, jen spustit skript.

KROK 3 — na USB dej payload (klidně do kořene):
  - magisk_patched-….img  (ten co jsem vytvořil Magiskem z 6316_1 boot.img)
  - vbmeta_disabled.img    (prázdný vbmeta, 4096 B, flags=3; z Mekede\lsec_updatesh\
                            nebo: avbtool make_vbmeta_image --flags 2 --padding_size 4096 --output vbmeta_disabled.img)

KROK 4 — vytvoř složku lsec_updatesh\ a v ní soubor 8581lsec.sh (a kopii jako
  7862lsec.sh) s TÍMTO obsahem (ulož UTF-8 bez BOM, konce řádků LF, žádné \r):
  ------------------------------------------------------------
  #!/system/bin/sh
  f=$(find /storage /mnt /udisk -name "magisk_patched*.img" 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=boot$" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
  v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$v" ] && grep -l "PARTNAME=vbmeta$" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$v" of=/dev/block/{}
  v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$v" ] && grep -l "PARTNAME=vbmeta_bak$" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$v" of=/dev/block/{}
  sync
  sleep 8
  ------------------------------------------------------------

KROK 5 — KONTROLA: vypiš strom USB. Má obsahovat:
  lsec6316update, config.txt, magisk_patched-….img, vbmeta_disabled.img,
  lsec_updatesh\{8581lsec.sh, 7862lsec.sh}
  NESMÍ tam být: 6316_1.zip, AllAppUpdate.bin, new-boot.img, náš starý vbmeta/wipe skript.

CO NAHLÁSIT: strom USB + potvrzení, že 8581lsec.sh má LF a přesně výše uvedený obsah.
NEDĚLEJ: žádný zápis do /system, super, vbmeta_system, vbmeta_vendor, userdata.
```

---

## Flash (ty)
1. USB do předního USB-A. Trigger: zastrč → u LOGA RST → po rozsvícení RST → 3× →
   4. boot jde do „Android update".
2. Nech doběhnout do „update sucess! Pls remove device". (Poběží pár sekund — jen
   skript, ne full flash.)
3. Vytáhni USB → RST → mělo by nabootovat **s rootem** (Magisk app → nainstalovat/otevřít).

## Kdyby se skript NEspustil (update skončí hned bez efektu = trigger neprošel)
Přidej na USB zpět **6316_1.zip + AllAppUpdate.bin** (jako u reflashe). Tím se
spustí zaručeně celý flow: nejdřív se přeflashne stock, pak proběhne náš post-flash
skript (root). Je to těžší (full reflash), ale 100% spustí hook.

## Kdyby bootloop
- `lsec_updatesh/20-restore-stock-boot.sh` (stock-boot.img) → zpět na stock boot, nebo
- reflash USB → čistý stock. Riziko je ohraničené, ne brick.
