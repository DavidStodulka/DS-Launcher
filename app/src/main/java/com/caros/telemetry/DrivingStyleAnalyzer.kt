package com.caros.telemetry

// ─────────────────────────────────────────────────────────────────────────────
//  DrivingStyleAnalyzer.kt — Post-session driving behaviour scorer
//
//  Produces a multi-dimensional score for each completed session:
//    • ecoScore:        fuel-efficiency and rpm discipline
//    • sportScore:      dynamic driving intensity
//    • mechanicalScore: drivetrain care (cold-start discipline, oil temperature)
//    • smoothnessScore: throttle modulation consistency
//
//  Scores are in range [0, 100]. Recommendations are generated for the worst-
//  scoring dimensions.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.CarOSDatabase
import com.caros.db.TelemetryFrameEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Driving style score for a single session.
 *
 * @param sessionId       The session this score belongs to.
 * @param ecoScore        0–100. Higher = more fuel-efficient driving.
 * @param sportScore      0–100. Higher = more dynamic/spirited driving.
 * @param mechanicalScore 0–100. Higher = more mechanically sympathetic behaviour.
 * @param smoothnessScore 0–100. Higher = smoother throttle application.
 * @param recommendations List of actionable improvement suggestions.
 */
data class DrivingStyleScore(
    val sessionId: Long,
    val ecoScore: Int,
    val sportScore: Int,
    val mechanicalScore: Int,
    val smoothnessScore: Int,
    val recommendations: List<String>
)

