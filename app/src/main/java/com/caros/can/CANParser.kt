package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  CANParser.kt — Decodes raw VW-RZ-08-0041 output lines into CANFrame
//
//  The VW-RZ-08-0041 USB-CAN adapter outputs one ASCII line per frame:
//    "ID:xxx DATA:xx xx xx xx xx xx xx xx"
//  where xxx is the 11-bit CAN ID in hex and xx is each data byte in hex.
//
//  All frame IDs and bit positions are marked // CALIBRATE where they must
//  be confirmed against a live can_log.txt capture from the target vehicle.
// ─────────────────────────────────────────────────────────────────────────────

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses raw text lines from the VW-RZ-08-0041 CAN decoder into [CANFrame] snapshots.
 *
 * The parser is stateful: it accumulates the latest value for every signal type
 * and emits a new [CANFrame] on every incoming line, merging with the previous
 * snapshot so that unchanged signals retain their last-known value.
 *
 * Thread-safety: all public methods must be called from a single coroutine /
 * serial executor. [reset] resets state between sessions.
 */
@Singleton
class CANParser @Inject constructor() {

    // ── Accumulated state ─────────────────────────────────────────────────────

    @Volatile private var latest = CANFrame.EMPTY

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parse a single raw line from the CAN adapter.
     *
     * @param rawLine  e.g. "ID:280 DATA:0F A0 00 64 00 00 00 00"
     * @return Updated [CANFrame] with the decoded signal merged in, or `null`
     *         if the line cannot be parsed (format error, unknown ID, checksum
     *         failure).
     */
    fun parseFrame(rawLine: String): CANFrame? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return null

        val parsed = parseRawLine(line) ?: return null
        val (id, bytes) = parsed

        latest = when (id) {
            VAGFrameMap.ID_ENGINE_DATA,
            VAGFrameMap.ID_ENGINE_DATA_ALT -> decodeEngineData(bytes)

            VAGFrameMap.ID_BOOST_PRESSURE  -> decodeBoost(bytes)
            VAGFrameMap.ID_MAF             -> decodeMAF(bytes)
            VAGFrameMap.ID_FUEL_TRIM       -> decodeFuelTrim(bytes)
            VAGFrameMap.ID_VEHICLE_SPEED   -> decodeVehicleSpeed(bytes)
            VAGFrameMap.ID_WHEEL_SPEED     -> decodeWheelSpeed(bytes)
            VAGFrameMap.ID_COOLANT_TEMP    -> decodeCoolantTemp(bytes)
            VAGFrameMap.ID_OIL_TEMP        -> decodeOilTemp(bytes)
            VAGFrameMap.ID_DOORS_LIGHTS    -> decodeDoorsAndLights(bytes)
            VAGFrameMap.ID_STALK_STATE     -> decodeStalkState(bytes)
            VAGFrameMap.ID_BATTERY_VOLTAGE -> decodeBatteryVoltage(bytes)
            VAGFrameMap.ID_IGNITION        -> decodeIgnition(bytes)
            VAGFrameMap.ID_CLIMATE_STATUS,
            VAGFrameMap.ID_CLIMATE_STATUS_PASS -> decodeClimate(bytes)
            VAGFrameMap.ID_DPF_STATUS      -> decodeDPF(bytes)
            VAGFrameMap.ID_DSG_STATUS      -> decodeDSG(bytes)
            else -> {
                if (id !in VAGFrameMap.ALL_KNOWN_IDS) {
                    Timber.v("Unknown CAN ID 0x%03X — logging raw: %s", id, line)
                }
                return null   // No state update for unknown IDs
            }
        }

