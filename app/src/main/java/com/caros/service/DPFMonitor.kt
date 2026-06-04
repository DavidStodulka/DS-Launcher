package com.caros.service

// ─────────────────────────────────────────────────────────────────────────────
//  DPFMonitor.kt — Diesel Particulate Filter health monitor for CLHA 1.6 TDI
//
//  Analyses the last 100 telemetry frames to determine:
//    • Current soot load percentage
//    • Load trend in % per hour
//    • Whether a regeneration cycle recently completed (load drop > 15 % in 10 frames)
//    • Actionable recommendation based on load thresholds
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.CarOSDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Actionable recommendation for DPF maintenance. */
enum class DPFRecommendation {
    /** Load is within normal operating range. */
    OK,
    /** A sustained motorway drive at > 2000 RPM will trigger passive regen. */
    HIGHWAY_DRIVE_RECOMMENDED,
    /** Load is high — engine will initiate active regen; avoid short trips until complete. */
    URGENT_REGEN,
    /** DPF may be blocked beyond self-regeneration — workshop service required. */
    SERVICE_REQUIRED
}

/**
 * Snapshot of the DPF state at query time.
 *
 * @param currentLoad    Current soot load in percent [0, 100].
 * @param trend          Rate of load change in % per hour (positive = filling, negative = regen).
 * @param lastRegenTime  Unix epoch millis of the last detected regen cycle, or null if none found.
 * @param recommendation Recommended driver action.
 */
data class DPFStatus(
    val currentLoad: Float,
    val trend: Float,
    val lastRegenTime: Long?,
    val recommendation: DPFRecommendation
)

@Singleton
class DPFMonitor @Inject constructor(
    private val db: CarOSDatabase
) {

    companion object {
        private const val TAG = "DPFMonitor"

        /** Number of recent frames to analyse. */
        private const val SAMPLE_FRAMES = 100

        /** Minimum number of frames required for a trend calculation. */
        private const val MIN_FRAMES_FOR_TREND = 2

        /** Window size (frames) used for regen detection. */
        private const val REGEN_WINDOW = 10

        /** Minimum DPF load drop within [REGEN_WINDOW] frames to count as a regen event. */
        private const val REGEN_DROP_THRESHOLD = 15f

        // Recommendation thresholds (percent)
        private const val LOAD_OK_MAX                = 60f
        private const val LOAD_HIGHWAY_MAX           = 75f
        private const val LOAD_URGENT_REGEN_MAX      = 85f
        // > 85 → SERVICE_REQUIRED
    }

    /**
     * Returns the current DPF status based on the most recent recorded telemetry frames.
     *
     * Queries the last [SAMPLE_FRAMES] frames across all sessions, ordered by timestamp.
     * Falls back to a safe default if no data is available.
     */
    suspend fun getDPFStatus(): DPFStatus = withContext(Dispatchers.IO) {
        Timber.d("$TAG: evaluating DPF status")

        // Fetch the most recent session to limit the frame query scope
        val latestSession = db.telemetrySessionDao().getLatestSession()
        if (latestSession == null) {
            Timber.w("$TAG: no sessions found — returning default DPF status")
            return@withContext defaultStatus()
        }

        // Pull all frames from the latest session, take the last SAMPLE_FRAMES.
        // If fewer exist, also look in the previous session to fill the window.
        val latestFrames = db.telemetryFrameDao()
            .getFramesForSessionOnce(latestSession.id)
            .takeLast(SAMPLE_FRAMES)

        val frames = if (latestFrames.size < SAMPLE_FRAMES) {
            val sessions = db.telemetrySessionDao().getAllSessions().first()
            val prev = sessions
                .sortedByDescending { it.startTime }
                .firstOrNull { it.id != latestSession.id }
            val prevFrames = if (prev != null) {
                val needed = SAMPLE_FRAMES - latestFrames.size
                db.telemetryFrameDao()
                    .getFramesForSessionOnce(prev.id)
                    .takeLast(needed)
            } else {
                emptyList()
            }
            (prevFrames + latestFrames).sortedBy { it.timestamp }
        } else {
            latestFrames.sortedBy { it.timestamp }
        }

        if (frames.isEmpty()) {
            Timber.w("$TAG: no telemetry frames found — returning default")
            return@withContext defaultStatus()
        }

        val currentLoad = frames.last().dpfLoadPct ?: 0f

        // Trend: (lastLoad - firstLoad) / elapsed hours × 100 → % per hour
        val trend = if (frames.size >= MIN_FRAMES_FOR_TREND) {
            val firstLoad = frames.first().dpfLoadPct ?: 0f
            val lastLoad  = frames.last().dpfLoadPct  ?: 0f
            val elapsedMs = frames.last().timestamp - frames.first().timestamp
            val elapsedHours = if (elapsedMs > 0) elapsedMs / 3_600_000f else 1f
            (lastLoad - firstLoad) / elapsedHours
        } else {
            0f
        }

        // Regen detection: scan for a window of REGEN_WINDOW consecutive frames where
        // load drops by more than REGEN_DROP_THRESHOLD percent.
        var lastRegenTime: Long? = null
        if (frames.size >= REGEN_WINDOW) {
            for (i in REGEN_WINDOW until frames.size) {
                val windowStart = frames[i - REGEN_WINDOW].dpfLoadPct ?: continue
                val windowEnd   = frames[i].dpfLoadPct ?: continue
                val drop = windowStart - windowEnd
                if (drop >= REGEN_DROP_THRESHOLD) {
                    // Keep the most recent regen event
                    val regenTs = frames[i].timestamp
                    if (lastRegenTime == null || regenTs > lastRegenTime!!) {
                        lastRegenTime = regenTs
                    }
                }
            }
        }

        val recommendation = when {
            currentLoad <= LOAD_OK_MAX          -> DPFRecommendation.OK
            currentLoad <= LOAD_HIGHWAY_MAX     -> DPFRecommendation.HIGHWAY_DRIVE_RECOMMENDED
            currentLoad <= LOAD_URGENT_REGEN_MAX -> DPFRecommendation.URGENT_REGEN
            else                                -> DPFRecommendation.SERVICE_REQUIRED
        }

        Timber.d(
            "$TAG: load=%.1f%% trend=%.2f%%/hr lastRegen=%s recommendation=%s",
            currentLoad, trend,
            lastRegenTime?.let { java.util.Date(it).toString() } ?: "none",
            recommendation
        )

        DPFStatus(
            currentLoad    = currentLoad,
            trend          = trend,
            lastRegenTime  = lastRegenTime,
            recommendation = recommendation
        )
    }

    private fun defaultStatus() = DPFStatus(
        currentLoad    = 0f,
        trend          = 0f,
        lastRegenTime  = null,
        recommendation = DPFRecommendation.OK
    )
}
