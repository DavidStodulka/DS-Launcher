# Cowork — kompletní úkol: sestav root USB + obě kontrolní brány

Uživatel (já) už mám: naformátovaný USB klíč a `boot_patched.img` (Magiskem
patchnutý STOCK boot z `6316_1.zip`, přejmenovaný přesně na `boot_patched.img`).

Vlož Coworku celý blok níže. Sestaví USB, provede BRÁNU 1 (kontrola PŘED
flashem) sám, a po tvém flashi rozebere `lsec.log` (BRÁNA 2).

---

```
ROLE / CÍL:
Sestav mi ROOT USB pro Mekede MN X20 Pro (Unisoc uis8581a) a proveď důkladnou
kontrolu PŘED i PO flashi, ať se nezopakuje minulá chyba (skript proběhl, ale
na USB chyběl patched boot, takže se nic neflashlo). Zeptej se mě na písmeno
USB disku. Každý zápis mi popiš předem.

MÁM K DISPOZICI:
  - USB klíč, už naformátovaný FAT32/MBR (ověř, nepřeformátovávej bez dotazu)
  - boot_patched.img (Magiskem patchnutý stock boot) — řekni mi, kam ho mám dát,
    nebo ho najdi, pokud už je na disku
  - Mekede\ složka: lsec6316update, config.txt, lsec_updatesh\vbmeta_disabled.img,
    lsec_updatesh\fyt_build.prop

KROK 1 — SESTAVENÍ USB (kořen disku):
  Zkopíruj do kořene USB:
    - lsec6316update       (z Mekede\)
    - config.txt           (z Mekede\)
    - boot_patched.img     (najdi na PC nebo se mě zeptej na cestu)
    - vbmeta_disabled.img  (z Mekede\lsec_updatesh\; pokud chybí, vygeneruj:
                            avbtool make_vbmeta_image --flags 2 --padding_size 4096 --output vbmeta_disabled.img)
    - fyt_build.prop       (z Mekede\lsec_updatesh\)
  NEDÁVEJ na USB: 6316_1.zip, AllAppUpdate.bin, updatecfg.txt, new-boot.img,
  žádný starší/jiný lsec skript.

  Vytvoř složku lsec_updatesh\ a v ní DVA soubory, 8581lsec.sh a 7862lsec.sh,
  OBA se stejným obsahem níže. Ulož jako UTF-8 BEZ BOM, konce řádků LF (Unix),
  žádné \r znaky — jinak shell na jednotce skript rozbije:
  ------------------------------------------------------------
  #!/system/bin/sh
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; U=$(dirname "$D" 2>/dev/null); echo "=== lsec root v4 ===" > "$D/lsec.log"; date >> "$D/lsec.log" 2>&1; echo "--- USB root ($U) ---" >> "$D/lsec.log"; ls -la "$U" >> "$D/lsec.log" 2>&1; echo "--- by-name ---" >> "$D/lsec.log"; ls -la /dev/block/by-name >> "$D/lsec.log" 2>&1
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; p=$(find /storage /mnt /udisk -name fyt_build.prop 2>/dev/null | head -1); echo "prop=$p" >> "$D/lsec.log"; [ -n "$p" ] && { cp -f "$p" /oem/app/fyt_build.prop 2>>"$D/lsec.log"; chmod 644 /oem/app/fyt_build.prop 2>>"$D/lsec.log"; echo "prop rc=$?" >> "$D/lsec.log"; }
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; f=$(find /storage /mnt /udisk -name "boot_patched.img" 2>/dev/null | head -1); [ -z "$f" ] && f=$(find /storage /mnt /udisk -name "magisk_patched*.img" 2>/dev/null | head -1); sz=$(wc -c < "$f" 2>/dev/null); echo "patched=$f size=$sz" >> "$D/lsec.log"; if [ -n "$f" ] && [ "${sz:-0}" -gt 1000000 ]; then dd if="$f" of=/dev/block/by-name/boot 2>>"$D/lsec.log"; echo "boot flash rc=$?" >> "$D/lsec.log"; else echo "BOOT SKIP: patched image chybi nebo maly" >> "$D/lsec.log"; fi
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); echo "vbmeta=$v" >> "$D/lsec.log"; if [ -n "$v" ]; then dd if="$v" of=/dev/block/by-name/vbmeta 2>>"$D/lsec.log"; echo "vbmeta rc=$?" >> "$D/lsec.log"; dd if="$v" of=/dev/block/by-name/vbmeta_bak 2>>"$D/lsec.log"; echo "vbmeta_bak rc=$?" >> "$D/lsec.log"; else echo "VBMETA SKIP: img chybi" >> "$D/lsec.log"; fi
  sync
  sleep 8
  ------------------------------------------------------------

KROK 2 — BRÁNA 1: KONTROLA PŘED FLASHEM (proveď SÁM, nepokládej mi to jako otázku):
  Spusť ekvivalent `dir` / `Get-ChildItem` na kořeni USB i na lsec_updatesh\ a
  ukaž mi VÝPIS SE VELIKOSTMI. Ověř explicitně a napiš mi ANO/NE ke každému:
    [ ] boot_patched.img existuje A má velikost > 1 000 000 B (typicky 16–32 MB)
    [ ] vbmeta_disabled.img existuje (~4096 B)
    [ ] lsec6316update existuje
    [ ] config.txt existuje
    [ ] fyt_build.prop existuje
    [ ] lsec_updatesh\8581lsec.sh existuje A NEMÁ CRLF (zkontroluj, že soubor
        neobsahuje \r — např. přes PowerShell:
        (Get-Content -Raw lsec_updatesh\8581lsec.sh) -match "`r")
    [ ] lsec_updatesh\7862lsec.sh existuje se stejným obsahem jako 8581lsec.sh
    [ ] na USB NEJSOU: 6316_1.zip, AllAppUpdate.bin, updatecfg.txt, new-boot.img
  Pokud JAKÝKOLI bod selže (hlavně boot_patched.img chybí/je malý), ZASTAV SE,
  napiš mi přesně co chybí, a NEPOKRAČUJ dokud to nedoplním. Toto je přesně ta
  chyba, která se stala minule (skript proběhl, ale nic se neflashlo, protože
  patched boot na USB nebyl).

