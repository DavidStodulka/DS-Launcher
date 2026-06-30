package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  VAGFrameMap.kt — CAN frame ID constants for Seat Leon 5F (PQ35 / PQ46)
//
//  IDs are given as 11-bit standard CAN identifiers (hex).
//  All entries marked // CALIBRATE must be verified against a live
//  can_log.txt capture from the actual vehicle before use in production.
//
//  Capture procedure:
//    1. Connect VW-RZ-08-0041 to OBD-II
//    2. Run:  candump -l /dev/ttyS1 > can_log.txt
//    3. Drive at various conditions (idle, cruise, brake, climate on/off)
//    4. Compare with SavvyCAN / BUSmaster decode to confirm each ID
// ─────────────────────────────────────────────────────────────────────────────

object VAGFrameMap {

    // ── Powertrain ────────────────────────────────────────────────────────────

    /**
     * Engine speed (RPM) and throttle pedal position.
     * Byte 0–1: RPM = (b0 * 256 + b1) / 4
     * Byte 3:   Throttle = b3 * 100 / 255
     * Broadcast period: ~10 ms
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_ENGINE_DATA = 0x280

    /**
     * Engine RPM alternate / secondary ECU broadcast (some Leon 5F variants).
     * Same byte layout as 0x280 on many PQ35 builds.
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_ENGINE_DATA_ALT = 0x285

    /**
     * Boost / intake manifold pressure.
     * Byte 0–1: absolute MAP in hPa, raw = (b0 shl 8) or b1
     *           kPa = raw / 10.0
     * Byte 2:   Requested boost (same scale)
     * Broadcast period: ~10 ms
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_BOOST_PRESSURE = 0x288

    /**
     * Mass Air Flow sensor.
     * Byte 0–1: MAF g/s = ((b0 shl 8) or b1) / 100.0
     * // CALIBRATE: verify ID from live can_log.txt capture — TDI may differ
     */
    const val ID_MAF = 0x289

    /**
     * Fuel trim (short-term / long-term) and lambda sensor data.
     * Byte 0: STFT signed, percent = (b0.toByte() / 128.0f) * 100
     * Byte 1: LTFT signed, same scale
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_FUEL_TRIM = 0x28F

    // ── Vehicle Dynamics / Wheel Speed ────────────────────────────────────────

    /**
     * Primary vehicle speed (instrument cluster).
     * Byte 0–1: speed = ((b0 shl 8) or b1) / 100.0  (km/h)
     * Broadcast period: ~10 ms
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_VEHICLE_SPEED = 0x320

    /**
     * ABS/ESP wheel speed — all four wheels.
     * Byte 0–1: front-left   km/h * 100
     * Byte 2–3: front-right  km/h * 100
     * Byte 4–5: rear-left    km/h * 100
     * Byte 6–7: rear-right   km/h * 100
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_WHEEL_SPEED = 0x350

    /**
     * ESP/ABS longitudinal + lateral acceleration and stability control flags.
     * Byte 0–1: lateral G      signed 16-bit, /1000.0 → g (positive = right)
     * Byte 2–3: longitudinal G signed 16-bit, /1000.0 → g (positive = forward)
     * Byte 4 bit 0: ESP active
     * Byte 4 bit 1: TC active
     * Byte 4 bit 2: ABS active
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_ACCELERATION = 0x368

    /**
     * EPS (Electric Power Steering) steering angle.
     * Byte 0–1: steering angle signed 16-bit, /10.0 → degrees (negative = left)
     * Byte 2–3: angular velocity signed 16-bit, /10.0 → °/s
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_EPS_STEERING = 0x0C6

    /**
     * TPMS tire pressure (optional equipment — not all León 5F have TPMS).
     * Byte 0: front-left  kPa (raw * 1.364 = kPa)
     * Byte 1: front-right kPa
     * Byte 2: rear-left   kPa
     * Byte 3: rear-right  kPa
     * // CALIBRATE: verify ID from live can_log.txt capture; may be absent
     */
    const val ID_TPMS = 0x3E3

    // ── Thermal / Temperatures ────────────────────────────────────────────────

