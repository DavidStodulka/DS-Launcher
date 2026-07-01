CO DÁT NA USB KLÍČ
==================

USB klíč naformátuj FAT32 a vytvoř na něm složku:

    lsec_updatesh/

Do ní vždy dej PRÁVĚ JEDEN skript přejmenovaný na 8581lsec.sh
(FYT executor spouští soubor s tímto přesným jménem) + potřebné .img.

--------------------------------------------------------------------
KROK 0 — DIAGNOSTIKA (doporučeno první, nic nemění)
    lsec_updatesh/
        8581lsec.sh      <- kopie 00-diagnostics.sh
    (žádné .img netřeba)
    Po doběhu vytáhni USB, na PC přečti diag_*.txt.
    Nejdůležitější: diag_pstore_console.txt / diag_last_kmsg.txt.

KROK 1 — HLAVNÍ OPRAVA (Tier 1)
    lsec_updatesh/
        8581lsec.sh          <- kopie 10-disable-vbmeta-system-vendor.sh
        vbmeta_disabled.img  <- tvůj prázdný vbmeta (4096 B, flags=3)
    Spusť, počkej ~15 s, RST. Zkontroluj boot.

KROK 2 — (jen když bys musel) obnova stock boot
    lsec_updatesh/
        8581lsec.sh      <- kopie 20-restore-stock-boot.sh
        stock-boot.img   <- Mekede\6316_1\boot.img (NE new-boot.img!)
--------------------------------------------------------------------

POZNÁMKY K CESTÁM
  Skripty hledají USB automaticky přes:
    /storage/*/lsec_updatesh
    /mnt/media_rw/*/lsec_updatesh
    /storage/usb*/lsec_updatesh
  Pokud tvůj klíč mountuje jinam, řekni mi to z diag_byname.txt /
  diag_partitions.txt a cesty upravím.

BEZPEČNOST
  - Skripty píší dd JEN do malých vbmeta partitions nebo do boot
    přes by-name (žádné natvrdo mmcblk čísla, žádný zápis do super).
  - Nech doběhnout sync + sleep, teprve pak RST.
  - .img soubory (firmware) do gitu NEcommituj — viz rescue/.gitignore.
