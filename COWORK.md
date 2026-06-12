# CarOS — Průvodce pro vývojáře

Automotive launcher pro Seat Leon 5F ST 1.6 TDI, head unit Mekede MN X20 Pro (Android 10, 1280×720).

---

## Obsah

1. [Co to je](#co-to-je)
2. [Technický stack](#technický-stack)
3. [Jak to rozjet lokálně](#jak-to-rozjet-lokálně)
4. [Struktura projektu](#struktura-projektu)
5. [Klíčové koncepty](#klíčové-koncepty)
6. [Jak přidat novou funkci](#jak-přidat-novou-funkci)
7. [CAN bus — specifika](#can-bus--specifika)
8. [Nasazení na head unit](#nasazení-na-head-unit)
9. [Časté chyby](#časté-chyby)
10. [Stav projektu](#stav-projektu-12-6-2026)
11. [Otevřené úkoly](#otevřené-úkoly)

---

## Co to je

CarOS nahrazuje výchozí launcher head unitu. Na obrazovce 1280×720 zobrazuje:

- **Střed** — mapa / médium / aktuální fragment
- **Levý panel** — rychlé akce (FM, Klima, Spotify, Waze…)
- **Pravý panel** — živá data z CAN (rychlost, otáčky, teplota chladiče, klima)
- **StatusBar** — hodiny, GPS, teplota, MQTT badge, hlasový indikátor

Veškerá data z auta jdou přes CAN bus na `/dev/ttyS1` → `CANParser` → `CANFrame` → `MainViewModel` → UI.

---

## Technický stack

| Vrstva | Technologie |
|--------|-------------|
| Jazyk | Kotlin 1.9 |
| DI | Hilt 2.48 |
| Databáze | Room 2.6.1 |
| Navigace | Navigation Component 2.7.7 |
| Async | Coroutines + StateFlow |
| UI binding | ViewBinding (žádný Compose) |
| Media | Media3 / ExoPlayer |
| HTTP | OkHttp 4.12 (Gemini API) |
| Build | Gradle 8, JDK 17 |
| min/targetSdk | 28 / 29 (Android 9/10) |

---

## Jak to rozjet lokálně

### Požadavky

- Android Studio Hedgehog nebo novější
- JDK 17
- Android SDK API 34 (compileSdk), emulátor nebo fyzický telefon pro základní UI testy

### Kroky

```bash
git clone https://github.com/DavidStodulka/DS-Launcher.git
cd DS-Launcher
git checkout main          # nebo claude/magical-lovelace-k3nrY pro nejnovější změny
```

Otevři v Android Studiu → **Sync Project with Gradle Files** → **Run**.

> CAN bus a root funkce bez head unitu nebudou fungovat — app se na to adaptuje automaticky
> přes `MockCANSource` (viz [CAN bus sekce](#can-bus--specifika)).

### Mock režim

V `app/build.gradle` je `buildConfigField "boolean", "ENABLE_LOGGING", "true"` pro debug.  
`MockCANSource` generuje syntetická data pokud `/dev/ttyS1` není dostupný — žádné nastavení není potřeba.

---

## Struktura projektu

```
app/src/main/
├── java/com/caros/
│   ├── audio/              # AudioEngineManager, AdaptiveEQEngine, JamesDSP/V4A bridge, AudioProfile
│   ├── automation/         # Automatizační pravidla
│   ├── can/                # CANFrame, CANParser, CANWriter, CANReader, MockCANSource
│   ├── communication/      # NotificationOverlay (TYPE_APPLICATION_OVERLAY)
│   ├── core/               # CarOSApplication, ShellExecutor, ServiceHealthMonitor, WatchdogService
│   ├── db/                 # Room databáze (v4), entity, DAO
│   ├── diagnostics/        # OBD/VCDS diagnostika
│   ├── elevation/          # Nadmořská výška a sklon
│   ├── fuel/               # InstantFuelComputer (MAF → L/100km)
│   ├── multimedia/         # FMController, FMRadioViewModel, AndroidAutoManager, UsbConnectionReceiver
│   ├── power/              # ACCPowerManager, DeepSleepManager, ShutdownManager
│   ├── profiles/           # Profily (jízdní režimy)
│   ├── race/               # Měření 0–100, AggressiveDrivingDetector, GForceCalculator
│   ├── service/            # MediaPlaybackService, DPFPredictorEngine, OilLifeCalculator
│   ├── system/             # SystemSettings(Manager), AutomationEngine, AppManager, OTA
│   ├── telemetry/          # TelemetryService, DrivingStyleAnalyzer, RoutePredictorEngine
│   ├── termux/             # Termux bridge, MQTT publisher, TermuxServiceMonitor
│   ├── ui/
│   │   ├── audio/          # AudioFragment, EQFragment, ViperFragment
│   │   ├── climate/        # ClimateFragment, ClimateViewModel
│   │   ├── health/         # SystemHealthFragment — self-diagnostika CarOS
│   │   ├── main/           # MainActivity, MainViewModel, LeftPanelFragment, RightPanelFragment
│   │   ├── media/          # MediaFragment, FMRadioFragment
│   │   ├── race/           # RaceFragment
│   │   ├── settings/       # SettingsFragment
│   │   ├── telemetry/      # TelemetryFragment, DrivingStyleFragment
│   │   ├── vcds/           # VcdsFragment + sub-fragmenty (Live, Faults, Coding)
│   │   └── voice/          # VoiceSetupFragment
│   ├── vcds/               # AdaptationManager, LongCodingEditor, UDSProtocol
│   ├── views/              # Custom views: StatusBarView, ACControlView, VoiceWaveView, …
│   └── voice/              # VoiceInputManager, GeminiCommandProcessor, VoiceCommandExecutor, TTS
│
├── res/
│   ├── layout/             # 30 XML layoutů
│   ├── navigation/         # nav_graph.xml — 15 fragmentů
│   ├── drawable/           # ~50 vectorových ikon
│   └── values/             # colors, strings, themes
│
└── assets/
    └── scripts/            # bridge.py, termux_setup.sh, mosquitto.conf.template

magisk_modules/
├── caros_audio/            # Detekce JDSP/V4A, chmod /dev/snd/*
└── caros_termux/           # Autostart Termux bridge při bootu
```

### Navigační destinations (nav_graph.xml)

| ID | Fragment | Popis |
|----|----------|-------|
| `mainFragment` | MainFragment | Domovská obrazovka |
| `mediaFragment` | MediaFragment | Media přehrávač |
| `fmRadioFragment` | FMRadioFragment | FM rádio + větrák |
| `climateFragment` | ClimateFragment | Klimatizace |
| `audioFragment` | AudioFragment | EQ, profily, AdaptiveEQ |
| `vcdsFragment` | VcdsFragment | VCDS — Live Data / Fault Codes / Coding |
| `raceFragment` | RaceFragment | Měření výkonu |
| `diagnosticsFragment` | DiagnosticsFragment | OBD diagnostika |
| `telemetryFragment` | TelemetryFragment | Telemetrie |
| `drivingStyleFragment` | DrivingStyleFragment | Skóre jízdního stylu |
| `elevationFragment` | ElevationFragment | Profil výšky trasy |
| `serviceAdvisorFragment` | ServiceAdvisorFragment | Servisní záznamy |
| `ecosystemFragment` | EcosystemFragment | Termux / MQTT status |
| `voiceSetupFragment` | VoiceSetupFragment | Hlasové ovládání — nastavení |
| `settingsFragment` | SettingsFragment | Nastavení aplikace |
| `systemHealthFragment` | SystemHealthFragment | Zdraví systému — CPU/RAM/pády/watchdog/moduly |

---

## Klíčové koncepty

### CANFrame — všechna pole jsou nullable

```kotlin
// CANFrame má ~20 nullable polí — vždy používej safe-call
val speed = frame.vehicleSpeed?.kmh ?: 0f
val rpm   = frame.engineRpm?.rpm   ?: 0
val temp  = frame.climateData?.interiorTemp   // může být null
```

Nikdy nepiš `frame.vehicleSpeed!!` — crash je zaručený při studeném startu.

### MainViewModel je sdílený ViewModel

Všechny fragmenty ho sdílejí přes `by activityViewModels()`. Nepoužívej `by viewModels()` pro `MainViewModel`.

```kotlin
// správně
private val mainViewModel: MainViewModel by activityViewModels()

// špatně — dostaneš vlastní instanci bez CAN dat
private val mainViewModel: MainViewModel by viewModels()
```

### StateFlow vs LiveData

Projekt používá výhradně `StateFlow` a `collect` v `lifecycleScope`:

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    viewModel.someFlow.collect { value ->
        // aktualizuj UI
    }
}
```

### Audio backend

`AudioEngineManager` detekuje při startu co je nainstalováno:
- **JDSP** (james.dsp) → `JamesDSPBridge` — broadcast `james.dsp.action.SET_PARAMETER`
- **ViperFX** (pittvandewitt.viperfx) → `V4ABridge` — broadcast `com.pittvandewitt.viperfx.ACTION_COMMAND`
- **Žádné** → nativní `AudioEffect` fallback

`AdaptiveEQEngine` běží každé 2 s, 5-vrstvý algoritmus (basy + hluk + loudness + profil + noc), smoothing `new = old * 0.85 + target * 0.15`, clamp ±12 dB.

### Hlasové ovládání

`VoiceInputManager` → `GeminiCommandProcessor` (gemini-1.5-flash, temp=0.1) → `VoiceCommand` sealed class → `VoiceCommandExecutor`

API klíč se ukládá do `EncryptedSharedPreferences`. Bez klíče hlas nefunguje — UI na to upozorní.

### Reliability vrstva (heartbeat + watchdog)

Každý kritický modul hlásí životnost do `ServiceHealthMonitor` (`core/`):

```kotlin
// V service.onCreate(): registrace restart akce + heartbeat loop (5 s)
healthMonitor.registerRestartAction(HealthModules.CAN) { start(appContext) }
scope.launch { while (true) { healthMonitor.heartbeat(HealthModules.CAN); delay(5_000) } }

// V onDestroy() — DŮLEŽITÉ: odregistrovat, jinak watchdog službu vzkřísí
healthMonitor.unregister(HealthModules.CAN)
```

`WatchdogService` kontroluje každých 10 s stale moduly (>30 s bez heartbeatu) a restartuje je.
`CarOSApplication` po fatálním pádu naplánuje restart přes AlarmManager (ochrana proti crash-loop: max 1 restart za 60 s).
Stav všeho vidíš v UI: **Nastavení → Zdraví systému**.

### Výkonnostní konvence

- **Žádné alokace v `onDraw()`** — Paint/Path předalokuj jako fieldy, používej `path.reset()`
- **CAN → UI přes `sample(200ms)`** — UI se překresluje max 5 Hz (viz MainActivity)
- **Telemetrie zapisuje dávkově** — 10 snímků na jeden `insertAll()` (šetří eMMC)
- **Sériové čtení CAN má vlastní vlákno** — `CANReader` používá dedikovaný single-thread dispatcher, ne `Dispatchers.IO`
- **Custom views mají `LAYER_TYPE_HARDWARE`** a guard proti zbytečnému `invalidate()`

---

## Jak přidat novou funkci

### Nový fragment

1. Vytvoř `fragment_xxx.xml` v `res/layout/`
2. Vytvoř `XxxFragment.kt` s `@AndroidEntryPoint` + ViewBinding
3. Přidej záznam do `res/navigation/nav_graph.xml`
4. Přidej navigaci odkudkoli: `findNavController().navigate(R.id.xxxFragment)`

```kotlin
@AndroidEntryPoint
class XxxFragment : Fragment() {
    private var _binding: FragmentXxxBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentXxxBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
```

### Nový ViewModel s injekcí

```kotlin
@HiltViewModel
class XxxViewModel @Inject constructor(
    private val canWriter: CANWriter,         // z AppModule
    @ApplicationContext private val ctx: Context
) : ViewModel() { ... }
```

### Nový Room DAO

1. Vytvoř `XxxEntity.kt` s `@Entity`
2. Vytvoř `XxxDao.kt` s `@Dao`
3. Přidej entitu do `@Database` anotace v `CarOSDatabase.kt`
4. Zvedni `version`, přidej migraci `MIGRATION_x_y`
5. Přidej `abstract fun xxxDao(): XxxDao` do `CarOSDatabase`
6. Přidej `@Provides` do `DatabaseModule`

---

## CAN bus — specifika

### Fyzické připojení

```
Seat Leon 5F → VW-RZ-08-0041 VAG CAN decoder → /dev/ttyS1 (head unit UART)
```

Dekodér mluví standardním ASCII frame formátem: `ID#DATA\n`

### Mock pro vývoj

```kotlin
// V debug buildu se MockCANSource aktivuje automaticky pokud /dev/ttyS1 neexistuje
// Generuje realistická data pro všechny CANFrame fieldy
```

Nebo ručně v Nastavení → Vývojář → přepni zdroj CAN na Mock.

### Psaní CAN příkazů

```kotlin
// Posílání klimatizačního příkazu zpět do auta
canWriter.sendClimateCommand(ClimateCommand(
    targetTemp = 22.0f,
    fanSpeed = 3,
    acOn = true
))
// generuje frame: 3E0#XX XX XX 00 00 00 přes /dev/ttyS1 + su
```

---

## Nasazení na head unit

### ⚡ One-click USB deploy (doporučeno)

Připoj head unit USB kabelem a spusť:

```
deploy-usb.bat      # Windows — stačí dvojklik
./deploy-usb.sh     # Linux / macOS
```

Skript počká na zařízení, **stáhne nejnovější zelenou APK z GitHub Actions** (vyžaduje přihlášené `gh` CLI), nainstaluje s `-r -d` a spustí CarOS. Pokud vedle skriptu leží `app-debug.apk`, použije se ten (offline režim, `gh` není potřeba).

### Automatický build (GitHub Actions)

Každý push na `main` nebo `claude/magical-lovelace-k3nrY` spustí build (testy + APK + lint).  
Artifact `caros-debug-<sha>.apk` je dostupný na:  
**GitHub → Actions → Build APK → Artifacts**

### Manuální install přes ADB

```bash
# připojení přes USB
adb devices
adb install -r -d app-debug.apk

# nebo přes Wi-Fi (head unit a PC ve stejné síti)
adb connect 192.168.1.XXX:5555
adb install -r -d app-debug.apk
```

### Nastavit jako výchozí launcher

```bash
adb shell cmd package set-home-activity com.caros/.ui.MainActivity
```

### Oprávnění

```bash
# overlay (NotificationOverlay)
adb shell appops set com.caros SYSTEM_ALERT_WINDOW allow

# mikrofon (hlasové ovládání)
adb shell pm grant com.caros android.permission.RECORD_AUDIO
```

### Magisk moduly

```bash
# na PC — zabalit a přenést
cd magisk_modules/caros_audio  && zip -r ../../caros_audio.zip .
cd ../caros_termux             && zip -r ../../caros_termux.zip .

adb push caros_audio.zip  /sdcard/
adb push caros_termux.zip /sdcard/
```

Pak v Magisk Manageru → Moduly → instalovat ze souboru → restart.

`caros_audio` — detekuje JDSP/V4A, nastaví `chmod 666 /dev/snd/*`, `setprop persist.caros.audio.*`  
`caros_termux` — grant `RUN_COMMAND`, spustí Termux boot script

### Termux bridge (volitelné)

```bash
# v Termuxu na head unitu
bash /data/data/com.termux/files/home/scripts/termux_setup.sh
```

Nainstaluje Python, Mosquitto, InfluxDB, SSH, Syncthing. Bridge běží na `localhost:8765`.

---

## Časté chyby

### ViewBinding ID nenalezeno

Pokud build selže na `binding.tvXxx` — zkontroluj že `android:id="@+id/tvXxx"` existuje v layoutu. ID musí být v layoutu pojmenovaném přesně podle třídy (`FragmentXxxBinding` → `fragment_xxx.xml`).

### Hilt: `@AndroidEntryPoint` chybí

```
error: [Hilt] ... is not a Hilt component
```
Přidej `@AndroidEntryPoint` nad třídu fragmentu nebo aktivity.

### Room: zapomenutá migrace

```
IllegalStateException: Room cannot verify the data integrity
```
Zvedni `version` v `@Database` a přidej `MIGRATION_x_y` do `ALL_MIGRATIONS`. Nebo v debug použij `fallbackToDestructiveMigration()`.

### CAN data jsou null

CANFrame fieldy jsou nullable. Vždy `?.` nebo `?: default`. Nikdy `!!`.

### Navigation: destination not found

Zkontroluj že fragment má záznam v `nav_graph.xml` s odpovídajícím `android:id`.

### Zápisy na /sdcard nefungují

Aplikace používá `/sdcard/CarOS/` (crash reporty, CAN logy, GPX, Vosk model). Funguje to díky `android:requestLegacyExternalStorage="true"` v manifestu — **neodstraňuj ho**, dokud `targetSdk` zůstává 29.

### Watchdog mi pořád restartuje službu

Při záměrném zastavení služby zavolej `healthMonitor.unregister(HealthModules.XXX)` v `onDestroy()` — jinak ji watchdog do 30 s vzkřísí.

---

## Stav projektu (12. 6. 2026)

- ✅ **CI zelené** — testy + assembleDebug + lint procházejí na každý push
- ✅ **Funkční audit dokončen** — 4-vrstvý audit (služby / UI / data+DI / enginy), všechny nálezy opraveny v commitu `b8907f3`:
  scoped storage fix, nativní EQ (kumulace zisků), NaN guardy v gauge views, coroutine lifecycle opravy, DB index + migrace 3→4
- ✅ **Reliability vrstva** — heartbeaty, WatchdogService, crash-restart, health dashboard
- ✅ **One-click USB deploy** — `deploy-usb.bat` / `deploy-usb.sh`
- 📄 Kompletní seznam funkcí všech 22+ modulů: viz `FEATURES.md`

## Otevřené úkoly

- [ ] Release build — podpis keystorem, ProGuard pravidla
- [ ] Fyzický CAN test — ověřit dekodér na reálném autě
- [ ] Fyzický test scoped storage na head unitu (zápis do /sdcard/CarOS po instalaci)
- [ ] Detekce natankování v `InstantFuelComputer` (reset trip počítadel po refuelu)
- [ ] Přidat `btnVoice` do levého panelu + long-press → VoiceSetupFragment
- [ ] `AdaptiveEQEngine.isEnabled` — předělat na `StateFlow<Boolean>` pro reaktivní UI
- [ ] Semenovat FM preset UI z `FMController.getPresets()` při startu
- [ ] OTA update mechanismus (stáhnout APK z GitHub Releases, nainstalovat přes PackageInstaller)
- [ ] Rozšířit unit testy — `AdaptiveEQEngine`, `VoiceCommandExecutor` (CANParser už testy má)
- [ ] Wireless ADB přes Wi-Fi hotspot pro snazší iteraci bez kabelu
