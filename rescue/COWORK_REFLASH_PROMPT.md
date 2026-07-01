# Cowork — kompletní prompt: PLNÝ STOCK REFLASH (probudit jednotku)

Vlož celý blok níže do Coworku. Provede tě od kontroly configu přes sestavení
USB až po pokyny k flashi. Určeno k probuzení Mekede MN X20 Pro z bootloopu
oficiálním FYT USB updatem.

---

```
ROLE / CÍL:
Jsi asistent, který mi na Windows PC připraví USB klíč pro PLNÝ tovární reflash
head unitu Mekede MN X20 Pro (board 6316, Unisoc uis8581a / SC9863A). Jednotka je
v bootloopu po neúspěšném Magisk rootu (Direct Install System → dm-verity neshoda
na /system). Cíl: probudit ji do funkčního STOCK stavu oficiálním firmwarem.
Pracuj opatrně — každý zápis/formátování disku mi NEJDŘÍV popiš a počkej na potvrzení.

CO MÁM K DISPOZICI (ověřeno) — složka Mekede\ obsahuje kompletní oficiální FYT balík:
  - lsec6316update (2.4 MB)  — flashovací binárka (TRIGGER updatu)
  - config.txt (582 B)       — hlavní konfigurace flashe
  - updatecfg.txt (5 B="test")— POZOR: nejspíš přepsaný, původně měl 15 B
  - AllAppUpdate.bin (~1 GB) — payload (system/vendor/product…)
  - 6316_1\                  — boot.img, system, vendor, product, modem, uboot…

KROK 1 — CÍLOVÝ DISK:
  Zeptej se mě, na které písmeno zapsat. Doporuč: mám-li druhý USB stick, použij ten
  a nech E: (má Tier 1 vbmeta fix) jako zálohu; mám-li jen jeden, přeformátuj ho —
  po plném reflashi je Tier 1 bezpředmětný. NEZAPISUJ, dokud písmeno nepotvrdím.

KROK 2 — NEJDŘÍV UKAŽ CONFIG (před formátováním):
  Vypiš mi CELÝ obsah config.txt (582 B) i updatecfg.txt. Ověřujeme, že config
  reálně flashuje AllAppUpdate.bin + partitions (ne že přeskočí kvůli shodné verzi).
  - když config.txt odkazuje AllAppUpdate.bin / seznam partitions k flashi → dobré,
  - když je prázdný/placeholder/jen verze → upozorni mě.
  Ukaž to PŘEDTÍM, než cokoli zapíšeš. (Klidně mi to nech zkopírovat k odsouhlasení.)

KROK 3 — updatecfg.txt:
  Zkus najít původní 15B verzi (v Mekede\, ve složkách BACKUP, ve vypis.txt, nebo
  v jiné kopii balíku). Když ji najdeš, obnov ji. Když ne, nech současný soubor a
  jen mě upozorni (hlavní driver je config.txt).

KROK 4 — SESTAV USB:
  - Naformátuj cílový disk na FAT32, schéma MBR (ne GPT).
  - Zkopíruj KOMPLETNÍ obsah oficiálního balíku do KOŘENE USB, NEZMĚNĚNÝ:
    lsec6316update, config.txt, updatecfg.txt, AllAppUpdate.bin, celá složka 6316_1,
    + cokoli dalšího co k balíku patří (lsec6315update, OEM_APP\, myconfiguration\…).
    Nic nepřejmenovávej, nic nevynechávej.

KROK 5 — EXKLUZE (kritické) — na USB NESMÍ být:
  - žádný můj UPRAVENÝ 8581lsec.sh (vbmeta skript); má-li balík vlastní originální
    8581lsec.sh, nech ten originál,
  - žádný new-boot.img (Magisk-patched),
  - žádné pozůstatky z Tier 1 klíče.

KROK 6 — KONTROLA:
  - Ověř velikosti: AllAppUpdate.bin ~1 GB, lsec6316update ~2.4 MB, 6316_1\ má boot.img.
  - Vypiš finální strom USB včetně velikostí a potvrď FAT32/MBR.

KROK 7 — VYPIŠ MI POKYNY K FLASHI:
  1) USB do PŘEDNÍHO USB-A portu jednotky (HOST).
  2) Trigger: USB zastrč → u LOGA stiskni RST → po rozsvícení obrazovky zas RST →
     opakuj 3× → 4. boot sám vstoupí do upgrade módu ("Android update").
  3) Během flashe (běžící progress) NEPŘERUŠOVAT napájení, NEDÁVAT RST — píše se
     i uboot/vbmeta; přerušení uprostřed je jediné reálné riziko bricku. Nech dojet
     do "update sucess! Pls remove device".
  4) Dobrý flash = minuty + progress (~1 GB). Špatný = pár sekund a hned "sucess"
     → version-skip → ozvi se, upravíme config.
  5) Po "sucess" → vytáhni USB → RST. První boot může trvat pár minut, needitovat.

CO MI NAHLÁSIT: obsah config.txt + updatecfg.txt; finální strom USB + velikosti;
jestli se povedlo obnovit původní updatecfg.txt.
NEDĚLEJ: žádné dd, žádné mazání/úpravy partitions, žádnou úpravu AllAppUpdate.bin.
```

---

## AKTUALIZACE po analýze balíku (2 kritické opravy)

Po vypsání configů se ukázalo:

- **config.txt** = jen device props (rotace, BT, launcher…), aplikuje se po flashi.
  NEřídí co se flashuje. Nech být.
- **updatecfg.txt = "test"** (přepsáno z 15 B) = **NEblokuje** hlavní flash. Ten
  se řídí přítomností `6316_1.zip` + `AllAppUpdate.bin` + binárky + sekvencí 3× RST.
  `updatecfg.txt` řeší hlavně reset app vrstvy/nastavení. Nech soubor jak je.

**OPRAVA #1 (kritická) — kernel musí být `6316_1.zip` (ZIP), ne jen rozbalená složka.**
Modifikovaný `/system` (a tím Magisk + verity) přepíše právě kernel balík `6316_1.zip`.
Pokud na USB bude jen složka `6316_1\` a ne zip, jádro/system se nemusí flashnout a
jednotka zůstane rozbitá. → Najdi/použij originální `6316_1.zip` a dej ho na USB.

**OPRAVA #2 (bonus = ADB) — hook je `8581lsec.sh` (uis8581a), ne 7862.**
Do `lsec_updatesh\` dej rekonstruovaný OEM `8581lsec.sh` (identický obsah jako
7862lsec.sh) + `7862lsec.sh` + `fyt_build.prop`:
```
#!/system/bin/sh
cp -rf /storage/sdcard1/lsec_updatesh/fyt_build.prop /oem/app/fyt_build.prop
chmod 644 /oem/app/fyt_build.prop
```
Po reflashi hook zkopíruje fyt_build.prop → zapne **ADB na TCP 5555**
(persist.service.adb.enable=1) → čistý síťový kanál pro další práci a správný root.
`fyt_build.prop` NEuprav (OEM originál). Pozn.: NEplést s naším vbmeta `8581lsec.sh`
z Tier 1 — ten na tenhle reflash klíč NEpatří.

Finální struktura USB (kořen):
    lsec6316update, 6316_1.zip, AllAppUpdate.bin, config.txt, updatecfg.txt,
    lsec_updatesh\{8581lsec.sh, 7862lsec.sh, fyt_build.prop}
    (+ OEM_APP\, myconfiguration\, lsec6315update — pokud jsou v balíku)
