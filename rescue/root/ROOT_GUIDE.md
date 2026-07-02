# Bezpečný root Mekede X20 (po záchraně z bootloopu)

Jednotka je zpět na stocku a bootuje. Cíl: **Magisk root bez bootloopu.**

## Proč to minule bootloopnulo (a co děláme jinak)

| Minule (=bootloop) | Teď (bezpečně) |
|---|---|
| Magisk **Direct Install (System)** → zápis do `/system` | **NIKDY nesahat na `/system`.** Jen systemless. |
| Patched boot flashnut **bez** disable vbmeta → bootloader odmítl | Patched boot **+ disable top-level vbmeta** (bootloader ho pak přijme) |
| `/system` modifikované → vbmeta_system verity fail → **dm-2 chybí** | `/system` zůstává **stock** → verity projde → dm-2 OK |

**Klíč:** viník bootloopu byla modifikace `/system`, ne root jako takový.
Když necháme `/system` netknutý a jen patchneme boot + vypneme top-level vbmeta,
jednotka nabootuje s rootem.

Pozn. k AVB: top-level `VERIFICATION_DISABLED` řekne bootloaderu, ať nekontroluje
(patched) boot. `vbmeta_system` (chained) NEcháme být → verity systemu běží dál,
ale protože system je stock, **projde**. Proto se dm-2 problém NEopakuje.

## Recept (pořadí)

1. **Vezmi STOCK boot** = `Mekede\6316_1\boot.img` (SHA1 `75900f451e43be53ba5334229c0e242551b68053`).
   To je přesně ten, co teď v jednotce běží (flashnut z 6316_1.zip).
   (Extra jistota: dumpni aktuální boot z jednotky — `lsec_updatesh/40-dump-boot.sh` — a porovnej.)
2. **Patchni ho Magiskem** → `magisk_patched.img`:
   - Magisk app na telefonu → Install → *Select and Patch a File* → vyber boot.img, NEBO
   - nainstaluj Magisk-v27.0.apk na jednotku přes ADB a patchni přímo tam.
   - ⚠ NIKDY nepoužívej *Direct Install (System)* ani *Direct Install*.
3. **Flashni** patched boot + disable vbmeta. Dva kanály:
   - **A) fastboot (nejčistší):**
     ```
     fastboot flash boot        magisk_patched.img
     fastboot flash vbmeta       vbmeta_disabled.img
     fastboot flash vbmeta_bak   vbmeta_disabled.img
     fastboot reboot
     ```
     (do fastbootu: `adb reboot bootloader`, nebo jak ses tam dostal minule)
   - **B) lsec (ověřený na tomto zařízení):** `lsec_updatesh/50-root-flash.sh`
     (na USB: `magisk_patched.img` + `vbmeta_disabled.img`), spustí přes update flow.
4. **Reboot.** Nabootuje s rootem → nainstaluj/otevři Magisk app, hotovo.

## Rollback (kdyby přece jen bootloop)

- **Rychlý:** flashni zpět stock boot — fastboot `flash boot boot.img`, nebo lsec
  `lsec_updatesh/20-restore-stock-boot.sh` (stock-boot.img). Systém je stock, takže
  stock boot = zpět do funkčního stavu.
- **Jistý:** ten reflash USB, co jsme udělali → zpět na čistý stock.

Riziko je tím ohraničené na „další kolo", ne na brick.

## Co ještě NEdělat

- žádný zápis do `/system`, `super`, `vbmeta_system`, `vbmeta_vendor`, `userdata`,
- neflashovat cizí/patched `system`,
- nezapínat Magisk moduly, které sahají do systemu, dokud root nestojí stabilně.
