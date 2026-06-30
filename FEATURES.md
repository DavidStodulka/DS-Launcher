# CarOS DS-Launcher — Kompletní přehled funkcí

Automotive Android launcher pro Seat León 5F na head-unit Mekede MN X20 Pro  
(Android 10, API 29, 1280 × 720, landscape)

---

## 1. CAN dekodér

Čte raw ASCII rámce z `/dev/ttyS1` (vestavěný adaptér) nebo přes vLinker MC+ (Bluetooth OBD2 ELM327).  
Parser je stavový — drží si poslední hodnotu každého signálu a emituje `CANFrame` snapshot po každém příchozím rámci.

### Dekódované signály

| CAN ID | Signál | Detail |
|--------|--------|--------|
| `0x280` / `0x285` | Otáčky + škrtící klapka | RPM = raw/4, Throttle = byte/255 × 100 % |
| `0x288` | Boost tlak (MAP) | kPa = raw/10 |
| `0x289` | MAF senzor | g/s = raw/100 |
| `0x28F` | Korekce paliva | STFT + LTFT ve % (signed byte) |
| `0x320` | Rychlost (přístrojová deska) | km/h = raw/100 |
| `0x350` | Rychlosti všech 4 kol | FL/FR/RL/RR v km/h, skluz = |průměr přední − zadní| |
| `0x368` | ESP G-force + stav systémů | lat/long G v miliG, ESP/TC/ABS active bity |
| `0x0C6` | EPS úhel volantu | stupně + úhlová rychlost °/s |
| `0x470` | Teplota chladicí kapaliny | °C = byte − 40 |
| `0x588` | Teplota oleje | °C = byte − 40 |
| `0x540` | DSG převodovka | stupeň (P/R/N/D/1–6), teplota spojky, teplota oleje |
| `0x55E` | Napětí baterie | mV / 1000 = V |
| `0x271` | Stav zapalování | 0=off, 1=ACC, 2=ON, 3=START |
| `0x570` / `0x575` | Klimatizace (Climatronic) | nastavená teplota, vnitřní teplota, ventilátor, AC, recirc, distribuce |
| `0x65D` | DPF stav | zátěž %, diferenciální tlak kPa, čas poslední regenerace |
| `0x65E` | DPF aktivní regenerace | flag isRegenActive |
| `0x60D` | Dveře / pásy / světla | bitmask otevřených dveří, zapnutých pásů, světel |
| `0x5C0` | Páčky (stalk) | stěrače (OFF/INTERVAL/LOW/HIGH), blinkry (NONE/LEFT/RIGHT/HAZARD) |
| `0x3E3` | TPMS | tlak každé pneumatiky v kPa (volitelná výbava) |
| `0x60A` | Odometer | celkový stav v km |

---

## 2. VAG rozšířená diagnostika — vLinker MC+ (Mode 22 / UDS)

Engine pro asynchronní dotazování ECU přes ELM327 každých 10 sekund.  
Všechny hodnoty jsou specifické pro 1.6 TDI EA288.

### Co se čte

| Oblast | Hodnota | Co říká |
|--------|---------|---------|
| **Vstřikovače** | Korekce cyl. 1–4 v mg/zdvih | Odchylka > ±2 mg = opotřebený vstřikovač; > ±4 mg = výměna |
| **EGT** | Teplota výfukových plynů senzory 1–4 | Přehřátí > 850 °C = isOvertemp flag |
| **VNT turbo** | Aktuální vs. požadovaná poloha lopatek (%) | Rozdíl > 10 % = isSticking (zaseklé lopatky — typická závada EA288) |
| **EGR ventil** | Aktuální vs. požadovaná poloha (%) | Rozdíl > 5 % = isStuck |
| **Swirl klapky** | Poloha (%) | Zaseklá = studený start, bílý kouř |
| **DPF thermal** | Teplota upstream + downstream | Delta < 20 °C při regeneraci = ucpaný filtr |
| **Common rail** | Tlak v bar | Idle ~250 bar, full load ~1800 bar |
| **Žhavicí svíčky** | Odpor každé svíčky v mΩ | < 100 nebo > 800 mΩ = anyFailed flag |
| **Hladina paliva** | Mode 01 PID 0x2F | Skutečné % + litry, přesný odhad dojezdu |

