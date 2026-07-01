# MEKEDE MN X20 Pro — Bootloop Rescue

Záchranný kit pro head unit **Mekede MN X20 Pro** (Unisoc UIS8581A / SC9863A,
Android 10, board 6316) po neúspěšném rootu Magiskem → **bootloop**.

Rádio **není bricknuté** — bootloader spustí kernel a init, dostane se až do
`fs_mgr` (userspace), tam spadne. To znamená, že lsec/FYT update hook stále
běží → máme spolehlivý injektážní bod na opravu bez BROM/ADB.

---

## 0. ⚠ Co NEspouštět (poučení ze zachyceného trace)

Ze zachyceného FYT trace (Android update V7.22) běžel omylem **starý wipe
skript**, který dělá jen:

```
find /dev/block -name metadata | ... dd if=/dev/zero ... bs=4096 count=4096
find /dev/block -name userdata | ... dd if=/dev/zero ... bs=1048576 count=2
```

To je **low-level factory reset** (vymaže metadata + začátek userdata) a
odpovídá STARÉ, zavržené teorii `dm-2 = /data`. **Nespravuje** `dm-2 = system`
— po něm `E:[libfs_mgr]Failed to open '/dev/block/dm-2'` **přetrvává** (potvrzeno
na obrazovce) a jednotka jen dlouho reformátuje smazanou userdata → recovery.

Trace zároveň potvrdil, že tovární discovery
`grep -l "PARTNAME=xxx" /sys/class/block/*/uevent | xargs dirname | xargs basename`
na tomto zařízení **funguje** → Tier 1 skript ho proto používá místo `by-name`.

---

## 1. Diagnóza (co se opravdu děje)

Chyba při bootloopu:

```
E:[libfs_mgr]Failed to open '/dev/block/dm-2': No such file or directory
```

Z `device_props.txt` (funkční stav) víme:

| prop | hodnota | význam |
|------|---------|--------|
| `dev.mnt.blk.product` | `dm-0` | product (logical v super) |
| `dev.mnt.blk.vendor`  | `dm-1` | vendor  (logical v super) |
| `dev.mnt.blk.root`    | `dm-2` | **system / root** |
| `dev.mnt.blk.elable`  | `dm-3` | elable |

Takže `dm-2` = **systémový oddíl**, ne `/data`. Device-mapper node pro system
se nevytvoří → `fs_mgr` ho nemůže otevřít → bootloop.

### Proč se dm-2 nevytvoří — hlavní hypotéza (verity)

1. Magisk *Direct Install (System)* zapsal přímo do `/system`.
2. `system` je **logical partition uvnitř `super`** (dynamic partitions).
3. `vbmeta_system` nese **hashtree descriptor** = hash *původního* systému.
4. Uživatel vypnul jen `vbmeta` (p35) + `vbmeta_bak` (p36) prázdným
   `vbmeta_disabled.img` (flags=3).
5. **Klíčový fakt (ověřeno z AVB dokumentace):** DISABLE flagy v top-level
   vbmeta se **nedědí** na chained `vbmeta_system` / `vbmeta_vendor`. Každá
   chained vbmeta si o verity rozhoduje sama svým hashtree descriptorem.
6. → `vbmeta_system` pořád vynucuje verity proti Magiskem změněnému systému
   → hash nesedí → `fs_mgr` nepostaví dm mapping pro system → **dm-2 chybí**
   → bootloop. Obnova stock boot.img proto nepomohla — problém je v `/system`,
   ne v boot.

**Oprava (Tier 1):** přepsat `vbmeta_system` + `vbmeta_vendor` prázdným vbmeta.
Tím se **odebere hashtree descriptor** → verity se pro system vůbec nenastaví
→ dm-2 vznikne jako čistý dm-linear → boot projde.

### Poctivá výhrada (proč mít připravený i plán B)

`system` žije v `super`. Pokud Magisk při zápisu **resizoval logical partition
a přepsal LP metadata super**, pak dm-2 nechybí kvůli verity, ale kvůli
nekonzistentnímu super metadata — a Tier 1 by sám nestačil. Kernel log
z minulého bootu to rozhodne, proto je **Tier 0 diagnostika** první krok.
Pokud jde o super metadata, definitivní oprava je **plný stock reflash**
(Tier 2), který přepíše celý `super`.

---

## 2. Postup (v pořadí)

> Detailní návod „co dát na USB“ je v [`lsec_updatesh/README.txt`](lsec_updatesh/README.txt).
> Vždy se na USB dává jeden skript přejmenovaný na **`8581lsec.sh`**.

### Tier 0 — Diagnostika (5 min, nic nemění) ✅ dělej první
`lsec_updatesh/00-diagnostics.sh` → sesbírá kernel log z minulého bootu,
partitions, by-name mapping, hlavičky vbmeta a zapíše je **zpět na USB**
(stdout je v FYT potlačen, jediná cesta ven je soubor). Na PC přečti
`diag_pstore_console.txt` / `diag_last_kmsg.txt` — tam je přesný kontext pádu
`dm-2` (potvrdí verity vs. super metadata).

