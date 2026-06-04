package com.caros.race

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  GForceCalculator.kt — Derives lateral/longitudinal G-force from GPS or
//  accelerometer data.  GPS is the primary source; the accelerometer path
//  serves as a fallback when GPS fix quality is poor.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Instantaneous G-force snapshot.
 *
 * @param lateral       Left/right G — positive = right cornering force
 * @param longitudinal  Forward/backward G — positive = acceleration, negative = braking
 * @param timestamp     Wall-clock millis at which the sample was computed
 */
data class GForce(
    val lateral: Float,
    val longitudinal: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class GForceCalculator @Inject constructor() : SensorEventListener {

    // ── Exposed state ─────────────────────────────────────────────────────────

    private val _gForce = MutableStateFlow(GForce(0f, 0f))
    val gForce: StateFlow<GForce> = _gForce

    // ── GPS-based computation state ───────────────────────────────────────────

    private var lastSpeedMs = 0f
    private var lastBearing = 0f
    private var lastTimestampMs = 0L
    private var maxLateral = 0f
    private var maxLongitudinal = 0f

    /**
     * Process a GPS location update and derive G-force from speed and bearing
     * changes.  This is the preferred path — GPS gives absolute acceleration
     * without the calibration drift inherent in accelerometers.
     *
     * @param speedMs      Current speed in metres per second
     * @param bearing      Current heading in degrees [0, 360)
     * @param timestampMs  Epoch millis of the GPS fix
     */
    fun processGPSUpdate(speedMs: Float, bearing: Float, timestampMs: Long) {
        if (lastTimestampMs == 0L) {
            // First sample — initialise state but emit nothing yet
            lastSpeedMs = speedMs
            lastBearing = bearing
            lastTimestampMs = timestampMs
            return
        }

        val dt = (timestampMs - lastTimestampMs) / 1000f
        if (dt <= 0f) return

        // Longitudinal G: linear acceleration along the vehicle axis
        val longG = ((speedMs - lastSpeedMs) / dt) / 9.81f

        // Lateral G: centripetal acceleration from change in heading
        // a_lat = v * dθ/dt  (small-angle approximation, bearing in radians)
        val bearingDeltaRad = Math.toRadians((bearing - lastBearing).toDouble()).toFloat()
        val latG = (speedMs * bearingDeltaRad / dt) / 9.81f

        val clampedLat = latG.coerceIn(-3f, 3f)
        val clampedLong = longG.coerceIn(-3f, 3f)

        _gForce.value = GForce(clampedLat, clampedLong, timestampMs)

        if (kotlin.math.abs(clampedLat) > maxLateral) maxLateral = kotlin.math.abs(clampedLat)
        if (kotlin.math.abs(clampedLong) > maxLongitudinal) maxLongitudinal = kotlin.math.abs(clampedLong)

        lastSpeedMs = speedMs
        lastBearing = bearing
        lastTimestampMs = timestampMs
    }

    // ── Accelerometer fallback ────────────────────────────────────────────────

    /**
     * Low-pass filter coefficient.  Smaller = smoother but slower to react.
     * 0.1 ≈ 90 ms smoothing at 10 Hz sensor rate.
     */
    private val alpha = 0.1f

    /** Running gravity estimate used to separate static gravity from dynamic acceleration. */
    private val gravity = FloatArray(3) { 0f }

    /**
     * [SensorEventListener] callback.  Processes [Sensor.TYPE_ACCELEROMETER] events
     * using a simple high-pass / low-pass decomposition to isolate vehicle-induced
     * acceleration from gravitational pull.
     */
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Update gravity estimate with low-pass filter
        gravity[0] = alpha * event.values[0] + (1f - alpha) * gravity[0]
        gravity[1] = alpha * event.values[1] + (1f - alpha) * gravity[1]
        gravity[2] = alpha * event.values[2] + (1f - alpha) * gravity[2]

        // High-pass remainder = vehicle-induced acceleration
        val latG = (event.values[0] - gravity[0]) / 9.81f
        val longG = (event.values[1] - gravity[1]) / 9.81f

        _gForce.value = GForce(latG.coerceIn(-3f, 3f), longG.coerceIn(-3f, 3f))
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
        // No action required — accuracy changes are handled implicitly via
        // the GPS fallback selection in the calling layer.
    }

    // ── Session helpers ───────────────────────────────────────────────────────

    /** Reset peak G accumulators and GPS state at the start of a new session. */
    fun resetSession() {
        maxLateral = 0f
        maxLongitudinal = 0f
        lastTimestampMs = 0L
        lastSpeedMs = 0f
        lastBearing = 0f
        gravity.fill(0f)
    }

    /**
     * Returns the session peak G-force values recorded since the last
     * [resetSession] call.  [GForce.timestamp] is the current wall time.
     */
    fun getMaxG(): GForce = GForce(maxLateral, maxLongitudinal)
}