---

## 3. Spotřeba paliva (real-time)

**Primární metoda:** MAF senzor  
`fuelLph = (mafGs / 43) / 0.832 × 3600`  
Diesel λ ≈ 3, efektivní AFR = 43, hustota nafty = 832 g/L

**Záložní metoda:** rychlost + škrtící klapka (empirický polynomiální model kalibrovaný na 1.6 TDI)

**Odhad dojezdu:**  
Pokud je dostupná hladina paliva z OBD (Mode 22 / PID 0x2F), používá skutečný obsah nádrže.  
Jinak odhaduje spotřebu z průběhu jízdy.

| Zobrazená hodnota | Popis |
|-------------------|-------|
| Okamžitá spotřeba | L/100 km (99 = volnoběh / stání) |
| Průměrná spotřeba | L/100 km za celou jízdu |
| Ujetá vzdálenost | km od startu session |
| Spotřeba | litry za jízdu |
| Odhad dojezdu | km do prázdné nádrže |

**FuelMeterView** — obloukový canvas widget s barevným přechodem (zelená → červená dle zbývajícího dojezdu).

---

## 4. DPF monitor a predikce

### DPFMonitor
Analyzuje posledních 100 telemtrických rámců:
- Vypočítá trend zanesení v %/hodinu
- Detekuje regeneraci: pokles ≥ 15 % za 10 rámců = regenerace proběhla
- Vydá jedno ze 4 doporučení:

| Zátěž | Doporučení |
|-------|-----------|
| 0–60 % | OK |
| 60–75 % | Doporučena jízda po dálnici |
| 75–85 % | Nutná jízda po dálnici |
| > 85 % | Servis |

### DPFPredictorEngine
Odhaduje počet km do spuštění aktivní regenerace (trigger = 75 % zátěže):
- Pokud je dostupný trend z DPFMonitoru, převede ho z %/hod na %/km (÷ 25 km/h průměr město)
- Jinak použije konzervativní odhad 0.25 %/km

### DPFStatusView
Animovaný obloukový gauge (0–100 %) s plynulou animací 600 ms:
- Zelená < 60 %, žlutá 60–75 %, oranžová 75–85 %, červená > 85 %
- Pod obloukem: text "regen za ~150 km" nebo "regenerace nyní"
- Barevný odznak s doporučením

---

## 5. Servisní poradce

Sleduje 8 servisních položek a upozorní notifikací na URGENT stav:

| Položka | Interval | URGENT | WARNING | INFO |
|---------|----------|--------|---------|------|
| Olej | z OilLifeCalculator | ≤ 0 % | ≤ 15 % | ≤ 25 % |
| DPF | z DPFMonitor | dle doporučení | — | — |
| DSG olej | 60 000 km | ≤ 0 km | ≤ 5 000 km | ≤ 10 000 km |
| Chladicí kapalina | 4 roky | ≤ 0 dní | ≤ 60 dní | ≤ 90 dní |
| Vzduchový filtr | 30 000 km | ≤ 0 km | ≤ 5 000 km | ≤ 10 000 km |
| Palivový filtr | 50 000 km | ≤ 0 km | ≤ 5 000 km | ≤ 10 000 km |
| Žhavicí svíčky | 100 000 km | ≤ 0 km | ≤ 5 000 km | ≤ 10 000 km |
| Rozvodový řemen | 120 000 km / 5 let | ≤ 0 km | ≤ 20 000 km | — |

---

## 6. Telemetrie a jízdní styl

### Záznam
- Foreground GPS service — záznam každé jízdy do Room databáze (WAL mode)
- Session START: rychlost > 0 po 3 po sobě jdoucích měřeních
- Session END: rychlost = 0 po 60 s nebo příjem ACC_OFF broadcastu

