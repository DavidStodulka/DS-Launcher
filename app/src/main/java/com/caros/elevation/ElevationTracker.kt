package com.caros.elevation

// ─────────────────────────────────────────────────────────────────────────────
//  ElevationTracker.kt — Tracks GPS altitude in real time and computes slope
//  percent between consecutive location fixes.  Exposes live StateFlows and
//  a list of all sampled points for the current route.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single GPS elevation sample with associated slope.
 *
 * @param lat          Latitude in decimal degrees
 * @param lon          Longitude in decimal degrees
 * @param altM         Altitude above WGS-84 ellipsoid in metres (calibrated)
 * @param timestamp    Epoch millis of the GPS fix
 * @param slopePercent Grade in percent from the previous point.
 *                     Positive = uphill, negative = downhill.  0f for the first point.
 */
data class ElevationPoint(
    val lat: Double,
    val lon: Double,
    val altM: Float,
    val timestamp: Long,
    val slopePercent: Float = 0f
)

@Singleton
class ElevationTracker @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationListener {

    // ── Exposed state ─────────────────────────────────────────────────────────

    /** Current GPS altitude in metres (after calibration offset). */
    private val _currentAlt = MutableStateFlow(0f)
    val currentAlt: StateFlow<Float> = _currentAlt

    /** Current grade in percent between the two most recent GPS fixes. */
    private val _currentSlope = MutableStateFlow(0f)
    val currentSlope: StateFlow<Float> = _currentSlope

    /** Ordered list of all elevation points recorded since the last [clearCurrentRoute]. */
    private val _points = MutableStateFlow<List<ElevationPoint>>(emptyList())
    val points: StateFlow<List<ElevationPoint>> = _points

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Altitude calibration offset in metres.
     * Applied as: calibratedAlt = rawGpsAlt + manualCalibrationOffset
     * Set via [calibrateAltitude].
     */
    private var manualCalibrationOffset = 0f

    private var lastPoint: ElevationPoint? = null

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Register for GPS and network location updates.
     * Requires [android.Manifest.permission.ACCESS_FINE_LOCATION].
     * GPS at 500 ms / 0 m; network at 1000 ms / 0 m.
     */
    fun startTracking() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                this
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                this
            )
        } catch (e: SecurityException) {
            // Permission not granted — caller must handle via request flow
        }
    }

    /** Unregister location listeners. */
    fun stopTracking() {
        locationManager.removeUpdates(this)
    }

    // ── Calibration ───────────────────────────────────────────────────────────

    /**
     * Override the GPS altitude with a known reference altitude (e.g. from a
     * topo map or barometric sensor).
     *
     * @param knownAltM The true altitude at the current position in metres
     */
    fun calibrateAltitude(knownAltM: Float) {
        // offset = knownAlt - currentRawAlt
        manualCalibrationOffset = knownAltM - (_currentAlt.value - manualCalibrationOffset)
    }

    // ── LocationListener ─────────────────────────────────────────────────────

    override fun onLocationChanged(location: Location) {
        val rawAlt = location.altitude.toFloat()
        val alt = rawAlt + manualCalibrationOffset
        _currentAlt.value = alt

        val prev = lastPoint
        val slope: Float = if (prev != null) {
            val distArr = FloatArray(1)
            Location.distanceBetween(
                prev.lat, prev.lon,
                location.latitude, location.longitude,
                distArr
            )
            val distM = distArr[0]
            if (distM > 1f) ((alt - prev.altM) / distM) * 100f else 0f
        } else {
            0f
        }

        _currentSlope.value = slope

        val point = ElevationPoint(
            lat = location.latitude,
            lon = location.longitude,
            altM = alt,
            timestamp = location.time,
            slopePercent = slope
        )
        // Cap the in-memory trace so multi-hour recordings can't exhaust the heap
        val updated = _points.value + point
        _points.value = if (updated.size > MAX_POINTS) updated.takeLast(MAX_POINTS) else updated
        lastPoint = point
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Deprecated in API 29 — no action required
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    // ── Route helpers ─────────────────────────────────────────────────────────

    /** Clear all recorded points and reset the last-point reference for the current route. */
    fun clearCurrentRoute() {
        _points.value = emptyList()
        lastPoint = null
    }

    /**
     * Total cumulative ascent in metres across all recorded points.
     * Computed as the sum of all positive altitude deltas.
     */
    fun getTotalAscent(): Float =
        _points.value.zipWithNext()
            .sumOf { (a, b) -> if (b.altM > a.altM) (b.altM - a.altM).toDouble() else 0.0 }
            .toFloat()

    /**
     * Total cumulative descent in metres across all recorded points.
     * Computed as the sum of all negative altitude deltas (returned as positive).
     */
    fun getTotalDescent(): Float =
        _points.value.zipWithNext()
            .sumOf { (a, b) -> if (b.altM < a.altM) (a.altM - b.altM).toDouble() else 0.0 }
            .toFloat()

    companion object {
        /** ~14 h of points at 1 Hz — enough for any drive, bounded memory. */
        private const val MAX_POINTS = 50_000
    }
}
