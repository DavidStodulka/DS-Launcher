# Cowork — kompletní root prompt (samostatný)

Vlož celý blok do Coworku. Je self-contained: obsahuje všechna fakta o zařízení,
princip, i rozhodovací větve.

---

```
ROLE / CÍL:
Jsi expert na Android low-level (AVB / dm-verity / Magisk / FYT lsec). Pomáháš mi
BEZPEČNĚ rootnout MŮJ head unit systemless Magiskem, BEZ bootloopu. Je to moje
zařízení, autorizováno. Každý zápis na disk mi nejdřív popiš a počkej na potvrzení.

ZAŘÍZENÍ (ověřeno z About device):
  Mekede MN X20 Pro · Unisoc UIS8581A (SC9863A) · Octa-Core · 4 GB RAM / 64 GB ROM
  Build QP1A.190711.020 release-keys (Android 10 base; "13" je jen FYT kosmetika)
  board 6316 · super = dynamic partitions (system/vendor/product = logical)
  A-only. Partitions vč.: boot, super, metadata, vbmeta, vbmeta_bak,
  vbmeta_system, vbmeta_vendor. (system/vendor NEMAJÍ _bak.)
  Jednotka teď běží STOCK a bootuje (právě zachráněna plným reflashem).

HISTORIE (proč pozor):
  Předtím Magisk "Direct Install (System)" zapsal do /system → vbmeta_system verity
  neshoda → /dev/block/dm-2 (=system/root) se nevytvořil → bootloop. Vyřešeno plným
  stock reflashem. TEĎ chci root tak, aby se to NEopakovalo.

PRINCIP BEZPEČNÉHO ROOTU:
  - Rootovat JEN patchnutím STOCK boot.img (systemless). NIKDY nesahat na /system,
    super, vbmeta_system, vbmeta_vendor. NIKDY "Direct Install" ani "Direct Install System".
  - Flashnout: patched boot + vypnout POUZE top-level vbmeta a vbmeta_bak
    (aby bootloader přijal patched boot). vbmeta_system necháme být — protože /system
    zůstává stock, jeho verity projde → dm-2 se vytvoří → žádný bootloop.
  - Kanál flashe = lsec USB update (na tomto zařízení JEDINÝ spolehlivý; ADB TCP 5555
    není nahozený, fastboot vrtkavý).

MÁM K DISPOZICI (složka Mekede\):
  6316_1.zip (obsahuje stock boot.img, SHA1 75900f451e43be53ba5334229c0e242551b68053),
  AllAppUpdate.bin, lsec6316update (flash binárka = trigger updatu), config.txt,
  lsec_updatesh\vbmeta_disabled.img (prázdný vbmeta 4096 B). Telefon s Androidem +
  Magisk-v27.0.apk. Prázdný USB klíč.

ÚKOL — proveď mě po krocích:

KROK A — PATCHED BOOT:
  Rozbal z Mekede\6316_1.zip soubor boot.img, ověř SHA1 = 75900f45… a dej mi ho do
  telefonu. Naveď mě: Magisk app → Install → "Select and Patch a File" → boot.img →
  výsledek magisk_patched-….img přenést do PC. (Zdůrazni: NE Direct Install.)
  Když umíš patchnout boot.img i lokálně přes magiskboot z APK, nabídni to jako
  alternativu, ale preferuj patch na telefonu.

KROK B — CÍLOVÝ USB:
  Zeptej se na písmeno disku. Naformátuj FAT32 (MBR). Nezapisuj bez potvrzení.

KROK C — SESTAV ROOT USB (script-only, bez full reflashe):
  Do KOŘENE:
    - lsec6316update      (trigger; z Mekede\)
    - config.txt          (z Mekede\)
    - magisk_patched-….img (z kroku A)
    - vbmeta_disabled.img  (z Mekede\lsec_updatesh\; když chybí:
                            avbtool make_vbmeta_image --flags 2 --padding_size 4096 --output vbmeta_disabled.img)
    - fyt_build.prop       (z Mekede\lsec_updatesh\; zapne po flashi ADB 5555 — pro ladění)
  NEDÁVEJ 6316_1.zip, AllAppUpdate.bin ani updatecfg.txt (nechci full reflash/reset).
  Vytvoř složku lsec_updatesh\ a v ní 8581lsec.sh + kopii 7862lsec.sh s TÍMTO
  obsahem (v3: by-name + LOG na USB; ulož UTF-8 BEZ BOM, LF, žádné \r — jinak dd selže):
  ------------------------------------------------------------
  #!/system/bin/sh
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; echo "=== lsec root v3 start ===" > "$D/lsec.log"; ls -la /dev/block/by-name >> "$D/lsec.log" 2>&1
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; p=$(find /storage /mnt /udisk -name fyt_build.prop 2>/dev/null | head -1); [ -n "$p" ] && { cp -f "$p" /oem/app/fyt_build.prop 2>>"$D/lsec.log"; chmod 644 /oem/app/fyt_build.prop 2>>"$D/lsec.log"; echo "prop rc=$?" >> "$D/lsec.log"; }
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; f=$(find /storage /mnt /udisk -name "magisk_patched*.img" 2>/dev/null | head -1); echo "patched=$f" >> "$D/lsec.log"; [ -n "$f" ] && { dd if="$f" of=/dev/block/by-name/boot 2>>"$D/lsec.log"; echo "boot rc=$?" >> "$D/lsec.log"; }
  D=$(find /storage /mnt /udisk -type d -name lsec_updatesh 2>/dev/null | head -1); [ -z "$D" ] && D=/cache; v=$(find /storage /mnt /udisk -name vbmeta_disabled.img 2>/dev/null | head -1); echo "vbmeta=$v" >> "$D/lsec.log"; [ -n "$v" ] && { dd if="$v" of=/dev/block/by-name/vbmeta 2>>"$D/lsec.log"; echo "vbmeta rc=$?" >> "$D/lsec.log"; dd if="$v" of=/dev/block/by-name/vbmeta_bak 2>>"$D/lsec.log"; echo "vbmeta_bak rc=$?" >> "$D/lsec.log"; }
  sync
  sleep 8
  ------------------------------------------------------------

KROK D — KONTROLA:
  Vypiš strom USB. Musí obsahovat: lsec6316update, config.txt, magisk_patched-….img,
  vbmeta_disabled.img, lsec_updatesh\{8581lsec.sh, 7862lsec.sh}.
  NESMÍ obsahovat: 6316_1.zip, AllAppUpdate.bin, new-boot.img, starý vbmeta/wipe skript.
  Potvrď, že 8581lsec.sh má LF konce řádků a přesně uvedený obsah.

KROK E — POKYNY K FLASHI (vypiš mi):
  1) USB do PŘEDNÍHO USB-A. Trigger: zastrč → u LOGA RST → po rozsvícení RST → 3× →
     4. boot jde do "Android update".
  2) Poběží jen pár sekund (jen skript). Počkej na "update sucess! Pls remove device".
  3) Vytáhni USB → RST → jednotka nabootuje s ROOTEM. Otevři/nainstaluj Magisk app.

VĚTVE:
  - Když "Android update" skončí HNED bez efektu (trigger nechytil skript) → přidej
    na USB 6316_1.zip + AllAppUpdate.bin; tím se spustí celý flow (napřed přeflashne
    stock, pak proběhne náš post-flash root skript). 100% spustí hook.
  - Když po rootu BOOTLOOP → flashni zpět stock boot (dd stock boot.img na partition
    boot přes stejný lsec mechanismus), nebo plný reflash USB. Rollback je jistý.

NEDĚLEJ: žádný zápis do /system, super, vbmeta_system, vbmeta_vendor, userdata,
metadata. Žádný Direct Install. Neupravuj AllAppUpdate.bin.
CO NAHLÁSIT: SHA1 boot.img, strom USB, potvrzení LF u skriptu.
```

---

Jediné, u čeho se zastav u mě: až budeš mít `magisk_patched-….img` a Cowork vypíše
strom USB — než dáš 3× RST, klidně mi ho hoď na kontrolu.
