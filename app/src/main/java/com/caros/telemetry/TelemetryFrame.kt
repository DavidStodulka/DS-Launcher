package com.caros.telemetry

// ─────────────────────────────────────────────────────────────────────────────
//  TelemetryFrame.kt — Domain model for a single telemetry snapshot
//
//  Combines CAN bus data, GPS data, and calculated g-forces.
//  Mapped to/from TelemetryFrameEntity for Room persistence.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.TelemetryFrameEntity

data class TelemetryFrame(
    val timestamp: Long = System.currentTimeMillis(),
    val speed: Float = 0f,
    val rpm: Int = 0,
    val throttlePct: Float = 0f,
    val coolantTemp: Float = 0f,
    val oilTemp: Float? = null,
    val boostKpa: Float = 0f,
    val mafGs: Float? = null,
    val gear: String = "N",
    val fuelTrimShort: Float = 0f,
    val fuelTrimLong: Float = 0f,
    val dpfLoadPct: Float = 0f,
    val voltage: Float = 12f,
    val gpsLat: Double = 0.0,
    val gpsLon: Double = 0.0,
    val gpsAlt: Float = 0f,
    val gpsSpeed: Float = 0f,
    val lateralG: Float = 0f,
    val longitudinalG: Float = 0f,
    val sessionId: Long = 0L
)

/**
 * Maps a [TelemetryFrame] domain object to a [TelemetryFrameEntity] for Room storage.
 * The entity's [id] is left as 0 so Room auto-generates it on insert.
 */
fun TelemetryFrame.toEntity(): TelemetryFrameEntity = TelemetryFrameEntity(
    id             = 0L,
    timestamp      = timestamp,
    speedKmh       = speed,
    rpm            = rpm,
    throttlePct    = throttlePct,
    coolantTemp    = coolantTemp,
    oilTemp        = oilTemp,
    boostKpa       = boostKpa,
    mafGs          = mafGs,
    gear           = gear,
    fuelTrimShort  = fuelTrimShort,
    fuelTrimLong   = fuelTrimLong,
    dpfLoadPct     = dpfLoadPct,
    voltage        = voltage,
    gpsLat         = gpsLat,
    gpsLon         = gpsLon,
    gpsAlt         = gpsAlt.toDouble(),
    gpsSpeed       = gpsSpeed,
    lateralG       = lateralG,
    longitudinalG  = longitudinalG,
    sessionId      = sessionId
)

/**
 * Converts a [TelemetryFrameEntity] back to a [TelemetryFrame] domain object.
 */
fun TelemetryFrameEntity.toDomain(): TelemetryFrame = TelemetryFrame(
    timestamp      = timestamp,
    speed          = speedKmh ?: 0f,
    rpm            = rpm ?: 0,
    throttlePct    = throttlePct ?: 0f,
    coolantTemp    = coolantTemp ?: 0f,
    oilTemp        = oilTemp,
    boostKpa       = boostKpa ?: 0f,
    mafGs          = mafGs,
    gear           = gear ?: "N",
    fuelTrimShort  = fuelTrimShort ?: 0f,
    fuelTrimLong   = fuelTrimLong ?: 0f,
    dpfLoadPct     = dpfLoadPct ?: 0f,
    voltage        = voltage ?: 12f,
    gpsLat         = gpsLat ?: 0.0,
    gpsLon         = gpsLon ?: 0.0,
    gpsAlt         = (gpsAlt ?: 0.0).toFloat(),
    gpsSpeed       = gpsSpeed ?: 0f,
    lateralG       = lateralG ?: 0f,
    longitudinalG  = longitudinalG ?: 0f,
    sessionId      = sessionId
)
