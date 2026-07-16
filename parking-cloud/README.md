# ☁️ Parkoviště (cloud) — sdílená online verze

Stejná appka jako ve složce `../parking`, ale data jsou ve **společné online
databázi** (Cloudflare D1). Každý, kdo otevře odkaz, vidí **stejná, aktuální
data** — změny se synchronizují mezi všemi telefony i lidmi a **pamatují se
natrvalo**. Přístup je **otevřený** (kdokoli s odkazem může číst i editovat).

## Architektura

```
Telefon (prohlížeč)  ──HTTPS──►  Cloudflare Worker  ──►  D1 databáze (SQLite)
   public/ (appka)                 src/worker.js            ds-parking-db
```

- **`public/`** — frontend (HTML/JS), volá `/api/*`.
- **`src/worker.js`** — REST API + servírování appky.
- **D1 `ds-parking-db`** — už vytvořená a naplněná 64 vozy (id v `wrangler.toml`).
- Appka se každých 12 s sama obnoví (a při návratu do okna), takže vidíš změny
  ostatních. Indikátor vpravo nahoře ukazuje stav synchronizace.

## Parkoviště Dílna, Kaufmann a Archiv

Vedle Levé a Pravé strany appka nabízí ještě dva „placy" bez řad — **Parkoviště
Dílna** a **Parkoviště Kaufmann**. Auta se tam řadí pod sebe metodou **FIFO**
(nové na konec), pořadí jde stejně jako v řadě přeskupit podržením prstu.

- V detailu vozu tlačítka **🔧 Přeskladnit do Dílny** / **🏭 Přeskladnit do
  Kaufmannu** vůz okamžitě přesunou (uloží i rozpracované změny) a nastaví mu
  odpovídající stav.
- Návrat na Levou/Pravou stranu (i mezi Dílnou a Kaufmannem navzájem) jde přes
  stejný výběr **Strana/Plac · Řada · Pozice** jako běžný přesun.
- Tlačítko **🗄 Po předprodeji** v detailu vozu ho vyřadí ze všech aktivních
  parkovišť. V **Archivu** (menu → Archiv) zůstane natrvalo jen VIN a číslo
  klíče — bez možnosti obnovy. Archiv má vlastní vyhledávání, nezávislé na
  hledání v aktivních parkovištích.

## API

| Metoda | Cesta | Co dělá |
|--------|-------|---------|
| GET | `/api/state` | vrátí všechny vozy (aktivní parkoviště, vč. Dílny/Kaufmannu) |
| POST | `/api/vehicles` | přidá vůz (pozice se dopočítá; `side` = `left`/`right`/`dilna`/`kaufmann`) |
| PUT | `/api/vehicles/:id` | upraví vůz (model, VIN, klíč, poznámka, stav) |
| DELETE | `/api/vehicles/:id` | smaže vůz |
| POST | `/api/move` | přesun/přeskladnění `{side,row,order}` (i mezi Dílnou/Kaufmannem) |
| POST | `/api/rows/delete` | smaže celou řadu `{side,row}` |
| POST | `/api/reset` | obnoví původních 64 vozů |
| POST | `/api/archive` | vyřadí vůz po předprodeji `{id}` — do archivu jen VIN + klíč |
| GET | `/api/archive` | seznam vyřazených vozů (VIN + klíč) |

## Nasazení

### Automaticky (GitHub Actions)

Po prvním ručním nasazení (níže) se appka dál nasazuje **sama** — workflow
`.github/workflows/deploy-parking-cloud.yml` při každém pushi do `main`,
který se týká `parking-cloud/**`, spustí testy a pak `wrangler deploy`.

Potřebuje to jen jednou nastavit GitHub secret s Cloudflare API tokenem:

1. Vytvoř token: **https://dash.cloudflare.com/profile/api-tokens** →
   *Create Token* → šablona **„Edit Cloudflare Workers"** (případně přidej
   i právo na D1, pokud šablona nepokrývá databázi).
2. V GitHubu: repo → **Settings → Secrets and variables → Actions → New
   repository secret** → název `CLOUDFLARE_API_TOKEN`, hodnota = token.

Od té chvíle stačí mergovat do `main` a appka se nasadí sama (průběh vidíš
v záložce **Actions**).

### Ručně (první nasazení / lokální test)

```bash
cd parking-cloud
npx wrangler login      # otevře prohlížeč, přihlas se do Cloudflare
npx wrangler deploy     # nasadí Worker + appku
```

Po dokončení vypíše veřejnou adresu, např.
`https://ds-parking.<tvůj-účet>.workers.dev` — **to je ten sdílený odkaz**.
Pošli ho komukoli, na telefonu pak prohlížeč → *Přidat na plochu*.

> Vlastní doména: po nasazení v Cloudflare dashboardu *Workers → ds-parking →
> Settings → Domains & Routes* můžeš přidat vlastní URL.

## Lokální vývoj / test

```bash
npm install
npm test                # otestuje API logiku (mock D1), 36 kontrol
npx wrangler dev        # lokální běh (potřebuje wrangler login kvůli D1)
```

## Změna dat napřímo (volitelné)

DB jde upravovat i z Cloudflare dashboardu (*Storage & Databases → D1 →
ds-parking-db → Console`) nebo přes `npx wrangler d1 execute ds-parking-db
--command "SELECT * FROM vehicles"`.
