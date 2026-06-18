# 🅿️ Parkoviště — správa vozidel

Rychlá webová aplikace pro správu parkování vozidel v řadách. Optimalizovaná
pro mobily (Android i iPhone), funguje i offline a jde si ji „přidat na plochu"
jako appku. Žádný server, žádná instalace — jen otevřít odkaz.

## Co umí

- **Vizuální rozložení** — vozidla jako barevné čtverce v řadách, zvlášť **Levá**
  a **Pravá strana**.
- **Evidence vozu** — model, **VIN / číslo vozu**, **číslo klíče**, poznámka.
- **Stav vozu** — u každého vozu po rozkliknutí dvě hlavní tlačítka:
  **🔧 Dílna** a **🆕 Nově přijato** (+ zpět na *Zaparkováno*). Stav je barevně
  vidět přímo na čtverci.
- **Editace pozic** — přidávání a mazání vozidel i celých řad.
- **Hledání a filtry** — podle modelu, VINu, klíče; filtr podle stavu.
- **Záloha** — Export / Import / Sdílení dat (`.json`) pro přenos mezi telefony.
- **Offline** — díky service workeru funguje i bez signálu.

Data se ukládají **do prohlížeče telefonu** (localStorage). Pro přenos na jiné
zařízení použij v menu **Export** a na druhém telefonu **Import**.

## Spuštění

- **Lokálně:** otevři `index.html` v prohlížeči. (Pro plnou funkci PWA a service
  workeru spusť přes jednoduchý server, např. `python3 -m http.server` a otevři
  `http://localhost:8000/`.)
- **Online (zdarma):** zapni GitHub Pages v *Settings → Pages → Source: GitHub
  Actions*. Po sloučení do `main` workflow `.github/workflows/pages.yml` nasadí
  appku automaticky a poběží na adrese `https://<uživatel>.github.io/<repo>/`.
  (Alternativně *Deploy from branch* — pak je appka na
  `https://<uživatel>.github.io/<repo>/parking/`.)

Na telefonu pak v prohlížeči otevři odkaz → menu prohlížeče →
**Přidat na plochu**. Appka se chová jako nativní.

## Přidání na plochu

- **Android (Chrome):** ⋮ → *Přidat na plochu / Nainstalovat aplikaci*.
- **iPhone (Safari):** Sdílet → *Přidat na plochu*.

## Struktura

| Soubor | Popis |
|--------|-------|
| `index.html` | UI a styly |
| `app.js` | logika (render, editace, ukládání, import/export) |
| `data.js` | výchozí seznam vozů (vygenerováno z původního TXT) |
| `manifest.webmanifest`, `sw.js`, `icon*.svg` | PWA (instalace + offline) |

Výchozí seznam obsahuje **64 vozidel** (Levá strana: řady 1–3, 8–10; Pravá
strana: řady 1–14). Tlačítko **Obnovit původní seznam** v menu kdykoli vrátí
tento stav.
