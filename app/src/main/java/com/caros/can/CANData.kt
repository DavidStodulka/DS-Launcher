package com.caros.can

// ─────────────────────────────────────────────────────────────────────────────
//  CANData.kt — Domain data classes for all CAN signals on Seat Leon 5F
//  VW-RZ-08-0041 CAN box, /dev/ttyS1
// ─────────────────────────────────────────────────────────────────────────────

/** Vehicle speed in km/h as decoded from CAN frame 0x320 / 0x350. */
data class VehicleSpeed(val kmh: Float)

/** Engine RPM as decoded from CAN frame 0x280. */
data class EngineRPM(val rpm: Int)

/** Coolant temperature in °C as decoded from CAN frame 0x470. */
data class CoolantTemp(val celsius: Float)

/** Engine oil temperature in °C. Nullable — not all variants broadcast this signal. */
data class OilTemp(val celsius: Float?)

/** Throttle / accelerator pedal position in percent [0.0 – 100.0]. */
data class ThrottlePosition(val percent: Float)

/**
 * DSG (Direct-Shift Gearbox) data from the TCU.
 * @param gear       Display gear — one of: "P", "R", "N", "D", "1"–"6"
 * @param clutchTemp Clutch pack temperature in °C
 * @param oilTemp    DSG oil temperature in °C
 */
data class DSGData(
    val gear: String,
    val clutchTemp: Float,
    val oilTemp: Float
)

/** Mass Air Flow sensor reading. Nullable — TDI may not expose MAF on all variants. */
data class MAFRate(val gramsPerSecond: Float?)

/** Turbocharger boost pressure in kPa. */
data class BoostPressure(val kPa: Float)

/**
 * Fuel trim correction values.
 * @param shortTerm STFT as percent deviation
 * @param longTerm  LTFT as percent deviation
 */
data class FuelTrim(val shortTerm: Float, val longTerm: Float)

/** 12 V battery / alternator voltage. */
data class BatteryVoltage(val volts: Float)

/** Accessory / ignition state from CAN frame 0x271. */
data class ACCState(val isOn: Boolean)

/** Door ajar sensor state. true = door open / ajar. */
data class DoorState(
    val driver: Boolean,
    val passenger: Boolean,
    val rearLeft: Boolean,
    val rearRight: Boolean,
    val trunk: Boolean
)

/** Seatbelt buckle state. true = belt fastened. */
data class SeatbeltState(
    val driver: Boolean,
    val passenger: Boolean,
    val rearLeft: Boolean,
    val rearRight: Boolean
)

/** Exterior lighting state. true = light is active. */
data class LightState(
    val lowBeam: Boolean,
    val highBeam: Boolean,
    val positionLights: Boolean,
    val fogFront: Boolean,
    val fogRear: Boolean,
    val reverse: Boolean
)

/** Wiper stalk position. */
enum class WiperState { OFF, INTERVAL, LOW, HIGH }

/** Turn signal / indicator stalk position. */
enum class TurnSignalState { NONE, LEFT, RIGHT, HAZARD }

/**
 * Climate control (Climatronic) state.
 * @param setTemp      Target cabin temperature in °C
 * @param interiorTemp Current measured interior temperature in °C
 * @param fanSpeed     Fan speed step [0–7]
 * @param distribution Airflow distribution bitmask (vent=1, floor=2, defrost=4)
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
 * @param loadPercent  Soot load 0–100 %
 * @param diffPressure Differential pressure kPa across filter (null if sensor absent)
 * @param lastRegenTime Unix epoch millis of last successful regen
 */
data class DPFData(
    val loadPercent: Float,
    val diffPressure: Float?,
    val lastRegenTime: Long
)

/**
 * A single Diagnostic Trouble Code.
 * @param status "ACTIVE", "STORED", or "PENDING"
 */
data class DTCCode(
    val code: String,
    val description: String,
    val status: String
)

