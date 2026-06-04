package com.caros.diagnostics

import com.caros.db.CarOSDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

data class TripStats(
    val avgSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val distanceKm: Float = 0f,
    val durationMs: Long = 0L,
    val fuelUsedL: Float? = null,
    val avgThrottlePct: Float = 0f,
    val maxRpm: Int = 0,
    val startTime: Long = 0L,
    val endTime: Long = 0L
)

@Singleton
class TripComputer @Inject constructor(private val db: CarOSDatabase) {

    fun getLastTripStats(): Flow<TripStats> = flow {
        val sessions = db.telemetrySessionDao().getAllSessions()
        val lastSession = sessions.let {
            // Get latest session using a one-shot query
            db.telemetrySessionDao().getLatestSession()
        } ?: run { emit(TripStats()); return@flow }

        val frames = db.telemetryFrameDao().getFramesForSessionOnce(lastSession.id)
        if (frames.isEmpty()) { emit(TripStats()); return@flow }

        val avgSpeed = frames.mapNotNull { it.speedKmh }.average().toFloat()
        val maxSpeed = frames.mapNotNull { it.speedKmh }.maxOrNull() ?: 0f
        val maxRpm = frames.mapNotNull { it.rpm }.maxOrNull() ?: 0
        val avgThrottle = frames.mapNotNull { it.throttlePct }.average().toFloat()
        val duration = (lastSession.endTime ?: frames.last().timestamp) - lastSession.startTime

        emit(TripStats(
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            distanceKm = lastSession.distanceKm.toFloat(),
            durationMs = duration,
            avgThrottlePct = avgThrottle,
            maxRpm = maxRpm,
            startTime = lastSession.startTime,
            endTime = lastSession.endTime ?: frames.last().timestamp
        ))
    }.flowOn(Dispatchers.IO)

    fun formatDuration(ms: Long): String {
        val secs = ms / 1000
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}
