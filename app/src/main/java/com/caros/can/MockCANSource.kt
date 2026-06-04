package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  MockCANSource.kt — Simulated CAN data for development without hardware
//
//  Simulates a Seat Leon 5F 1.6 TDI on a typical drive cycle:
//    0–60 s   : acceleration 0 → 130 km/h through gears 1–6
//    60–180 s : highway cruise ~130 km/h
//    180–240 s: deceleration 130 → 0 km/h
//    240+ s   : idle / stop
//  DPF rises from 45 % → 85 % over 10 minutes, then simulated regen resets to 20 %
//  DTCs emitted after 30 s (P0420 + P0300)
//
//  Emits both Flow<CANFrame> and Flow<String> (raw encoded lines in the same
//  "ID:xxx DATA:xx ..." format used by the real adapter, so CANParser works
//  identically for both paths).
// ─────────────────────────────────────────────────────────────────────────────

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ── Leon 5F 1.6 TDI gear ratios × final drive × tyre factor (km/h per 1000 rpm) ──
private val GEAR_SPEED_AT_1000_RPM = floatArrayOf(
    0f,      // neutral / index 0 placeholder
    6.5f,    // 1st gear
    11.8f,   // 2nd gear
    17.9f,   // 3rd gear
    24.3f,   // 4th gear
    31.0f,   // 5th gear
    36.6f    // 6th gear
)
private const val IDLE_RPM          = 780
private const val UPDATE_INTERVAL   = 100L    // ms between emitted frames
private const val DPF_REGEN_TRIGGER = 85.0f
private const val DPF_REGEN_RESET   = 20.0f
private const val DPF_RISE_PER_SEC  = (85.0f - 45.0f) / 600.0f   // %/s over 10 min

@Singleton
class MockCANSource @Inject constructor() {

    // ── Simulation state ──────────────────────────────────────────────────────

    private var elapsedSec       = 0.0
    private var speedKmh         = 0.0f
    private var rpm              = IDLE_RPM.toFloat()
    private var gear             = 0
    private var throttle         = 0.0f
    private var coolantCelsius   = 20.0f
    private var oilCelsius       = 20.0f
    private var dpfLoad          = 45.0f
    private var lastRegenMs      = 0L
    private var dtcsEmitted      = false
    private var activeDtcs       = mutableListOf<DTCCode>()
    private var doorState        = DoorState(false, false, false, false, false)
    private var nextDoorEventSec = 15.0
    private var boostKPa         = 100.0f   // ~atmospheric (gauge 0)
    private var voltageV         = 14.2f
    private var gpsLat           = 41.3870   // Starting point (Barcelona area)
    private var gpsLon           = 2.1700
    private var gpsAlt           = 120.0
    private var gpsHeading       = 0.0       // degrees

    // ── Public API — Flow<CANFrame> ───────────────────────────────────────────

    /**
     * Emits fully decoded [CANFrame] snapshots at [intervalMs] intervals.
     * Use this when you need domain objects directly.
     */
    fun frames(intervalMs: Long = UPDATE_INTERVAL): Flow<CANFrame> = flow {
        resetState()
        Timber.d("MockCANSource: starting simulation")
        while (true) {
            elapsedSec += intervalMs / 1000.0
            val frame = buildFrame()
            emit(frame)
            delay(intervalMs)
        }
    }

    /**
     * Emits raw ASCII lines in "ID:xxx DATA:xx xx ..." format.
     * [CANReader] uses this as its fallback — ensuring [CANParser] processes
     * mock data identically to real hardware data.
     */
    fun rawLines(intervalMs: Long = UPDATE_INTERVAL): Flow<String> = flow {
        resetState()
        Timber.d("MockCANSource: starting raw-line simulation")
        while (true) {
            elapsedSec += intervalMs / 1000.0
            val frame = buildFrame()
            // Encode each subsystem into separate lines, one per frame ID
            encodeToLines(frame).forEach { emit(it) }
            delay(intervalMs)
        }
    }

    // ── Simulation core ───────────────────────────────────────────────────────

