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

## API

| Metoda | Cesta | Co dělá |
|--------|-------|---------|
| GET | `/api/state` | vrátí všechny vozy |
| POST | `/api/vehicles` | přidá vůz (pozice v řadě se dopočítá) |
| PUT | `/api/vehicles/:id` | upraví vůz (model, VIN, klíč, poznámka, stav) |
| DELETE | `/api/vehicles/:id` | smaže vůz |
| POST | `/api/rows/delete` | smaže celou řadu `{side,row}` |
| POST | `/api/reset` | obnoví původních 64 vozů |

## Nasazení — jediný krok pro tebe

Databáze i kód jsou hotové. Stačí Worker jednou nasadit pod tvým Cloudflare
účtem (já na to nemám oprávnění/token):

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
npm test                # otestuje API logiku (mock D1), 18 kontrol
npx wrangler dev        # lokální běh (potřebuje wrangler login kvůli D1)
```

## Změna dat napřímo (volitelné)

DB jde upravovat i z Cloudflare dashboardu (*Storage & Databases → D1 →
ds-parking-db → Console`) nebo přes `npx wrangler d1 execute ds-parking-db
--command "SELECT * FROM vehicles"`.
