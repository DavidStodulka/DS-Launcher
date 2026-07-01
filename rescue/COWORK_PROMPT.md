# Cowork / cold-start prompt — Mekede X20 bootloop rescue

Zkopíruj text níže do Cowork (nebo nového agenta). Je samostatný — obsahuje
všechna fakta o zařízení, hypotézu i rozhodovací strom, takže agent nemusí nic
dohledávat.

---

```
Jsi expert na Android low-level recovery (AVB / dm-verity / dynamic partitions /
Unisoc SPD). Pomáháš mi zachránit MOJE VLASTNÍ head unit z bootloopu. Není
bricknuté, jen bootloop. Autorizace: je to moje zařízení, chci ho zprovoznit.

ZAŘÍZENÍ
  Mekede MN X20 Pro · Unisoc UIS8581A (SC9863A) · Android 10 (QP1A.190711.020)
  board 6316 · super = dynamic partitions (system/vendor/product jsou logical)
  A-only (žádné a/b sloty). Partitions mj.: boot, super, metadata, misc,
  vbmeta, vbmeta_bak, vbmeta_system, vbmeta_vendor (POZOR: system/vendor NEMAJÍ
  _bak variantu).

CO SE STALO
  Root přes Magisk „Direct Install (System)" zapsal přímo do /system → bootloop.
  Obnova stock boot.img nepomohla. Chyba:
     E:[libfs_mgr]Failed to open '/dev/block/dm-2': No such file or directory
  Z funkčních props: dm-2 = root/system (ne /data).

HYPOTÉZA (potvrzená z AVB dokumentace)
  vbmeta (p35) + vbmeta_bak (p36) už mám disabled (flags=3, prázdný vbmeta).
  ALE DISABLE flagy se NEDĚDÍ na chained vbmeta_system / vbmeta_vendor. Ty pořád
  nesou hashtree descriptor = hash původního systému → verity proti Magiskem
  změněnému /system selže → dm-2 se nevytvoří → bootloop.

INJEKTÁŽNÍ BOD
  FYT/lsec OTA hook: soubor lsec_updatesh/8581lsec.sh z USB se během update flow
  spustí jako ROOT — funguje i teď v bootloopu. Executor specifika:
    - každý ŘÁDEK = samostatný `sh -c` (proměnné mezi řádky nepřetrvávají)
    - pipe v rámci řádku funguje
    - stdout i stderr potlačeny → výstup MUSÍ jít do souboru (USB/cache)
    - `reboot` selže → končit sync+sleep, restart ručně tlačítkem RST
    - /dev/block/by-name/ dostupné; USB bývá /storage/sdcard1 nebo /mnt/media_rw/*

PLÁN (rozhodovací strom)
  TIER 0  Diagnostika (nic nemění): sesbírej z lsec pstore/last_kmsg + partitions
          + by-name + vbmeta hlavičky ZPĚT na USB. V kernel logu z minulého bootu
          je kontext pádu dm-2 → rozhodne verity vs. super metadata.
  TIER 1  Přepiš vbmeta_system a vbmeta_vendor prázdným vbmeta_disabled.img
          (dd přes by-name) → odebere hashtree descriptor → verity se pro system
          nenastaví → dm-2 vznikne → boot projde. NEJPRAVDĚPODOBNĚJŠÍ FIX.
  TIER 2  Když Tier 1 selže → plný stock reflash (undo Magisku, přepíše super):
          A) FYT USB update AllAppUpdate.bin (1 GB), nebo
          B) SPD ResearchDownload + PAC pro board 6316; BROM na Unisoc = test
             point/pad na desce (přední USB-A je HOST, ADB/BROM tam nebude).
  TIER 3  Obnova jen system z system.new.dat.br — komplex (system je v super,
          nejde jen dd), Tier 2 je jednodušší.

SOUBORY (na PC)
  Mekede\6316_1\boot.img  (stock, SHA1 75900f451e43be53ba5334229c0e242551b68053)
  Mekede\6316_1\system.new.dat.br · Mekede\AllAppUpdate.bin · Magisk-v27.0.apk
  Mekede\lsec_updatesh\ (obsah USB; new-boot.img zde je Magisk-patched = NEPOUŽÍT)

ÚKOL
  1) Napiš mi lsec skripty (respektuj executor specifika výše: 1 operace/řádek,
     výstup do souboru, by-name, ukončit sync+sleep).
  2) Veď mě Tier 0 → Tier 1 → (Tier 2). U každého kroku řekni přesně co dát na
     USB a jak ověřit výsledek.
  3) Až ti pošlu obsah diag_* logů, potvrď/vyvrať verity hypotézu a uprav plán.
  Bezpečnost: dd jen do malých vbmeta/boot přes by-name; žádný zápis do super/
  userdata/metadata; nikdy neflashuj patchnutý new-boot.img.
```

---

Hotové skripty pro všechny tiery máš vedle v `lsec_updatesh/` — Cowork jimi
můžeš rovnou nakrmit, nebo je nechat vygenerovat znovu podle promptu.
