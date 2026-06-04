package com.caros.elevation

// ─────────────────────────────────────────────────────────────────────────────
//  RouteRecorder.kt — Starts/stops a route recording session by delegating
//  location collection to ElevationTracker, then persists the result as a
//  RouteEntity (with embedded GPX) in the Room database.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.location.Location
import com.caros.db.CarOSDatabase
import com.caros.db.RouteEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteRecorder @Inject constructor(
    private val elevationTracker: ElevationTracker,
    private val gpxExporter: GPXExporter,
    private val db: CarOSDatabase,
    @ApplicationContext private val context: Context
) {
    /**
     * Background scope used for any auxiliary coroutine work.
     * Uses [SupervisorJob] so individual child failures don't cancel the recorder.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var routeName = ""
    private var routeStartTime = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start a new route recording.
     *
     * Clears any previously accumulated points from [ElevationTracker] and
     * begins requesting location updates.
     *
     * @param name Human-readable route name.  Defaults to a timestamp-based name.
     */
    fun startRecording(name: String = "Route ${System.currentTimeMillis()}") {
        routeName = name
        routeStartTime = System.currentTimeMillis()
        elevationTracker.clearCurrentRoute()
        elevationTracker.startTracking()
    }

    /**
     * Stop recording, persist the route to the database, and return its row ID.
     *
     * - Stops location updates via [ElevationTracker.stopTracking].
     * - Serialises the track to a GPX string via [GPXExporter].
     * - Computes aggregate statistics (distance, ascent, descent).
     * - Inserts a [RouteEntity] into Room and returns the auto-generated ID.
     *
     * @return The new database row ID, or -1L if no points were recorded.
     */
    suspend fun stopAndSave(): Long {
        elevationTracker.stopTracking()

        val points = elevationTracker.points.value
        if (points.isEmpty()) return -1L

        val gpxData = gpxExporter.toGPXString(points, routeName)
        val distanceKm = calculateDistanceKm(points)
        val ascentM = elevationTracker.getTotalAscent()
        val descentM = elevationTracker.getTotalDescent()

        return withContext(Dispatchers.IO) {
            db.routeDao().insert(
                RouteEntity(
                    name = routeName,
                    date = routeStartTime,
                    distanceKm = distanceKm.toDouble(),
                    totalAscentM = ascentM.toDouble(),
                    totalDescentM = descentM.toDouble(),
                    gpxData = gpxData
                )
            )
        }
    }

    /**
     * Cancel an ongoing recording without saving.
     * Stops location updates and discards all accumulated points.
     */
    fun cancelRecording() {
        elevationTracker.stopTracking()
        elevationTracker.clearCurrentRoute()
    }

    /**
     * Observe all saved routes from the database, ordered newest-first.
     */
    fun getSavedRoutes() = db.routeDao().getAllRoutes()

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Compute the total route distance in kilometres by summing Vincenty
     * distances between consecutive [ElevationPoint]s.
     */
    private fun calculateDistanceKm(points: List<ElevationPoint>): Float {
        if (points.size < 2) return 0f
        var totalM = 0f
        val arr = FloatArray(1)
        for (i in 1 until points.size) {
            Location.distanceBetween(
                points[i - 1].lat, points[i - 1].lon,
                points[i].lat,     points[i].lon,
                arr
            )
            totalM += arr[0]
        }
        return totalM / 1000f
    }
}