// ─────────────────────────────────────────────────────────────────────────────
//  New signals — decoded from previously ignored frames + new IDs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ESP/ABS longitudinal + lateral G-force and stability control flags.
 * Decoded from CAN frame 0x368.
 * // CALIBRATE: verify byte layout from live can_log.txt capture
 */
data class ESPAcceleration(
    /** Lateral G-force (positive = right turn). */
    val lateralG: Float,
    /** Longitudinal G-force (positive = acceleration, negative = braking). */
    val longitudinalG: Float,
    val espActive: Boolean,
    val tcActive: Boolean,
    val absActive: Boolean
)

/**
 * All four ABS wheel speeds from CAN frame 0x350.
 * // CALIBRATE: verify byte layout from live can_log.txt capture
 */
data class WheelSpeeds(
    val frontLeft: Float,
    val frontRight: Float,
    val rearLeft: Float,
    val rearRight: Float
) {
    val averageKmh: Float get() = (frontLeft + frontRight + rearLeft + rearRight) / 4f

    val maxSlipKmh: Float get() {
        val front = (frontLeft + frontRight) / 2f
        val rear  = (rearLeft  + rearRight)  / 2f
        return kotlin.math.abs(front - rear)
    }
}

/** DPF active regeneration flag from CAN frame 0x65E. */
data class DPFRegenState(val isRegenActive: Boolean)

/**
 * EPS steering angle from CAN frame 0x0C6.
 * @param degrees          Steering wheel angle. Negative = left, positive = right.
 * @param degreesPerSecond Angular velocity of the steering wheel.
 * // CALIBRATE: verify byte layout from live can_log.txt capture
 */
data class SteeringAngle(
    val degrees: Float,
    val degreesPerSecond: Float
)

/**
 * TPMS tire pressures from CAN frame 0x3E3 (optional equipment).
 * All values in kPa.
 * // CALIBRATE: verify byte layout from live can_log.txt capture; TPMS may be absent
 */
data class TPMSData(
    val frontLeftKPa: Float,
    val frontRightKPa: Float,
    val rearLeftKPa: Float,
    val rearRightKPa: Float
) {
    /** Returns true if any tyre is below 190 kPa (~27.5 psi). */
    val anyLow: Boolean
        get() = frontLeftKPa < 190f || frontRightKPa < 190f ||
                rearLeftKPa  < 190f || rearRightKPa  < 190f
}

/**
 * Fuel tank level from OBD-II Mode 01 PID 0x2F.
 * @param percent         Tank fill level [0–100 %]
 * @param estimatedLitres Calculated remaining litres (55 L tank)
 */
data class FuelLevel(
    val percent: Float,
    val estimatedLitres: Float
)

// ─────────────────────────────────────────────────────────────────────────────
//  CANFrame — composite snapshot of all decoded signals at one point in time
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Aggregated CAN snapshot emitted by [CANParser] on every new frame burst.
 * Fields are nullable when the corresponding frame has not been received yet.
 * [timestamp] is System.currentTimeMillis() at decode time.
 */
data class CANFrame(
    val timestamp: Long = System.currentTimeMillis(),

    // ── Pre-existing signals ──────────────────────────────────────────────────
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
    val activeDtcs: List<DTCCode> = emptyList(),

    // ── New signals ───────────────────────────────────────────────────────────
    val espAcceleration: ESPAcceleration? = null,
    val wheelSpeeds: WheelSpeeds? = null,
    val dpfRegenState: DPFRegenState? = null,
    val steeringAngle: SteeringAngle? = null,
    val tpmsData: TPMSData? = null,
    val fuelLevel: FuelLevel? = null
) {
    val isEngineRunning: Boolean get() = (engineRpm?.rpm ?: 0) > 50
    val anyDoorOpen: Boolean
        get() = doorState?.let {
            it.driver || it.passenger || it.rearLeft || it.rearRight || it.trunk
        } ?: false

    companion object {
        val EMPTY = CANFrame(timestamp = 0L)
    }
}
