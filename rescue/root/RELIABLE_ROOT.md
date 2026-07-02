# Spolehlivý root — postup s kontrolou před flashem

Minule „update success" proběhl, ale root ne, protože **na USB nebyl patched
boot** → skript neměl co flashnout. Tenhle postup tu chybu **chytí předem**.

## Fáze 1 — vyrobit patched boot (ty, na telefonu)
1. Z `Mekede\6316_1.zip` rozbal **boot.img** (SHA1 `75900f451e43be53ba5334229c0e242551b68053`).
2. Přenes do telefonu, Magisk app → **Install → Select and Patch a File** → boot.img.
   (⚠ NIKDY *Direct Install* / *Direct Install System*.)
3. Výsledek `magisk_patched-….img` přenes do PC a **PŘEJMENUJ na `boot_patched.img`**.
   (Pevný název = konec problémů s hledáním souboru.)

## Fáze 2 — sestavit USB (Cowork nebo ty)
USB = FAT32/MBR. Do **kořene** dej přesně:

| soubor | odkud | k čemu |
|---|---|---|
| `lsec6316update` | `Mekede\` | trigger updatu |
| `config.txt` | `Mekede\` | trigger |
| `boot_patched.img` | z Fáze 1 | patched boot |
| `vbmeta_disabled.img` | `Mekede\lsec_updatesh\` | vypnutí verity bootu |
| `fyt_build.prop` | `Mekede\lsec_updatesh\` | zapne ADB 5555 |
| `lsec_updatesh\8581lsec.sh` + `7862lsec.sh` | v4 skript (viz `50-root-flash.sh`) | flash + log |

**NEDÁVAT:** `6316_1.zip`, `AllAppUpdate.bin`, `updatecfg.txt`, `new-boot.img`, starý wipe/vbmeta skript.

## Fáze 3 — ⭐ KONTROLA PŘED FLASHEM (nepřeskakuj!)
Ve Windows PowerShell (nahraď `E:` písmenem USB) ověř, že soubory **fakt jsou a mají velikost**:
```
dir E:\
dir E:\lsec_updatesh\
```
Musíš vidět:
- `boot_patched.img` — **~16–32 MB** (NE 0, NE pár kB!)
- `vbmeta_disabled.img` — ~4 kB
- `lsec6316update`, `config.txt`, `fyt_build.prop`
- `lsec_updatesh\8581lsec.sh` a `7862lsec.sh`

Když `boot_patched.img` chybí nebo má 0 B → **NEFLASHUJ**, oprav USB. (To byla minulá chyba.)

## Fáze 4 — flash
1. USB do **předního USB-A**. Trigger: zastrč → u LOGA **RST** → po rozsvícení **RST** → 3× → 4. boot „Android update".
2. Počkej na **„update sucess! Pls remove device"**.
3. Vytáhni USB.

## Fáze 5 — ⭐ OVĚŘENÍ Z LOGU (než dáš RST)
Na PC otevři z USB **`lsec_updatesh\lsec.log`**. Chceš vidět:
```
patched=/…/boot_patched.img size=<milióny>   <- ne prázdné, velké číslo
boot flash rc=0
vbmeta rc=0
vbmeta_bak rc=0
```
- Vidíš **`boot flash rc=0`** → paráda, dej **RST** → nabootuje s rootem.
- Vidíš **`BOOT SKIP`** → patched boot pořád nebyl na USB → zpět na Fázi 2/3.
- `by-name` ve výpisu nemá `boot`/`vbmeta` → pošli mi `lsec.log`, přepneme cíl.

## Rollback (kdyby bootloop)
- Flashni zpět stock boot (dd `boot.img` z 6316_1 na `by-name/boot` stejným lsec mechanismem), nebo
- plný reflash USB. Riziko ohraničené, ne brick.