KROK 3 — POKYNY K FLASHI (vypiš mi, já to provedu fyzicky na jednotce):
  1) USB do PŘEDNÍHO USB-A portu jednotky.
  2) Trigger: zastrč USB → jakmile se objeví LOGO, stiskni RST → po rozsvícení
     obrazovky znovu RST → opakuj celkem 3×  → počtvrté boot sám vejde do
     "Android update" módu.
  3) Počkej na zelený nápis "update sucess! Pls remove device".
  4) Vytáhni USB, nedávej ještě RST.
  Řekni mi, ať ti dám vědět, jakmile toto proběhne a USB je zpátky v PC.

KROK 4 — BRÁNA 2: KONTROLA PO FLASHI (proveď sám, až ti řeknu že je USB zpět):
  Otevři a vypiš mi CELÝ obsah lsec_updatesh\lsec.log z USB. Rozeber ho a
  napiš mi jasný verdikt:
    - "boot flash rc=0" A "patched=...boot_patched.img size=<velké číslo>"
        → ÚSPĚCH, řekni mi: "Můžeš dát RST, mělo by nabootovat s rootem."
    - "BOOT SKIP" nebo patched= je prázdné
        → SELHÁNÍ, patched boot nebyl nalezen i přes kontrolu v Kroku 2 —
          vypiš mi celý USB root listing z logu (sekce "--- USB root ---")
          ať vidíme, co tam FYT vidělo, a najdi rozdíl oproti Kroku 2.
    - "vbmeta rc=0" a "vbmeta_bak rc=0" chybí nebo mají jiný kód → upozorni mě.
    - pokud v logu chybí sekce "by-name" nebo neobsahuje "boot"/"vbmeta" →
      upozorni mě, budeme muset změnit cílovou cestu (ne by-name).

NEDĚLEJ (za žádných okolností): žádný zápis do /system, super, vbmeta_system,
vbmeta_vendor, userdata, metadata. Nic neflashuj mimo boot/vbmeta/vbmeta_bak.
```

---

## Co uděláš ty (mimo Cowork)
1. Patchni `boot.img` (z `6316_1.zip`) Magiskem na telefonu → *Select and Patch
   a File* (NE Direct Install) → výsledek přejmenuj na `boot_patched.img`.
2. Naformátuj USB FAT32/MBR (už hotovo dle zprávy).
3. Dej Coworku vědět, kde `boot_patched.img` leží (cesta na PC).
4. Až Cowork potvrdí Bránu 1 (vše ✅), proveď fyzický flash (Krok 3) a vrať USB
   zpět do PC.
5. Nech Coworka udělat Bránu 2 (rozbor logu) — teprve na jeho **"Můžeš dát RST"**
   dej RST na jednotce.

Tenhle postup structurally vylučuje, aby se zopakovala minulá chyba — Cowork
sám ověří přítomnost a velikost `boot_patched.img` PŘED tím, než tě pustí
k fyzickému flashi, a znovu PO flashi rozebere log, než dovolí RST.