    private fun resetState() {
        elapsedSec       = 0.0
        speedKmh         = 0.0f
        rpm              = IDLE_RPM.toFloat()
        gear             = 0
        throttle         = 0.0f
        coolantCelsius   = 20.0f
        oilCelsius       = 20.0f
        dpfLoad          = 45.0f
        lastRegenMs      = 0L
        dtcsEmitted      = false
        activeDtcs       = mutableListOf()
        doorState        = DoorState(false, false, false, false, false)
        nextDoorEventSec = 15.0
        boostKPa         = 100.0f
        voltageV         = 14.2f
    }

    private fun buildFrame(): CANFrame {
        val t = elapsedSec.toFloat()

        // ── Drive cycle ────────────────────────────────────────────────────────
        when {
            t < 5f   -> {                                    // Cold idle
                throttle = 0f
                speedKmh = 0f
                rpm      = IDLE_RPM.toFloat()
                gear     = 0
                voltageV = 14.2f
            }
            t < 60f  -> {                                    // Acceleration 0→130
                val progress = (t - 5f) / 55f               // 0→1
                speedKmh  = (progress * 130f).coerceAtMost(130f)
                throttle  = (60f + progress * 30f).coerceAtMost(90f)
                gear      = selectGear(speedKmh)
                rpm       = calcRpm(speedKmh, gear)
                voltageV  = 14.2f
            }
            t < 180f -> {                                    // Highway cruise
                val drift = sin(t / 30.0).toFloat() * 3f
                speedKmh  = (130f + drift).coerceIn(120f, 135f)
                throttle  = 35f + sin(t / 20.0).toFloat() * 5f
                gear      = 6
                rpm       = calcRpm(speedKmh, gear)
                voltageV  = 14.2f
            }
            t < 240f -> {                                    // Deceleration 130→0
                val progress = (t - 180f) / 60f
                speedKmh  = ((1f - progress) * 130f).coerceAtLeast(0f)
                throttle  = 0f
                gear      = selectGear(speedKmh)
                rpm       = if (speedKmh < 5f) IDLE_RPM.toFloat() else calcRpm(speedKmh, gear)
                voltageV  = if (speedKmh < 2f) 12.1f else 14.2f
            }
            else     -> {                                    // Stop / idle
                speedKmh  = 0f
                throttle  = 0f
                gear      = 0
                rpm       = IDLE_RPM.toFloat()
                voltageV  = 12.1f
            }
        }

        // ── Boost pressure (correlates with throttle and RPM) ──────────────────
        val targetBoostGauge = if (throttle > 20f) {
            (throttle / 100f) * 1.8f * 100f      // up to 180 kPa gauge → 280 kPa absolute
        } else {
            0f
        }
        boostKPa += (targetBoostGauge + 101.3f - boostKPa) * 0.05f  // lag filter

        // ── Thermal simulation ─────────────────────────────────────────────────
        // Coolant: cold start 20°C → 90°C over 120 s
        val coolantTarget = if (t < 120f) 20f + (t / 120f) * 70f else 90f
        coolantCelsius += (coolantTarget - coolantCelsius) * 0.01f

        // Oil: lags coolant by ~30 s, reaches 100°C
        val oilTarget = if (t < 150f) 20f + ((t - 30f).coerceAtLeast(0f) / 150f) * 80f else 100f
        oilCelsius += (oilTarget - oilCelsius) * 0.008f

        // ── DPF simulation ─────────────────────────────────────────────────────
        dpfLoad += DPF_RISE_PER_SEC * (UPDATE_INTERVAL / 1000.0f)
        if (dpfLoad >= DPF_REGEN_TRIGGER) {
            Timber.d("MockCANSource: DPF regen triggered at %.1f %%", dpfLoad)
            dpfLoad     = DPF_REGEN_RESET
            lastRegenMs = System.currentTimeMillis()
        }

        // ── Doors: random open/close every 15 s ───────────────────────────────
        if (t >= nextDoorEventSec) {
            doorState        = randomDoorEvent(doorState)
            nextDoorEventSec = t + 15.0
        }

        // ── DTCs after 30 s ───────────────────────────────────────────────────
        if (t >= 30.0 && !dtcsEmitted) {
            activeDtcs.addAll(
                listOf(
                    DTCCode("P0420", "Catalyst system efficiency below threshold (bank 1)", "STORED"),
                    DTCCode("P0300", "Random / multiple cylinder misfire detected", "STORED")
                )
            )
            dtcsEmitted = true
            Timber.d("MockCANSource: emitting mock DTCs")
        }

        // ── GPS: simulate route with elevation changes ─────────────────────────
        val headingRad = Math.toRadians(gpsHeading)
        val metersPerStep = speedKmh * (UPDATE_INTERVAL / 3_600_000.0)
        gpsLat += cos(headingRad) * metersPerStep / 111_320.0
        gpsLon += sin(headingRad) * metersPerStep / (111_320.0 * cos(Math.toRadians(gpsLat)))
        gpsAlt += sin(t / 40.0) * 0.5   // gentle elevation change
        gpsHeading = (gpsHeading + sin(t / 80.0) * 0.3) % 360.0

        return CANFrame(
            timestamp       = System.currentTimeMillis(),
            vehicleSpeed    = VehicleSpeed(speedKmh),
            engineRpm       = EngineRPM(rpm.roundToInt()),
            coolantTemp     = CoolantTemp(coolantCelsius),
            oilTemp         = OilTemp(oilCelsius),
            throttlePosition = ThrottlePosition(throttle.coerceIn(0f, 100f)),
            dsgData         = DSGData(
                gear       = if (gear == 0) "N" else gear.toString(),
                clutchTemp = (coolantCelsius * 0.9f).coerceIn(20f, 200f),
                oilTemp    = oilCelsius
            ),
            mafRate         = MAFRate(gramsPerSecond = calcMAF(rpm, throttle)),
            boostPressure   = BoostPressure(boostKPa),
            fuelTrim        = FuelTrim(
                shortTerm = (sin(t / 7.0) * 3.0).toFloat(),
                longTerm  = (cos(t / 30.0) * 1.5).toFloat()
            ),
            batteryVoltage  = BatteryVoltage(voltageV + (sin(t / 5.0) * 0.05).toFloat()),
            accState        = ACCState(isOn = true),
            doorState       = doorState,
            seatbeltState   = SeatbeltState(
                driver    = true,
                passenger = t > 5f,
                rearLeft  = false,
                rearRight = false
            ),
            lightState      = LightState(
                lowBeam        = true,
                highBeam       = false,
                positionLights = true,
                fogFront       = false,
                fogRear        = false,
                reverse        = false
            ),
            wiperState      = WiperState.OFF,
            turnSignalState = TurnSignalState.NONE,
            climateData     = ClimateData(
                setTemp      = 22.0f,
                interiorTemp = (coolantCelsius * 0.2f + 18f).coerceIn(15f, 30f),
                fanSpeed     = 3,
                acOn         = coolantCelsius > 60f,
                recircOn     = false,
                distribution = 1
            ),
            dpfData         = DPFData(
                loadPercent  = dpfLoad,
                diffPressure = dpfLoad * 0.05f,
                lastRegenTime = lastRegenMs
            ),
            activeDtcs      = activeDtcs.toList()
        )
    }