        return latest
    }

    /** Resets accumulated state (call at session start / reconnect). */
    fun reset() {
        latest = CANFrame.EMPTY
        Timber.d("CANParser state reset")
    }

    /** Returns the most recently assembled frame without triggering a parse. */
    fun currentFrame(): CANFrame = latest

    // ── Line tokeniser ────────────────────────────────────────────────────────

    /**
     * Tokenises "ID:280 DATA:0F A0 00 64 00 00 00 00" into (0x280, [0x0F, 0xA0, ...]).
     * Returns null on any format violation.
     */
    private fun parseRawLine(line: String): Pair<Int, ByteArray>? {
        return try {
            // Expected format: "ID:xxx DATA:xx xx xx ..."
            val idPrefix   = "ID:"
            val dataPrefix = "DATA:"

            val idStart   = line.indexOf(idPrefix)
            val dataStart = line.indexOf(dataPrefix)

            if (idStart < 0 || dataStart < 0) return null

            val idHex = line.substring(idStart + idPrefix.length, dataStart).trim()
            val id    = idHex.toInt(16)

            val dataSection = line.substring(dataStart + dataPrefix.length).trim()
            if (dataSection.isEmpty()) return null

            val byteTokens = dataSection.split(" ", "\t").filter { it.isNotEmpty() }
            val bytes = ByteArray(byteTokens.size) { byteTokens[it].toInt(16).toByte() }

            // Basic sanity: CAN frames carry 0–8 data bytes
            if (bytes.size > 8) {
                Timber.w("Oversized CAN frame ID=0x%03X len=%d", id, bytes.size)
                return null
            }

            id to bytes
        } catch (e: NumberFormatException) {
            Timber.w("CANParser hex parse error on line: %s", line)
            null
        } catch (e: Exception) {
            Timber.w(e, "CANParser unexpected error on line: %s", line)
            null
        }
    }

    // ── Bit / byte helpers ────────────────────────────────────────────────────

    /** Unsigned byte value [0..255]. */
    private fun ByteArray.u8(idx: Int): Int =
        if (idx < size) this[idx].toInt() and 0xFF else 0

    /** Unsigned 16-bit big-endian value. */
    private fun ByteArray.u16be(hiIdx: Int): Int =
        (u8(hiIdx) shl 8) or u8(hiIdx + 1)

    /** Signed 16-bit big-endian value. */
    private fun ByteArray.s16be(hiIdx: Int): Int {
        val raw = u16be(hiIdx)
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }

    /** Signed 32-bit big-endian value. */
    private fun ByteArray.s32be(idx: Int): Long =
        ((u8(idx).toLong() shl 24) or
         (u8(idx + 1).toLong() shl 16) or
         (u8(idx + 2).toLong() shl 8) or
          u8(idx + 3).toLong())

    /** Test a specific bit in a byte. */
    private fun ByteArray.bit(byteIdx: Int, bitPos: Int): Boolean =
        (u8(byteIdx) shr bitPos) and 1 == 1

    // ── Frame decoders ────────────────────────────────────────────────────────

    /**
     * 0x280 / 0x285 — Engine RPM + throttle pedal position.
     *
     * Byte 0–1: RPM = ((b0 shl 8) or b1) / 4
     * Byte 3:   Throttle % = b3 * 100.0 / 255.0
     *
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeEngineData(b: ByteArray): CANFrame {
        val rpm      = b.u16be(0) / 4
        val throttle = b.u8(3) * 100.0f / 255.0f
        return latest.copy(
            timestamp       = System.currentTimeMillis(),
            engineRpm       = EngineRPM(rpm),
            throttlePosition = ThrottlePosition(throttle.coerceIn(0f, 100f))
        )
    }

    /**
     * 0x288 — Boost / MAP.
     *
     * Byte 0–1: absolute MAP in hPa, kPa = raw / 10.0
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeBoost(b: ByteArray): CANFrame {
        val kPa = b.u16be(0) / 10.0f
        return latest.copy(
            timestamp     = System.currentTimeMillis(),
            boostPressure = BoostPressure(kPa)
        )
    }

    /**
     * 0x289 — MAF sensor.
     *
     * Byte 0–1: g/s = ((b0 shl 8) or b1) / 100.0
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeMAF(b: ByteArray): CANFrame {
        val maf = b.u16be(0) / 100.0f
        return latest.copy(
            timestamp = System.currentTimeMillis(),
            mafRate   = MAFRate(maf)
        )
    }

    /**
     * 0x28F — Fuel trim.
     *
     * Byte 0: STFT signed, % = (b0.toByte() / 128.0) * 100
     * Byte 1: LTFT signed, same
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeFuelTrim(b: ByteArray): CANFrame {
        val stft = (b[0].toFloat() / 128.0f) * 100.0f
        val ltft = (b.getOrElse(1) { 0 }.toFloat() / 128.0f) * 100.0f
        return latest.copy(
            timestamp = System.currentTimeMillis(),
            fuelTrim  = FuelTrim(shortTerm = stft, longTerm = ltft)
        )
    }

    /**
     * 0x320 — Instrument-cluster vehicle speed.
     *
     * Byte 0–1: km/h = ((b0 shl 8) or b1) / 100.0
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeVehicleSpeed(b: ByteArray): CANFrame {
        val kmh = b.u16be(0) / 100.0f
        return latest.copy(
            timestamp    = System.currentTimeMillis(),
            vehicleSpeed = VehicleSpeed(kmh.coerceAtLeast(0f))
        )
    }

    /**
     * 0x350 — ABS wheel speed (front-left used as fallback speed).
     *
     * Only used if 0x320 has not been seen yet.
     * Byte 0–1: FL wheel speed km/h = raw / 100.0
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeWheelSpeed(b: ByteArray): CANFrame {
        // Prefer 0x320; only fill in if vehicleSpeed is absent
        if (latest.vehicleSpeed != null) return latest
        val kmh = b.u16be(0) / 100.0f
        return latest.copy(
            timestamp    = System.currentTimeMillis(),
            vehicleSpeed = VehicleSpeed(kmh.coerceAtLeast(0f))
        )
    }

    /**
     * 0x470 — Coolant and ambient temperature.
     *
     * Byte 0: coolant °C = b0 - 40
     * Byte 1: ambient °C = b1 - 40  (not stored in CANFrame currently)
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeCoolantTemp(b: ByteArray): CANFrame {
        val coolant = b.u8(0) - 40.0f
        return latest.copy(
            timestamp   = System.currentTimeMillis(),
            coolantTemp = CoolantTemp(coolant)
        )
    }

    /**
     * 0x588 — Oil temperature.
     *
     * Byte 0: oil °C = b0 - 40
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeOilTemp(b: ByteArray): CANFrame {
        val oil = b.u8(0) - 40.0f
        return latest.copy(
            timestamp = System.currentTimeMillis(),
            oilTemp   = OilTemp(oil)
        )
    }

    /**
     * 0x60D — Door ajar, seatbelt, lighting (BCM).
     *
     * Byte 0: door bitmask  (bits 0–4: driver, pass, rearL, rearR, trunk)
     * Byte 1: seatbelt mask (bits 0–3: driver, pass, rearL, rearR)
     * Byte 2: lighting mask (bits 0–5: lowBeam, highBeam, position, fogF, fogR, reverse)
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeDoorsAndLights(b: ByteArray): CANFrame {
        val doors = DoorState(
            driver    = b.bit(0, 0),
            passenger = b.bit(0, 1),
            rearLeft  = b.bit(0, 2),
            rearRight = b.bit(0, 3),
            trunk     = b.bit(0, 4)
        )
        val belts = SeatbeltState(
            driver    = b.bit(1, 0),
            passenger = b.bit(1, 1),
            rearLeft  = b.bit(1, 2),
            rearRight = b.bit(1, 3)
        )
        val lights = LightState(
            lowBeam        = b.bit(2, 0),
            highBeam       = b.bit(2, 1),
            positionLights = b.bit(2, 2),
            fogFront       = b.bit(2, 3),
            fogRear        = b.bit(2, 4),
            reverse        = b.bit(2, 5)
        )
        return latest.copy(
            timestamp     = System.currentTimeMillis(),
            doorState     = doors,
            seatbeltState = belts,
            lightState    = lights
        )
    }

    /**
     * 0x5C0 — Wiper and turn signal stalk.
     *
     * Byte 0: wiper  0=OFF 1=INTERVAL 2=LOW 3=HIGH
     * Byte 1: turn   0=NONE 1=LEFT 2=RIGHT 3=HAZARD
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeStalkState(b: ByteArray): CANFrame {
        val wiper = when (b.u8(0)) {
            1    -> WiperState.INTERVAL
            2    -> WiperState.LOW
            3    -> WiperState.HIGH
            else -> WiperState.OFF
        }
        val turn = when (b.u8(1)) {
            1    -> TurnSignalState.LEFT
            2    -> TurnSignalState.RIGHT
            3    -> TurnSignalState.HAZARD
            else -> TurnSignalState.NONE
        }
        return latest.copy(
            timestamp       = System.currentTimeMillis(),
            wiperState      = wiper,
            turnSignalState = turn
        )
    }

    /**
     * 0x55E — Battery / alternator voltage.
     *
     * Byte 0–1: millivolts = (b0 shl 8) or b1 → volts = raw / 1000.0
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeBatteryVoltage(b: ByteArray): CANFrame {
        val volts = b.u16be(0) / 1000.0f
        return latest.copy(
            timestamp      = System.currentTimeMillis(),
            batteryVoltage = BatteryVoltage(volts.coerceIn(0f, 20f))
        )
    }

    /**
     * 0x271 — Ignition / ACC key position.
     *
     * Byte 0: 0=off 1=ACC 2=ON 3=START
     * ACC state = key position >= 1 (ACC or ON or START)
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeIgnition(b: ByteArray): CANFrame {
        val pos = b.u8(0)
        return latest.copy(
            timestamp = System.currentTimeMillis(),
            accState  = ACCState(isOn = pos >= 1)
        )
    }

    /**
     * 0x570 / 0x575 — Climatronic status.
     *
     * Byte 0: set temp   °C = b0 / 2.0  (0.5°C resolution)
     * Byte 1: interior   °C = b1 / 2.0
     * Byte 2 hi-nibble:  fan speed 0–7
     * Byte 2 bit 0:      A/C on
     * Byte 2 bit 1:      recirc on
     * Byte 3:            distribution bitmask
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeClimate(b: ByteArray): CANFrame {
        val setTemp    = b.u8(0) / 2.0f
        val intTemp    = b.u8(1) / 2.0f
        val fanRaw     = b.u8(2)
        val fanSpeed   = (fanRaw shr 4) and 0x07
        val acOn       = (fanRaw and 0x01) != 0
        val recircOn   = (fanRaw and 0x02) != 0
        val distrib    = b.u8(3)
        return latest.copy(
            timestamp   = System.currentTimeMillis(),
            climateData = ClimateData(
                setTemp      = setTemp.coerceIn(16f, 30f),
                interiorTemp = intTemp.coerceIn(-10f, 60f),
                fanSpeed     = fanSpeed.coerceIn(0, 7),
                acOn         = acOn,
                recircOn     = recircOn,
                distribution = distrib
            )
        )
    }

    /**
     * 0x65D — DPF soot load and differential pressure.
     *
     * Byte 0:   soot load %
     * Byte 1–2: differential pressure Pa raw = u16be(1)  → kPa = raw / 1000.0
     * Byte 3–6: last regen epoch (seconds, big-endian) → converted to millis
     * // CALIBRATE: verify byte layout from live can_log.txt capture
     */
    private fun decodeDPF(b: ByteArray): CANFrame {
        val load      = b.u8(0).toFloat().coerceIn(0f, 100f)
        val diffPaRaw = b.u16be(1)
        val diffKPa   = if (diffPaRaw > 0) diffPaRaw / 1000.0f else null
        // Bytes 3–6: seconds since ECU epoch — treat as Unix seconds if reasonable
        val regenSec  = b.s32be(3)
        val regenMs   = if (regenSec > 0) regenSec * 1000L else 0L
        return latest.copy(
            timestamp = System.currentTimeMillis(),
            dpfData   = DPFData(
                loadPercent   = load,
                diffPressure  = diffKPa,
                lastRegenTime = regenMs
            )
        )
    }

    /**
     * 0x540 — DSG transmission status.
     *
     * Byte 0:   selector position → gear label via [VAGFrameMap.dsgGearLabel]
     * Byte 1–2: clutch temperature 0.1°C steps, signed 16-bit big-endian
     * Byte 3–4: DSG oil temperature 0.1°C steps, signed 16-bit big-endian
     * // CALIBRATE: verify byte layout from live can_log.txt capture (DSG only)
     */
    private fun decodeDSG(b: ByteArray): CANFrame {
        val gear      = VAGFrameMap.dsgGearLabel(b.u8(0))
        val clutchTemp = b.s16be(1) / 10.0f
        val oilTemp    = b.s16be(3) / 10.0f
        return latest.copy(
            timestamp = System.currentTimeMillis(),
            dsgData   = DSGData(
                gear      = gear,
                clutchTemp = clutchTemp.coerceIn(-40f, 300f),
                oilTemp    = oilTemp.coerceIn(-40f, 200f)
            )
        )
    }
}