    /**
     * Engine coolant temperature and ambient temperature.
     * Byte 0: coolant °C = b0 - 40
     * Byte 1: ambient °C = b1 - 40
     * Broadcast period: ~1 s (temperature changes slowly)
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_COOLANT_TEMP = 0x470

    /**
     * Oil temperature (engine oil, from the ECU sensor).
     * Byte 0: oil °C = b0 - 40
     * Broadcast period: ~1 s
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_OIL_TEMP = 0x588

    // ── Body / Comfort ────────────────────────────────────────────────────────

    /**
     * Door ajar, seatbelt, light status (BCM / Komfort-Steuergerät).
     * Byte 0 bits:
     *   bit 0 = driver door open
     *   bit 1 = passenger door open
     *   bit 2 = rear-left door open
     *   bit 3 = rear-right door open
     *   bit 4 = trunk open
     * Byte 1 bits:
     *   bit 0 = driver seatbelt
     *   bit 1 = passenger seatbelt
     *   bit 2 = rear-left seatbelt
     *   bit 3 = rear-right seatbelt
     * Byte 2 bits:
     *   bit 0 = low beam
     *   bit 1 = high beam
     *   bit 2 = position lights
     *   bit 3 = front fog
     *   bit 4 = rear fog
     *   bit 5 = reverse
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_DOORS_LIGHTS = 0x60D

    /**
     * Wiper / turn signal stalk state.
     * Byte 0: wiper 0=OFF 1=INTERVAL 2=LOW 3=HIGH
     * Byte 1: turn  0=NONE 1=LEFT 2=RIGHT 3=HAZARD
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_STALK_STATE = 0x5C0

    /**
     * Battery voltage from the BCM.
     * Byte 0–1: millivolts = ((b0 shl 8) or b1)  → volts = raw / 1000.0
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_BATTERY_VOLTAGE = 0x55E

    /**
     * Ignition / ACC key position.
     * Byte 0: 0=off 1=ACC 2=ON 3=START
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_IGNITION = 0x271

    // ── Climate (Climatronic) ─────────────────────────────────────────────────

    /**
     * Climate control setpoint and state (Driver side).
     * Byte 0: set temp in 0.5°C steps, raw = b0 → °C = b0 / 2.0
     * Byte 1: interior temp (same encoding)
     * Byte 2 hi-nibble: fan speed 0–7
     * Byte 2 bit 0: A/C on
     * Byte 2 bit 1: recirc on
     * Byte 3: distribution bitmask (bit 0=vents, bit 1=floor, bit 2=defrost)
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_CLIMATE_STATUS = 0x570

    /**
     * Climate control secondary / passenger zone (if fitted).
     * Same byte layout as 0x570.
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_CLIMATE_STATUS_PASS = 0x575

    // ── DPF / Emissions ───────────────────────────────────────────────────────

    /**
     * Diesel Particulate Filter status (from diesel ECU).
     * Byte 0: soot load % (0–100)
     * Byte 1–2: differential pressure in Pa, raw = (b1 shl 8) or b2
     * Byte 3–6: last regen timestamp (seconds since ECU epoch, big-endian)
     * Broadcast period: ~1 s
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_DPF_STATUS = 0x65D

    /**
     * DPF regen request / active flag (ECU → BCM).
     * Byte 0 bit 0: regen active
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_DPF_REGEN = 0x65E

    // ── DSG / Transmission ────────────────────────────────────────────────────

    /**
     * DSG gear selector / TCU status.
     * Byte 0: selector position 0=P 1=R 2=N 3=D 4–9=Manual 1–6
     * Byte 1–2: clutch temperature in 0.1°C steps, signed
     * Byte 3–4: DSG oil temperature in 0.1°C steps, signed
     * // CALIBRATE: verify ID from live can_log.txt capture (DSG-equipped cars only)
     */
    const val ID_DSG_STATUS = 0x540

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /**
     * OBD-II / KWP2000 response gateway (UDS on CAN, functional addressing).
     * Standard ISO 15765-2 response ID for OBD-II mode 0x07 (pending DTCs).
     * // CALIBRATE: verify — gateway may shift ID by +0x008 for responses
     */
    const val ID_OBD_RESPONSE = 0x7E8

    /**
     * OBD-II request (tester → gateway, functional addressing 0x7DF).
     * Used by CANParser to filter out looped-back tester frames.
     */
    const val ID_OBD_REQUEST = 0x7DF

    // ── GPS Wheel-pulse / Odometer ────────────────────────────────────────────

    /**
     * Odometer pulse and trip distance from the instrument cluster.
     * Byte 0–3: total odometer reading in km (big-endian uint32)
     * // CALIBRATE: verify ID from live can_log.txt capture
     */
    const val ID_ODOMETER = 0x60A

    // ── Helper sets for routing ───────────────────────────────────────────────

    /** All IDs that feed powertrain / engine data. */
    val ENGINE_IDS = setOf(
        ID_ENGINE_DATA, ID_ENGINE_DATA_ALT, ID_BOOST_PRESSURE,
        ID_MAF, ID_FUEL_TRIM, ID_COOLANT_TEMP, ID_OIL_TEMP
    )

    /** All IDs that feed vehicle dynamics data. */
    val DYNAMICS_IDS = setOf(ID_VEHICLE_SPEED, ID_WHEEL_SPEED, ID_ACCELERATION, ID_EPS_STEERING)

    /** All IDs that feed body / comfort data. */
    val BODY_IDS = setOf(
        ID_DOORS_LIGHTS, ID_STALK_STATE, ID_BATTERY_VOLTAGE,
        ID_IGNITION, ID_ODOMETER, ID_TPMS
    )

    /** All IDs that feed the climate subsystem. */
    val CLIMATE_IDS = setOf(ID_CLIMATE_STATUS, ID_CLIMATE_STATUS_PASS)

    /** All IDs that feed DPF / emissions monitoring. */
    val DPF_IDS = setOf(ID_DPF_STATUS, ID_DPF_REGEN)

    /** All IDs that feed the transmission / DSG subsystem. */
    val DSG_IDS = setOf(ID_DSG_STATUS)

    /** Union of all known IDs — frames with IDs outside this set are logged but not decoded. */
    val ALL_KNOWN_IDS: Set<Int> = ENGINE_IDS + DYNAMICS_IDS + BODY_IDS + CLIMATE_IDS + DPF_IDS + DSG_IDS

    // ── DSG gear selector mapping ─────────────────────────────────────────────

    /** Maps raw DSG selector byte to display string. */
    fun dsgGearLabel(raw: Int): String = when (raw) {
        0    -> "P"
        1    -> "R"
        2    -> "N"
        3    -> "D"
        4    -> "1"
        5    -> "2"
        6    -> "3"
        7    -> "4"
        8    -> "5"
        9    -> "6"
        else -> "?"
    }
}