    // ── Gear selection helper ─────────────────────────────────────────────────

    private fun selectGear(kmh: Float): Int = when {
        kmh < 1f   -> 0
        kmh < 20f  -> 1
        kmh < 40f  -> 2
        kmh < 65f  -> 3
        kmh < 90f  -> 4
        kmh < 110f -> 5
        else       -> 6
    }

    private fun calcRpm(kmh: Float, gear: Int): Float {
        if (gear < 1 || gear > 6) return IDLE_RPM.toFloat()
        val rpmCalc = kmh / GEAR_SPEED_AT_1000_RPM[gear] * 1000f
        return rpmCalc.coerceIn(IDLE_RPM.toFloat(), 4800f)
    }

    /** Very rough MAF estimate: g/s ≈ throttle% × rpm / 10000 (TDI approximation). */
    private fun calcMAF(rpm: Float, throttle: Float): Float =
        (throttle * rpm / 10_000f).coerceIn(0f, 60f)

    // ── Door event simulator ──────────────────────────────────────────────────

    private fun randomDoorEvent(current: DoorState): DoorState {
        // Toggle a random door with ~30 % probability, otherwise leave unchanged
        return when ((System.currentTimeMillis() % 7).toInt()) {
            0 -> current.copy(driver    = !current.driver)
            1 -> current.copy(passenger = !current.passenger)
            2 -> current.copy(rearLeft  = !current.rearLeft)
            3 -> current.copy(rearRight = !current.rearRight)
            4 -> current.copy(trunk     = !current.trunk)
            else -> current   // no change
        }
    }