### Analýza po jízdě — 4 skóre (0–100)

**Eco skóre:**  
`100 − (průměrná škrtící klapka × 0.4 + % rámců nad 3000 RPM × 0.6)`

**Sport skóre:**  
`(průměrné longG × 20) + (% rámců nad 4000 RPM × 0.5) + (max latG × 15)`

**Mechanical skóre:**  
Začíná na 100, odečítá:
- Škrtící klapka > 20 % při zahřívání (první 3 min, chladivo < 60 °C)
- Každý rámec s teplotou oleje > 115 °C = −0.05 bodů

**Smoothness skóre:**  
`100 − směrodatná odchylka změn škrtící klapky × 2`

### Doporučení (příklady)
- Eco < 40: *"Significant fuel waste detected — shift up earlier…"*
- Mechanical < 40: *"Engine was worked hard before reaching operating temperature…"*
- Sport > 80: *"Very dynamic session — ensure tyres and brakes are in good condition."*

---

## 7. Detekce agresivní jízdy

Real-time, volaný při každém GPS/CAN updatu (~2 Hz).

**Preferuje hardwarový G-force z ESP CAN frame (0x368), záložně GPS výpočet.**

| Typ | Práh | TTS výstraha |
|-----|------|--------------|
| Tvrdé brzdění | longG < −0.40 g | "Tvrdé brzdění" |
| Ostrý rozjezd | longG > +0.35 g | — |
| Ostrá zatáčka | |latG| > 0.35 g | "Ostrá zatáčka" |

- TTS debounce: 3 sekundy na typ (neflooduje)
- Session score 100 → 0, každá událost odečítá 1–5 bodů dle závažnosti
- Historie událostí dostupná jako `StateFlow<List<AggressiveEvent>>`

---

## 8. Race Chrono

Přesná výkonová měření:

| Měření | Popis |
|--------|-------|
| 0–100 km/h | Čas od startu na 100 km/h |
| 0–200 km/h | Čas od startu na 200 km/h |
| 80–120 km/h | Průjezdový čas (overtaking) |
| Brzdná dráha | Čas a vzdálenost ze 100 km/h na 0 |
| Vlastní rozsah | Libovolný rychlostní interval |
| Okruhové časy | GPS-based s 50m rádiusem cílové čáry, debounce 10 s, sektory |

G-force: primárně GPS (dv/dt / 9.81), záložně akcelerometr.

---

## 9. Prediktivní navigace

Učí se vzory destinací z historických jízd.

- Při konci každé jízdy GPS souřadnice destináce uloží do Room DB
- Vzory se slučují: stejný den v týdnu ± 1 hodina, do 300 m od uložené destinace → inkrementuje počítadlo
- Reverz-geokódování adresy přes systémový Geocoder
- Automaticky čistí vzory starší 90 dní
- **Po nastartování nabídne dialog:** "Navigovat do: [adresa]?" pokud current den+hodina matchuje vzor s ≥ 2 výskyty

---

## 10. Offline mapa s telemetrickým overlayem

- **OSMDroid 6.1.18** — dlaždice uložené offline na `/sdcard/CarOS/maps/cache/`
- Výchozí střed: Praha (50.0755°N, 14.4378°E), zoom 13
- Spinner posledních 10 zaznamenaných sessions
- Max 500 bodů na trasu (subsampling pro výkon)
- Přepínání barevného kódování tlačítkem:

**Dle rychlosti:**
- Zelená < 60 km/h / Žlutá < 100 / Oranžová < 120 / Červená ≥ 120

**Dle G-force:**
- Zelená < 0.20g / Žlutá < 0.40g / Oranžová < 0.55g / Červená ≥ 0.55g

