package com.caros.race

// ─────────────────────────────────────────────────────────────────────────────
//  AggressiveDrivingDetector.kt — Real-time detection of aggressive manoeuvres
//  from G-force data, with immediate Czech TTS feedback and a running
//  aggression score for the current driving session.
//
//  Thresholds (measured in units of g):
//    Hard braking:       longitudinal G < -0.40 g
//    Hard acceleration:  longitudinal G >  0.35 g
//    Sharp cornering:    |lateral G|    >  0.35 g
//
//  TTS alerts are debounced per-type at 3 s to avoid flooding.
//  Score starts at 100; each event subtracts 1–5 points proportional to severity.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.can.CANFrame
import com.caros.voice.TextToSpeechManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class AggressionType {
    HARD_BRAKING,
    HARD_ACCELERATION,
    SHARP_CORNERING
}

data class AggressiveEvent(
    val type: AggressionType,
    /** Normalised severity: 0.0 = just above threshold, 1.0 = maximum. */
    val severity: Float,
    val timestampMs: Long
)

@Singleton
class AggressiveDrivingDetector @Inject constructor(
    private val tts: TextToSpeechManager
) {
    companion object {
        private const val BRAKING_THRESHOLD   = -0.40f
        private const val ACCEL_THRESHOLD     =  0.35f
        private const val CORNERING_THRESHOLD =  0.35f
        private const val TTS_DEBOUNCE_MS     = 3_000L
        private const val MAX_PENALTY_PER_EVENT = 5
    }

    private val _score = MutableStateFlow(100)
    /** Running aggression score for this session (100 = perfect, lower = more aggressive). */
    val sessionScore: StateFlow<Int> = _score.asStateFlow()

    private val _events = MutableStateFlow<List<AggressiveEvent>>(emptyList())
    /** Live list of aggressive events in the current session. */
    val events: StateFlow<List<AggressiveEvent>> = _events.asStateFlow()

    private var lastBrakingMs   = 0L
    private var lastAccelMs     = 0L
    private var lastCorneringMs = 0L

    /**
     * Convenience method — prefers hardware ESP G-force from [frame.espAcceleration]
     * when available, falls back to GPS-derived [GForce] via [processGForce].
     * Call this from TelemetryService instead of processGForce() directly.
     */
    fun updateFromCANFrame(frame: CANFrame, fallback: GForce) {
        val esp = frame.espAcceleration
        if (esp != null) {
            processGForce(GForce(esp.lateralG, esp.longitudinalG, frame.timestamp))
        } else {
            processGForce(fallback)
        }
    }

    /**
     * Process a [GForce] sample.  Called at the CAN frame rate (~2 Hz from
     * TelemetryService) so real thresholds are easy to reach.
     */
    fun processGForce(gForce: GForce) {
        val now  = gForce.timestamp
        val long = gForce.longitudinal
        val lat  = gForce.lateral

        if (long < BRAKING_THRESHOLD && now - lastBrakingMs > TTS_DEBOUNCE_MS) {
            val severity = ((-long - 0.40f) / 0.60f).coerceIn(0f, 1f)
            record(AggressiveEvent(AggressionType.HARD_BRAKING, severity, now))
            tts.speak("Tvrdé brzdění")
            lastBrakingMs = now
        }

        if (long > ACCEL_THRESHOLD && now - lastAccelMs > TTS_DEBOUNCE_MS) {
            val severity = ((long - 0.35f) / 0.65f).coerceIn(0f, 1f)
            record(AggressiveEvent(AggressionType.HARD_ACCELERATION, severity, now))
            lastAccelMs = now
        }

        if (abs(lat) > CORNERING_THRESHOLD && now - lastCorneringMs > TTS_DEBOUNCE_MS) {
            val severity = ((abs(lat) - 0.35f) / 0.65f).coerceIn(0f, 1f)
            record(AggressiveEvent(AggressionType.SHARP_CORNERING, severity, now))
            tts.speak("Ostrá zatáčka")
            lastCorneringMs = now
        }
    }

    private fun record(event: AggressiveEvent) {
        val updated = _events.value + event
        _events.value = updated

        val totalPenalty = updated.sumOf { (it.severity * MAX_PENALTY_PER_EVENT).toDouble() }.toInt()
        _score.value = (100 - totalPenalty).coerceIn(0, 100)

        Timber.d(
            "AggressiveDrivingDetector: %s severity=%.2f score=%d",
            event.type, event.severity, _score.value
        )
    }

    /** Reset all state at the start of a new driving session. */
    fun resetSession() {
        _events.value  = emptyList()
        _score.value   = 100
        lastBrakingMs   = 0L
        lastAccelMs     = 0L
        lastCorneringMs = 0L
        Timber.d("AggressiveDrivingDetector: session reset")
    }
}