    // ── Raw-line encoder (mirrors CANParser decoder byte layout) ──────────────

    /**
     * Encodes a [CANFrame] into a list of raw ASCII lines in VW-RZ-08-0041 format.
     * Byte layouts must match the decoder in [CANParser] exactly.
     */
    private fun encodeToLines(f: CANFrame): List<String> {
        val lines = mutableListOf<String>()

        // 0x280 — Engine data: RPM (bytes 0–1 big-endian × 4), throttle byte 3
        f.engineRpm?.let { rpmData ->
            val raw = (rpmData.rpm * 4).coerceIn(0, 65535)
            val throttleRaw = ((f.throttlePosition?.percent ?: 0f) * 255f / 100f).roundToInt().coerceIn(0, 255)
            lines += formatFrame(VAGFrameMap.ID_ENGINE_DATA,
                (raw shr 8) and 0xFF, raw and 0xFF, 0, throttleRaw, 0, 0, 0, 0)
        }

        // 0x288 — Boost: absolute MAP in hPa (× 10 = raw)
        f.boostPressure?.let {
            val raw = (it.kPa * 10f).roundToInt().coerceIn(0, 65535)
            lines += formatFrame(VAGFrameMap.ID_BOOST_PRESSURE,
                (raw shr 8) and 0xFF, raw and 0xFF, 0, 0, 0, 0, 0, 0)
        }

        // 0x289 — MAF g/s × 100
        f.mafRate?.gramsPerSecond?.let {
            val raw = (it * 100f).roundToInt().coerceIn(0, 65535)
            lines += formatFrame(VAGFrameMap.ID_MAF,
                (raw shr 8) and 0xFF, raw and 0xFF, 0, 0, 0, 0, 0, 0)
        }

        // 0x28F — Fuel trim: signed bytes × 128 / 100
        f.fuelTrim?.let {
            val stftRaw = (it.shortTerm / 100f * 128f).roundToInt().coerceIn(-128, 127)
            val ltftRaw = (it.longTerm  / 100f * 128f).roundToInt().coerceIn(-128, 127)
            lines += formatFrame(VAGFrameMap.ID_FUEL_TRIM,
                stftRaw and 0xFF, ltftRaw and 0xFF, 0, 0, 0, 0, 0, 0)
        }

        // 0x320 — Vehicle speed × 100
        f.vehicleSpeed?.let {
            val raw = (it.kmh * 100f).roundToInt().coerceIn(0, 65535)
            lines += formatFrame(VAGFrameMap.ID_VEHICLE_SPEED,
                (raw shr 8) and 0xFF, raw and 0xFF, 0, 0, 0, 0, 0, 0)
        }

        // 0x470 — Coolant temp: byte 0 = °C + 40
        f.coolantTemp?.let {
            val raw = (it.celsius + 40f).roundToInt().coerceIn(0, 255)
            lines += formatFrame(VAGFrameMap.ID_COOLANT_TEMP,
                raw, 0, 0, 0, 0, 0, 0, 0)
        }

        // 0x588 — Oil temp: byte 0 = °C + 40
        f.oilTemp?.celsius?.let {
            val raw = (it + 40f).roundToInt().coerceIn(0, 255)
            lines += formatFrame(VAGFrameMap.ID_OIL_TEMP,
                raw, 0, 0, 0, 0, 0, 0, 0)
        }

        // 0x55E — Battery voltage: millivolts big-endian
        f.batteryVoltage?.let {
            val mv = (it.volts * 1000f).roundToInt().coerceIn(0, 65535)
            lines += formatFrame(VAGFrameMap.ID_BATTERY_VOLTAGE,
                (mv shr 8) and 0xFF, mv and 0xFF, 0, 0, 0, 0, 0, 0)
        }

        // 0x271 — Ignition: byte 0 = 2 (ON)
        f.accState?.let {
            lines += formatFrame(VAGFrameMap.ID_IGNITION,
                if (it.isOn) 2 else 0, 0, 0, 0, 0, 0, 0, 0)
        }

        // 0x60D — Doors / seatbelts / lights
        f.doorState?.let { doors ->
            var doorByte = 0
            if (doors.driver)    doorByte = doorByte or 0x01
            if (doors.passenger) doorByte = doorByte or 0x02
            if (doors.rearLeft)  doorByte = doorByte or 0x04
            if (doors.rearRight) doorByte = doorByte or 0x08
            if (doors.trunk)     doorByte = doorByte or 0x10

            var beltByte = 0
            f.seatbeltState?.let { b ->
                if (b.driver)    beltByte = beltByte or 0x01
                if (b.passenger) beltByte = beltByte or 0x02
                if (b.rearLeft)  beltByte = beltByte or 0x04
                if (b.rearRight) beltByte = beltByte or 0x08
            }

            var lightByte = 0
            f.lightState?.let { l ->
                if (l.lowBeam)        lightByte = lightByte or 0x01
                if (l.highBeam)       lightByte = lightByte or 0x02
                if (l.positionLights) lightByte = lightByte or 0x04
                if (l.fogFront)       lightByte = lightByte or 0x08
                if (l.fogRear)        lightByte = lightByte or 0x10
                if (l.reverse)        lightByte = lightByte or 0x20
            }
            lines += formatFrame(VAGFrameMap.ID_DOORS_LIGHTS,
                doorByte, beltByte, lightByte, 0, 0, 0, 0, 0)
        }

        // 0x5C0 — Stalk state
        val wiperByte = when (f.wiperState) {
            WiperState.INTERVAL -> 1; WiperState.LOW -> 2; WiperState.HIGH -> 3; else -> 0
        }
        val turnByte = when (f.turnSignalState) {
            TurnSignalState.LEFT -> 1; TurnSignalState.RIGHT -> 2;
            TurnSignalState.HAZARD -> 3; else -> 0
        }
        lines += formatFrame(VAGFrameMap.ID_STALK_STATE,
            wiperByte, turnByte, 0, 0, 0, 0, 0, 0)

        // 0x570 — Climate
        f.climateData?.let { c ->
            val setRaw  = (c.setTemp  * 2f).roundToInt().coerceIn(0, 255)
            val intRaw  = (c.interiorTemp * 2f).roundToInt().coerceIn(0, 255)
            val fanHi   = (c.fanSpeed.coerceIn(0, 7) shl 4) and 0x70
            val acBit   = if (c.acOn)     0x01 else 0
            val recBit  = if (c.recircOn) 0x02 else 0
            val b2      = fanHi or acBit or recBit
            lines += formatFrame(VAGFrameMap.ID_CLIMATE_STATUS,
                setRaw, intRaw, b2, c.distribution and 0xFF, 0, 0, 0, 0)
        }

        // 0x540 — DSG
        f.dsgData?.let { d ->
            val gearByte = when (d.gear) {
                "P" -> 0; "R" -> 1; "N" -> 2; "D" -> 3
                "1" -> 4; "2" -> 5; "3" -> 6; "4" -> 7; "5" -> 8; "6" -> 9
                else -> 2
            }
            val clutch = (d.clutchTemp * 10f).roundToInt().coerceIn(-32768, 32767)
            val oil    = (d.oilTemp    * 10f).roundToInt().coerceIn(-32768, 32767)
            lines += formatFrame(VAGFrameMap.ID_DSG_STATUS,
                gearByte,
                (clutch shr 8) and 0xFF, clutch and 0xFF,
                (oil shr 8) and 0xFF,    oil and 0xFF,
                0, 0, 0)
        }

        // 0x65D — DPF
        f.dpfData?.let { d ->
            val load   = d.loadPercent.roundToInt().coerceIn(0, 100)
            val diffPa = ((d.diffPressure ?: 0f) * 1000f).roundToInt().coerceIn(0, 65535)
            val regenSec = (d.lastRegenTime / 1000L).coerceIn(0L, 0xFFFFFFFFL).toInt()
            lines += formatFrame(VAGFrameMap.ID_DPF_STATUS,
                load,
                (diffPa shr 8) and 0xFF, diffPa and 0xFF,
                (regenSec shr 24) and 0xFF, (regenSec shr 16) and 0xFF,
                (regenSec shr 8) and 0xFF,   regenSec and 0xFF,
                0)
        }

        return lines
    }

    private fun formatFrame(id: Int, vararg bytes: Int): String {
        val hex = bytes.take(8).joinToString(" ") { "%02X".format(it and 0xFF) }
        return "ID:%03X DATA:$hex".format(id)
    }
}
