package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  CANData.kt — Domain data classes for all CAN signals on Seat Leon 5F
//  VW-RZ-08-0041 CAN box, /dev/ttyS1
// ─────────────────────────────────────────────────────────────────────────────

/** Vehicle speed in km/h as decoded from CAN frame 0x320 / 0x350. */
data class VehicleSpeed(
    val kmh: Float
)

/** Engine RPM as decoded from CAN frame 0x280. */
data class EngineRPM(
    val rpm: Int
)

/** Coolant temperature in °C as decoded from CAN frame 0x470. */
data class CoolantTemp(
    val celsius: Float
)

/** Engine oil temperature in °C. Nullable — not all variants broadcast this signal. */
data class OilTemp(
    val celsius: Float?
)

/** Throttle / accelerator pedal position in percent [0.0 – 100.0]. */
data class ThrottlePosition(
    val percent: Float
)

/**
 * DSG (Direct-Shift Gearbox) data from the TCU.
 *
 * @param gear      Display gear — one of: "P", "R", "N", "D", "1"–"6"
 * @param clutchTemp Clutch pack temperature in °C (K1/K2 max)
 * @param oilTemp   DSG oil temperature in °C
 */
data class DSGData(
    val gear: String,
    val clutchTemp: Float,
    val oilTemp: Float
)

/**
 * Mass Air Flow sensor reading.
 * Nullable — diesel TDI may not expose MAF over CAN on all variants.
 */
data class MAFRate(
    val gramsPerSecond: Float?
)

/** Turbocharger boost pressure in kPa (absolute or gauge, see VAGFrameMap). */
data class BoostPressure(
    val kPa: Float
)

/**
 * Fuel trim correction values.
 * @param shortTerm Short-term fuel trim (STFT) as percent deviation
 * @param longTerm  Long-term fuel trim (LTFT) as percent deviation
 */
data class FuelTrim(
    val shortTerm: Float,
    val longTerm: Float
)

/** 12 V battery / alternator voltage. */
data class BatteryVoltage(
    val volts: Float
)

/** Accessory / ignition state derived from CAN (ACC position on ignition key). */
data class ACCState(
    val isOn: Boolean
)

/**
 * Door ajar sensor state.
 * true = door open / ajar.
 */
data class DoorState(
    val driver: Boolean,
    val passenger: Boolean,
    val rearLeft: Boolean,
    val rearRight: Boolean,
    val trunk: Boolean
)

/**
 * Seatbelt buckle state.
 * true = belt fastened.
 */
data class SeatbeltState(
    val driver: Boolean,
    val passenger: Boolean,
    val rearLeft: Boolean,
    val rearRight: Boolean
)

/**
 * Exterior lighting state.
 * true = light is active.
 */
data class LightState(
    val lowBeam: Boolean,
    val highBeam: Boolean,
    val positionLights: Boolean,
    val fogFront: Boolean,
    val fogRear: Boolean,
    val reverse: Boolean
)

/** Wiper stalk position. */
enum class WiperState {
    OFF,
    INTERVAL,
    LOW,
    HIGH
}

/** Turn signal / indicator stalk position. */
enum class TurnSignalState {
    NONE,
    LEFT,
    RIGHT,
    HAZARD
}

/**
 * Climate control (Climatronic) state.
 *
 * @param setTemp       Target cabin temperature in °C
 * @param interiorTemp  Current measured interior temperature in °C
 * @param fanSpeed      Fan speed step [0–7]
 * @param acOn          Compressor engaged
 * @param recircOn      Recirculation active
 * @param distribution  Airflow distribution bitmask (vent=1, floor=2, defrost=4)
 */
data class ClimateData(
    val setTemp: Float,
    val interiorTemp: Float,
    val fanSpeed: Int,
    val acOn: Boolean,
    val recircOn: Boolean,
    val distribution: Int
)

/**
 * Diesel Particulate Filter telemetry.
 *
 * @param loadPercent   Soot load 0–100 %
 * @param diffPressure  Differential pressure in kPa across the filter (nullable if sensor absent)
 * @param lastRegenTime Unix epoch millis of last successful regeneration cycle
 */
data class DPFData(
    val loadPercent: Float,
    val diffPressure: Float?,
    val lastRegenTime: Long
)

/**
 * A single Diagnostic Trouble Code read from the instrument cluster gateway.
 *
 * @param code        SAE / VAG code string, e.g. "P0420" or "01314"
 * @param description Human-readable description
 * @param status      "ACTIVE", "STORED", "PENDING"
 */
data class DTCCode(
    val code: String,
    val description: String,
    val status: String
)

// ─────────────────────────────────────────────────────────────────────────────
//  CANFrame — composite snapshot of all decoded signals at one point in time
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Aggregated CAN snapshot emitted by [CANParser] on every new frame burst.
 *
 * Fields are nullable when the corresponding CAN frame has not been received
 * yet during this session or is not available on a particular vehicle variant.
 *
 * [timestamp] is System.currentTimeMillis() at the time the frame was decoded.
 */
data class CANFrame(
    val timestamp: Long = System.currentTimeMillis(),

    val vehicleSpeed: VehicleSpeed? = null,
    val engineRpm: EngineRPM? = null,
    val coolantTemp: CoolantTemp? = null,
    val oilTemp: OilTemp? = null,
    val throttlePosition: ThrottlePosition? = null,
    val dsgData: DSGData? = null,
    val mafRate: MAFRate? = null,
    val boostPressure: BoostPressure? = null,
    val fuelTrim: FuelTrim? = null,
    val batteryVoltage: BatteryVoltage? = null,
    val accState: ACCState? = null,
    val doorState: DoorState? = null,
    val seatbeltState: SeatbeltState? = null,
    val lightState: LightState? = null,
    val wiperState: WiperState? = null,
    val turnSignalState: TurnSignalState? = null,
    val climateData: ClimateData? = null,
    val dpfData: DPFData? = null,
    val activeDtcs: List<DTCCode> = emptyList()
) {
    /** Convenience: is the engine currently running? */
    val isEngineRunning: Boolean
        get() = (engineRpm?.rpm ?: 0) > 50

    /** Convenience: is a door or the trunk open? */
    val anyDoorOpen: Boolean
        get() = doorState?.let {
            it.driver || it.passenger || it.rearLeft || it.rearRight || it.trunk
        } ?: false

    companion object {
        /** An empty frame used as the initial StateFlow value before any real data arrives. */
        val EMPTY = CANFrame(timestamp = 0L)
    }
}
