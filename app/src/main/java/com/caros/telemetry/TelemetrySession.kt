package com.caros.telemetry

// ─────────────────────────────────────────────────────────────────────────────
//  TelemetrySession.kt — Domain model for a single drive session
//
//  Lightweight counterpart to TelemetrySessionEntity.
//  Created when vehicle starts moving and closed when it stops / ACC turns off.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.TelemetrySessionEntity

data class TelemetrySession(
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val distance: Float = 0f,
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val notes: String? = null
)

/**
 * Maps a [TelemetrySession] to a [TelemetrySessionEntity] for Room persistence.
 */
fun TelemetrySession.toEntity(): TelemetrySessionEntity = TelemetrySessionEntity(
    id          = id,
    startTime   = startTime,
    endTime     = endTime,
    distanceKm  = distance.toDouble(),
    startLat    = startLat,
    startLon    = startLon,
    notes       = notes
)

/**
 * Converts a [TelemetrySessionEntity] back to a [TelemetrySession] domain object.
 */
fun TelemetrySessionEntity.toDomain(): TelemetrySession = TelemetrySession(
    id        = id,
    startTime = startTime,
    endTime   = endTime,
    distance  = distanceKm.toFloat(),
    startLat  = startLat ?: 0.0,
    startLon  = startLon ?: 0.0,
    notes     = notes
)