- Automatický zoom na bounding box trasy
- Tmavé UI (#1A1A2E, #16213E)

---

## 11. Elevace a trasy

### ElevationTracker
- GPS listener 500 ms / 0 m
- Výpočet sklonu: `(Δalt / vzdálenost) × 100 %`
- Ukládá až 50 000 bodů (prevence přetečení paměti)
- Manuální kalibrace nadmořské výšky od referenčního bodu
- Celkové stoupání a klesání

### RouteRecorder
- Orchestruje ElevationTracker + GPX export + Room DB
- Uloží trasu jako GPX XML + metadata (délka, stoupání, klesání)
- `startRecording(name)` / `stopAndSave(): Long` / `cancelRecording()`

---

## 12. Automatizační engine

8 vestavěných pravidel, neomezený počet vlastních. Princip: **edge-triggered** (akce se spustí jen při přechodu false → true, nikoliv opakovaně dokud podmínka platí).

### Podmínky
- `SpeedAbove(kmh)` / `SpeedBelow(kmh)`
- `CoolantTempAbove(celsius)`
- `VoltageBelow(volts)`
- `DPFLoadAbove(pct)`
- `ACCOn` / `ACCOff`
- `TimeOfDay(hour, minute)`

### Akce
- `LaunchApp(packageName)`
- `SetBrightness(level 0–255)`
- `ShowNotification(title, message)`
- `ExecuteShell(command)` — vyžaduje root
- `SetDrivingMode` / `SetParkedMode`
- `SpeakTTS(text)` — česky
- `PublishMQTT(topic, value)` — přes Termux bridge
- `SetAudioProfile(profileId)`

### Vestavěná pravidla

| Pravidlo | Podmínka | Akce |
|----------|----------|------|
| Jízdní mód | Rychlost > 5 km/h | Omezit UI |
| Parkování | Rychlost < 2 km/h | Plné UI |
| DPF upozornění | DPF > 80 % | Notifikace (česky) |
| DPF TTS | DPF > 80 % | TTS "Filtr pevných částic je zanesený…" |
| Baterie | Napětí < 11.8 V | Notifikace |
| Chladivo | Teplota > 110 °C | Notifikace |
| Noční jas | 20:00 | Jas = 80/255 |
| Noční audio | 20:00 | EQ profil "night" |

---

## 13. Adaptivní EQ (10 pásem)

Automaticky upravuje zvuk podle jízdní situace. Výpočet probíhá ve 5 vrstvách každé 2 sekundy s exponenciálním vyhlazením (85 % stará hodnota, 15 % nový cíl).

| Vrstva | Efekt |
|--------|-------|
| 1. Bass preference | Trvalé zvýraznění nízkých frekvencí (+3/+4/+2.5 dB na 31/62/125 Hz) |
| 2. Road noise compensation | Při jízdě: bass −2 dB / výšky +2 dB (normalizováno na 130 km/h) |
| 3. Loudness (Fletcher-Munson) | Při malé hlasitosti: bass a výšky nahoru (kompenzace fyziologie sluchu) |
| 4. Audio source profile | BT telefon: hlasy + výšky; FM rádio: méně basů; Streaming: lehce výšky |
| 5. Noční kompenzace | Po 22:00 nebo hlasitost < 5: méně basů, víc středů |

Rozsah každého pásma: ±12 dB. Uživatel může přidat vlastní offset k automatice.

### Předdefinované EQ profily (5)
Flat / Bass / Sport / Vocal / Night

---

## 14. Hlasové ovládání

### Online: Gemini API
- API klíč uložen šifrovaně v EncryptedSharedPreferences (AES256-GCM)
- Volné konverzační dotazy na stav auta

### Offline: Vosk (bez internetu)
- Lokální model běžící na zařízení

### Spouštění
- Tlačítkem na volantu (kalibrovatelné — záznam keycode přes EncryptedSharedPreferences)
- Tlačítkem v UI

### Offline příkazy (OfflineCommandMatcher)

| Kategorie | Příklady |
|-----------|---------|
| **Spouštění appek** | "spusť spotify", "spusť waze", "spusť youtube" |
| **Média** | "hraj muziku", "zastav přehrávání", "další skladba", "předchozí" |
| **Hlasitost** | "hlasitěji", "ztiš to" |
| **EQ profily** | "noční profil", "sportovní mód", "basy", "flat" |
| **EQ úpravy** | "zvyš basy", "zvyš výšky", "auto eq" |
| **Stav auta** | "jaká je rychlost", "kolik mám otáčky", "jaký je stav dpf" |
| **Systém** | "zapni wifi", "ztlum displej", "rozsvítit displej" |
| **Zrušit** | "zrušit", "cancel" |

---

## 15. Multimedia

- **Media3 / ExoPlayer** — playback s notifikací a ovládáním z lock screenu
- **MediaPlaybackService** — foreground service, media session pro Android Auto kompatibilitu
- Ovládání hlasem nebo tlačítky na volantu

---

## 16. OBD replay mode

### OBDSessionRecorder
- Zaznamenává live OBD komunikaci (příkaz + odpověď) do JSON souboru
- Ukládá na `/sdcard/CarOS/telemetry/obd_session_<timestamp>.json`
- `startRecording()` / `record(cmd, response)` / `stopAndSave()`

### OBDSessionPlayer
- Načte JSON soubor a odpovídá na příkazy jako by byl reálný adaptér
- Vyhledávání: přesná shoda → substring → null (fallback na mock)
- Nový `ConnectionType.REPLAY` v OBDConnection

**Použití:** testování VCDS obrazovek bez připojeného auta nebo přehrání starší diagnostické session.

---

## 17. Mock CAN simulátor

Hardwarově nezávislá simulace Seat León 5F pro vývoj bez auta.

### Simulovaný jízdní cyklus
- 0–5 s: Studený start, volnoběh 780 RPM
- 5–60 s: Rozjezd 0 → 130 km/h, řadí 1–6
- 60–180 s: Dálniční jízda ~130 km/h s ±3 km/h driftem
- 180–240 s: Zpomalení 130 → 0 km/h
- 240+ s: Stání/volnoběh

### Simulované hodnoty
- Tepelný model: chladivo 20 → 90 °C za 120 s, olej lags s 30s zpožděním
- DPF: 45 % → narůstá 0.142 %/s → reset na 20 % při dosažení 85 %
- Boost tlak: simulace turbo lag s filtrem 0.05
- Aktivní DTC kódy: P0420 + P0300 od 30 s
- GPS: trasa v okolí Barcelony (41.39°N, 2.17°E) s výškovým profilem
- Emituje i raw ASCII řádky — CANParser z nich čte identicky jako ze skutečného hardware

---

## 18. Profily jízdy a řízení displeje

### Jízdní mód (hystereze)
- **DRIVING:** nastaven při rychlosti ≥ 5 km/h → omezené UI, větší číslice
- **PARKED:** nastaven při rychlosti < 2 km/h → plné UI
- Pásmo 2–5 km/h zachovává aktuální stav (bez problikávání)

### DriveProfiles
Profily s nastavením jasu, hlasitosti médií a EQ:
- DAILY / NIGHT / SPORT / HIGHWAY — přepínají všechny parametry naráz
- Persistováno v SharedPreferences, obnoveno po restartu

---

## 19. Power management

### DeepSleepManager (parkovací režim)
Po vypnutí zapalování (ACC OFF):
1. Ztmaví displej na 0
2. Přepne CPU governor na `powersave` (všechny core)
3. Vypne WiFi (~200 mW úspora)
4. Drží PARTIAL_WAKE_LOCK (CPU běží, monitoruje ACC)
5. Vypne obrazovku

Probuzení probíhá opačně + broadcastuje `ACTION_WAKE_FROM_SLEEP`.

### ShutdownManager (vypnutí)
Sekvence graceful shutdown:
1. Broadcast `ACTION_SAVE_STATE` → čeká 2 s na uložení dat
2. `su -c 'reboot -p'` (ACPI power-off)
3. `su -c 'poweroff'`
4. Android `ACTION_SHUTDOWN` broadcast
5. `PowerManager.shutdown()` přes reflexi

Lze naplánovat s prodlevou nebo spustit okamžitě.  
Broadcast `ACTION_SHOW_SHUTDOWN_OVERLAY` zobrazí odpočet v UI.

---

## 20. Root management

Detekce `su` binárky (7 standardních cest + Magisk) + ověření uid=0.

### Co se self-grantem nastaví automaticky (16 oprávnění)
`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`,  
`READ_PHONE_STATE`, `CALL_PHONE`, `READ_CONTACTS`, `RECEIVE_SMS`, `READ_SMS`,  
`RECORD_AUDIO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`,  
`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `PACKAGE_USAGE_STATS`,  
`MANAGE_MEDIA`, `MODIFY_AUDIO_SETTINGS`

### Ostatní root operace
- `chmod 0666 /dev/ttyS1` a všechna `/dev/ttyUSB*`
- Nastavení systémových properties (`setprop` / `getprop`)
- Remount filesystem rw
- `withRootFallback<T>()` — helper pro operace s fallback hodnotou bez root

---

## 21. Databáze (Room)

Tři schema migrace: 1→2→3

| Tabulka | Obsah |
|---------|-------|
| `telemetry_sessions` | Metadata jízd (čas, vzdálenost, jízdní skóre) |
| `telemetry_frames` | Každý CAN rámec (GPS, rychlost, G-force, teploty…) |
| `service_records` | Historie servisních úkonů s km a datem |
| `route_predictions` | Naučené vzory destinací (den v týdnu, hodina, souřadnice, počet výskytů) |
| `routes` | Uložené GPX trasy s elevačním profilem |
| `driving_scores` | Výsledky DrivingStyleAnalyzer per session |

---

## 22. UI přehled

| Obrazovka / widget | Popis |
|-------------------|-------|
| **MainFragment** | Rychlost, stupeň, DPF gauge, stav dveří, DTC badge, přepínání s jízdním módem |
| **RightPanelFragment** | Teplota chladicí kapaliny, napětí baterie, stav pásů, OBD stav |
| **FuelMeterView** | Obloukový canvas widget: okamžitá spotřeba, průměr, vzdálenost, dojezd |
| **DPFStatusView** | Animovaný oblouk zátěže, km do regenerace, doporučení |
| **MapActivity** | Offline OSMDroid mapa se session selectorem a barevnou trasou |
| **LiveDataFragment** | Tabulka všech live OBD PIDů v reálném čase |
| **DrivingStyleFragment** | Výsledky analýzy jízdního stylu s grafy skóre |
| **ElevationProfileView** | Canvas widget s výškovým profilem trasy |
| **ServiceAdvisor UI** | Seznam servisních položek se stavovými barvami a dnech/km do servisu |
| **VCDS / DTC scan** | Čtení a mazání DTC kódů, kódovací presety |
| **TDI diagnostika** | Live hodnoty z VAGExtendedPIDEngine (vstřikovače, EGT, VNT, EGR…) |

---

## Technologie a architektura

| Oblast | Stack |
|--------|-------|
| Jazyk | Kotlin, Coroutines, Flow |
| DI | Hilt 2.48 |
| Architektura | MVVM + StateFlow |
| DB | Room 2.6, WAL mode, FK enforcement |
| CAN | Raw ASCII parser, VAG PQ35/PQ46 ID mapa |
| OBD2 | ELM327 přes BT (vLinker MC+), USB, UART |
| Offline mapy | OSMDroid 6.1.18 |
| ASR offline | Vosk 0.3.47 |
| ASR online | Gemini API (OkHttp 4.12) |
| Media | Media3 / ExoPlayer |
| Security | EncryptedSharedPreferences AES256-GCM |
| Logging | Timber |
| Build | Gradle, kapt, ProGuard |
