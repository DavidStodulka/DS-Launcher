# Cowork — připrav USB (OPRAVENO: trigger = lsec6316update + config.txt)

**Důležité zjištění:** FYT USB update NEspouští složka `lsec_updatesh` sama.
Spouští ho flashovací binárka **`lsec6316update`** + **`config.txt`** na USB
(krok „copy config file (OK)" na obrazovce). `8581lsec.sh` je jen *post-flash
hook*, který ta binárka zavolá až po flashi. Bez binárky + configu jednotka na
USB **nereaguje**. Proto verze „jen 8581lsec.sh + vbmeta_disabled.img" nefunguje.

Níže dvě varianty. **Doporučeno teď: Varianta A** (plný stock reflash — jistě
nabootuje, undo Magisku; root doděláme správně potom). Varianta B je chirurgická
(zkusit zachovat root).

---

## Varianta A — PLNÝ STOCK REFLASH (doporučeno) — vlož Coworku:

```
ÚKOL: Sestav mi USB pro PLNÝ tovární reflash head unitu Mekede MN X20 Pro
(board 6316, Unisoc uis8581a / SC9863A). Mám kompletní firmware od výrobce ve
složce Mekede\. Zeptej se mě na písmeno USB disku, než začneš zapisovat.

1. Najdi v Mekede\ originální FYT update balík. Pozná se podle těchto souborů:
   - flashovací binárka:  lsec6316update  (možná i lsec6315update)
   - config:              config.txt  a/nebo  updatecfg.txt
   - payload:             AllAppUpdate.bin
   - kernel/system:       složka 6316_1  (nebo 6316_1.zip) s boot.img, system.new.dat.br…
   - volitelně:           složky OEM_APP, myconfiguration (config.txt + fyt.prop)
   Vypiš mi, co jsi našel a kde.

2. Naformátuj USB na FAT32 (oddíl MBR, ne GPT). Zkopíruj KOMPLETNÍ obsah toho
   balíku do KOŘENE USB, NEZMĚNĚNÝ. Nic nepřejmenovávej, nic nemaž, nic nepřidávej.

3. KRITICKÉ — na USB NESMÍ být:
   - žádný MŮJ upravený 8581lsec.sh (vbmeta skript) — nech originál z balíku,
   - žádný new-boot.img (Magisk-patched).
   Cílem je čistý stock, ne mod.

4. Vypiš mi finální strom USB.
```

Pak: vlož USB do jednotky (přední USB-A = HOST) → RST → nech proběhnout celý
update do konce („update sucess! Pls remove device") → vytáhni USB → RST.
Plný reflash přepíše super (system/vendor/product), boot i vbmeta zpět na stock
→ Magisk modifikace zmizí, verity hash zase sedí → nabootuje.

---

## Varianta B — CHIRURGICKÁ (zkusit zachovat root) — vlož Coworku:

```
ÚKOL: Uprav mi PŘEDCHOZÍ FUNKČNÍ USB — ten, který spustil obrazovku
„Android update (V7.22): copy config file (OK)". Zeptej se mě na písmeno disku.

NECH NA USB VŠECHNY spouštěcí soubory (lsec6316update binárka, config.txt /
updatecfg.txt, myconfiguration, apod.) — TY spouští celý update. Změň JEN:

1. lsec_updatesh\8581lsec.sh  → přepiš tímto obsahem (ulož UTF-8 bez BOM, konce
   řádků LF/Unix, žádné \r):
   ------------------------------------------------------------
   #!/system/bin/sh
   D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; grep "PARTNAME=" /sys/class/block/*/uevent > "$D/parts.txt" 2>/dev/null; cat /sys/fs/pstore/console-ramoops-0 > "$D/lastlog.txt" 2>/dev/null
   f=$(find /storage /mnt /udisk /cache -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=vbmeta_system" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
   f=$(find /storage /mnt /udisk /cache -name vbmeta_disabled.img 2>/dev/null | head -1); [ -n "$f" ] && grep -l "PARTNAME=vbmeta_vendor" /sys/class/block/*/uevent 2>/dev/null | head -1 | xargs dirname | xargs basename | xargs -I{} dd if="$f" of=/dev/block/{}
   sync
   sleep 8
   ------------------------------------------------------------

2. vbmeta_disabled.img  → ať je na USB (klidně v lsec_updatesh\). Když chybí,
   vygeneruj: avbtool make_vbmeta_image --flags 2 --padding_size 4096 --output vbmeta_disabled.img

3. new-boot.img  → POKUD je na USB: buď ho SMAŽ, nebo přejmenuj STOCK
   Mekede\6316_1\boot.img na new-boot.img (aby se náhodou neflashnul Magisk boot).
   Stock boot má SHA1 75900f451e43be53ba5334229c0e242551b68053.

4. Otevři config.txt / updatecfg.txt a NAPIŠ MI jeho obsah — ať víme, co update
   flashuje (jen boot? system? AllAppUpdate? nebo jen běží skript). NEflashuj nic
   navíc, co tam nepatří.

5. Vypiš finální strom USB.
```

Poznámka: pokud config.txt flashuje AllAppUpdate.bin nebo system, pak reálně
děláš skoro plný reflash — v tom případě rovnou zvol Variantu A.