### Tier 1 — Vypnout verity pro system + vendor (5 min) ⭐ nejpravděpodobnější fix
`lsec_updatesh/10-disable-vbmeta-system-vendor.sh` + `vbmeta_disabled.img`.
Přepíše `vbmeta_system` a `vbmeta_vendor` → odebere hashtree descriptor.
Po doběhu RST. Když nabootuje → hotovo (a Magisk root ti dokonce zůstane,
protože verity je pryč a systémová modifikace zůstává).

### Tier 2 — Plný stock reflash (definitivní, když Tier 1 selže)
Vrátí `super` (tedy i system) + boot + vbmeta do továrního konzistentního
stavu → **jistá oprava**, undo Magisku.

- **A) FYT USB update** — `AllAppUpdate.bin` (1 GB) na USB přes normální FYT
  update flow. Nejjednodušší, pokud jednotka update stage při bootloopu ještě
  dosáhne (lsec hook běží → pravděpodobně ano).
- **B) SPD ResearchDownload + PAC** — potřebuješ PAC pro board 6316 (SC9863A)
  a BROM přístup:
  - `SPD_Dump` / ResearchDownload / SPD Upgrade Tool (Windows).
  - BROM na Unisoc: typicky **test point / zkratovaný pad** na desce, nebo
    Vol+ & Vol- při zapojení USB (u head unitu bývá jen test-point, protože
    přední USB-A je HOST). Hledej „boot pin“ / „ADFU-like“ pad u eMMC.
  - Z PAC lze i vytáhnout jednotlivé partition image (mj. čistý `super`).

### Tier 3 — Obnova jen system z `system.new.dat.br` (komplex, spíš nouzově)
`system.new.dat.br` je brotli-komprimovaný sparse dat blok. Protože system je
uvnitř `super`, nelze ho jen `dd`-nout do raw partitions — musel bys buď
přemapovat logical partition (`lptools`/`snapshotctl`, ale to vyžaduje aspoň
částečně nabootovaný systém), nebo poskládat celý `super` a flashnout ho.
V praxi je Tier 2 (celý reflash) jednodušší a spolehlivější než tohle.

---

## 3. Jak jsou skripty psané (FYT executor)

FYT lsec executor (`lsec6316update`) má specifika, se kterými skripty počítají:

- **Každý řádek = samostatný `sh -c "<řádek>"`** → proměnné mezi řádky
  nepřetrvávají. Proto je každá logická operace (i `for` smyčka) na **jednom
  řádku**.
- **Pipe v rámci řádku funguje.**
- **stdout i stderr jsou potlačeny** → veškerý výstup jde do souboru (na USB /
  `/cache`), ne na obrazovku.
- **`reboot` v skriptu selže** → skript končí `sync` + `sleep`, restart uděláš
  ručně tlačítkem **RST**.
- **`/dev/block/by-name/` je dostupné** (původní funkční skript ho používal) →
  žádná natvrdo napsaná `mmcblk0pN` čísla.
- Cesta k USB se hledá globem (`/storage/*`, `/mnt/media_rw/*`, `/storage/usb*`).

---

## 4. Bezpečnost / co NEdělat

- ✅ Skripty píší `dd` jen do **malých vbmeta** partitions nebo do **boot** přes
  by-name. Žádný zápis do `super`, `userdata`, `metadata`.
- ⚠ **Nikdy neflashuj `new-boot.img` z USB** — to je Magisk-patched (bootloop).
  Stock boot je `Mekede\6316_1\boot.img`, SHA1 `75900f451e43be53ba5334229c0e242551b68053`.
- ⚠ Firmware/vbmeta/boot **bloby se do gitu necommitují** (viz `rescue/.gitignore`).
  Patří jen na USB / lokální PC.
- 🛟 Fallback k dispozici pořád: `AllAppUpdate.bin` + SPD PAC → z bootloopu se
  dá dostat i kdyby lsec cesta selhala.

---

## 5. Reference

- FYT/Spreadtrum (uis7862 / uis8581a / SC9863A) — mody, firmware, lsec hooky:
  [XDA: General FYT based Spreadtrum uis7862(s) & uis8581a (sc9863)](https://xdaforums.com/t/general-fyt-based-spreadtrum-uis7862-s-unisoc-ums512-uis8581a-sc9863-q-a-mods-tips-firmware.4396339/)
- AVB (flagy, chained partitions, hashtree descriptors):
  [android.googlesource.com/platform/external/avb](https://android.googlesource.com/platform/external/avb/+/master/README.md)
- SPD / Unisoc Research Download Tool:
  [spdflashtool.com](https://spdflashtool.com/) ·
  [androidmtk.com/download-spd-research-tool](https://androidmtk.com/download-spd-research-tool)
- Backup Unisoc firmware přes ResearchDownload:
  [droidwin.com](https://droidwin.com/backup-unisoc-spreadtrum-firmware-research-download-tool/)