@Singleton
class DrivingStyleAnalyzer @Inject constructor(
    private val db: CarOSDatabase
) {

    companion object {
        private const val TAG = "DrivingStyleAnalyzer"

        // ecoScore thresholds
        private const val ECO_HIGH_RPM_THRESHOLD = 3_000
        private const val ECO_RPM_WEIGHT         = 0.6f
        private const val ECO_THROTTLE_WEIGHT    = 0.4f

        // sportScore thresholds
        private const val SPORT_HIGH_RPM_THRESHOLD  = 4_000
        private const val SPORT_G_FACTOR            = 20f
        private const val SPORT_RPM_FACTOR          = 0.5f
        private const val SPORT_LAT_G_FACTOR        = 15f

        // mechanicalScore — warm-up phase
        private const val WARMUP_MINUTES_MS        = 3L * 60 * 1_000
        private const val WARMUP_COOLANT_THRESHOLD = 60f     // °C
        private const val WARMUP_THROTTLE_PENALTY  = 20f     // > 20% throttle during warmup
        private const val OIL_OVERHEAT_THRESHOLD   = 115f    // °C

        // smoothnessScore
        private const val SMOOTHNESS_STDDEV_FACTOR = 2f

        // Recommendation thresholds
        private const val SCORE_WARNING_THRESHOLD   = 60
        private const val SCORE_CRITICAL_THRESHOLD  = 40
    }

    /**
     * Analyses a completed session and returns a [DrivingStyleScore].
     *
     * Fetches all frames from the DB, computes the four score dimensions, and
     * builds a prioritised list of recommendations.
     *
     * @param sessionId ID of the session to analyse.
     * @return [DrivingStyleScore] — falls back to a zero-data score if no frames exist.
     */
    suspend fun analyzeSession(sessionId: Long): DrivingStyleScore = withContext(Dispatchers.IO) {
        Timber.d("$TAG: analysing session $sessionId")

        val frames = db.telemetryFrameDao().getFramesForSessionOnce(sessionId)
        if (frames.isEmpty()) {
            Timber.w("$TAG: no frames for session $sessionId — returning default score")
            return@withContext defaultScore(sessionId)
        }

        val session = db.telemetrySessionDao().getSessionById(sessionId)
        val sessionStartMs = session?.startTime ?: frames.first().timestamp

        val ecoScore        = computeEcoScore(frames)
        val sportScore      = computeSportScore(frames)
        val mechanicalScore = computeMechanicalScore(frames, sessionStartMs)
        val smoothnessScore = computeSmoothnessScore(frames)

        val recommendations = buildRecommendations(
            ecoScore, sportScore, mechanicalScore, smoothnessScore, frames
        )

        Timber.i(
            "$TAG: session %d — eco=%d sport=%d mech=%d smooth=%d",
            sessionId, ecoScore, sportScore, mechanicalScore, smoothnessScore
        )

        DrivingStyleScore(
            sessionId       = sessionId,
            ecoScore        = ecoScore,
            sportScore      = sportScore,
            mechanicalScore = mechanicalScore,
            smoothnessScore = smoothnessScore,
            recommendations = recommendations
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Score calculators
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ecoScore = 100 - (avgThrottle * ECO_THROTTLE_WEIGHT + rpmAbove3000Pct * ECO_RPM_WEIGHT)
     * Clamped to [0, 100].
     */
    private fun computeEcoScore(frames: List<TelemetryFrameEntity>): Int {
        val movingFrames = frames.filter { (it.speedKmh ?: 0f) > 0f }
        if (movingFrames.isEmpty()) return 100

        val avgThrottle = movingFrames.map { it.throttlePct ?: 0f }.average().toFloat()
        val rpmAbove3000 = movingFrames.count { (it.rpm ?: 0) > ECO_HIGH_RPM_THRESHOLD }
        val rpmAbove3000Pct = rpmAbove3000.toFloat() / movingFrames.size * 100f

        val penalty = (avgThrottle * ECO_THROTTLE_WEIGHT) + (rpmAbove3000Pct * ECO_RPM_WEIGHT)
        return (100f - penalty).toInt().coerceIn(0, 100)
    }

    /**
     * sportScore = (avgLongG * SPORT_G_FACTOR + rpmAbove4000Pct * SPORT_RPM_FACTOR + maxLateralG * SPORT_LAT_G_FACTOR)
     * Clamped to [0, 100].
     */
    private fun computeSportScore(frames: List<TelemetryFrameEntity>): Int {
        if (frames.isEmpty()) return 0

        val avgLongG = frames.map { abs(it.longitudinalG ?: 0f) }.average().toFloat()

        val movingFrames = frames.filter { (it.speedKmh ?: 0f) > 0f }
        val rpmAbove4000Pct = if (movingFrames.isNotEmpty()) {
            movingFrames.count { (it.rpm ?: 0) > SPORT_HIGH_RPM_THRESHOLD }
                .toFloat() / movingFrames.size * 100f
        } else 0f

        val maxLateralG = frames.maxOfOrNull { abs(it.lateralG ?: 0f) } ?: 0f

        val raw = (avgLongG * SPORT_G_FACTOR) +
                  (rpmAbove4000Pct * SPORT_RPM_FACTOR) +
                  (maxLateralG * SPORT_LAT_G_FACTOR)

        return raw.toInt().coerceIn(0, 100)
    }

    /**
     * mechanicalScore starts at 100 and accrues penalties for:
     *   - Aggressive throttle (> 20 %) during the first 3 minutes while coolant < 60 °C
     *   - Oil temperature above 115 °C (each such frame = -0.05 pts)
     * Clamped to [0, 100].
     */
    private fun computeMechanicalScore(
        frames: List<TelemetryFrameEntity>,
        sessionStartMs: Long
    ): Int {
        var score = 100f
        val warmupEndMs = sessionStartMs + WARMUP_MINUTES_MS

        for (frame in frames) {
            // Cold-start aggression penalty
            if (frame.timestamp <= warmupEndMs) {
                val coolant = frame.coolantTemp ?: WARMUP_COOLANT_THRESHOLD
                val throttle = frame.throttlePct ?: 0f
                if (coolant < WARMUP_COOLANT_THRESHOLD && throttle > WARMUP_THROTTLE_PENALTY) {
                    // Penalty proportional to how far above threshold
                    score -= (throttle - WARMUP_THROTTLE_PENALTY) * 0.1f
                }
            }

            // Oil overheat penalty: -0.05 per frame above threshold
            val oilTemp = frame.oilTemp ?: 0f
            if (oilTemp > OIL_OVERHEAT_THRESHOLD) {
                score -= 0.05f
            }
        }

        return score.toInt().coerceIn(0, 100)
    }

    /**
     * smoothnessScore = 100 - stdDev(throttleDeltas) * SMOOTHNESS_STDDEV_FACTOR
     * throttleDeltas = per-frame change in throttle position.
     * Clamped to [0, 100].
     */
    private fun computeSmoothnessScore(frames: List<TelemetryFrameEntity>): Int {
        if (frames.size < 2) return 100

        val throttles = frames.map { it.throttlePct ?: 0f }
        val deltas    = throttles.zipWithNext { a, b -> b - a }

        val mean    = deltas.average().toFloat()
        val variance = deltas.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev  = sqrt(variance)

        return (100f - stdDev * SMOOTHNESS_STDDEV_FACTOR).toInt().coerceIn(0, 100)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Recommendations
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRecommendations(
        ecoScore: Int,
        sportScore: Int,
        mechanicalScore: Int,
        smoothnessScore: Int,
        frames: List<TelemetryFrameEntity>
    ): List<String> {
        val recs = mutableListOf<String>()

        if (ecoScore < SCORE_CRITICAL_THRESHOLD) {
            recs.add("Significant fuel waste detected — shift up earlier and reduce motorway throttle.")
        } else if (ecoScore < SCORE_WARNING_THRESHOLD) {
            recs.add("Consider shifting to a higher gear sooner and easing off high-RPM acceleration.")
        }

        if (mechanicalScore < SCORE_CRITICAL_THRESHOLD) {
            val hasWarmupViolation = frames.any { f ->
                (f.coolantTemp ?: 100f) < WARMUP_COOLANT_THRESHOLD &&
                (f.throttlePct ?: 0f) > WARMUP_THROTTLE_PENALTY
            }
            val hasOilOverheat = frames.any { f ->
                (f.oilTemp ?: 0f) > OIL_OVERHEAT_THRESHOLD
            }
            if (hasWarmupViolation) {
                recs.add("Engine was worked hard before reaching operating temperature — let it warm for 3 minutes.")
            }
            if (hasOilOverheat) {
                recs.add("Oil temperature exceeded 115 °C — check cooling, oil level, and consider synthetic oil.")
            }
        } else if (mechanicalScore < SCORE_WARNING_THRESHOLD) {
            recs.add("Avoid high throttle inputs until the engine reaches 60 °C coolant temperature.")
        }

        if (smoothnessScore < SCORE_CRITICAL_THRESHOLD) {
            recs.add("Very erratic throttle modulation detected — try smoother, more gradual acceleration inputs.")
        } else if (smoothnessScore < SCORE_WARNING_THRESHOLD) {
            recs.add("Throttle smoothness could improve — anticipate traffic flow to reduce sharp inputs.")
        }

        if (sportScore > 80) {
            recs.add("Very dynamic session recorded — ensure tyres and brakes are in good condition.")
        }

        if (recs.isEmpty()) {
            recs.add("Great drive! All metrics are within excellent ranges.")
        }

        return recs
    }

    private fun defaultScore(sessionId: Long) = DrivingStyleScore(
        sessionId       = sessionId,
        ecoScore        = 0,
        sportScore      = 0,
        mechanicalScore = 0,
        smoothnessScore = 0,
        recommendations = listOf("No telemetry data available for this session.")
    )
}
