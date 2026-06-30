package com.caros.telemetry

// ─────────────────────────────────────────────────────────────────────────────
//  RoutePredictorEngine.kt — Learns route patterns from completed telemetry
//  sessions and offers a destination suggestion when a pattern matches the
//  current day-of-week + hour-of-day.
//
//  Pattern storage: one Room row per (destination, weekday, hour) tuple.
//  Destinations within ~300 m and ±1 h on the same weekday are folded into
//  the same row with an incrementing trip count.
//  A suggestion is only offered when tripCount ≥ 2 (seen at least twice).
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.location.Geocoder
import com.caros.db.CarOSDatabase
import com.caros.db.RoutePredictionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class RouteSuggestion(
    val destLat: Double,
    val destLon: Double,
    val label: String,
    val confidence: Int
)

@Singleton
class RoutePredictorEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CarOSDatabase
) {

    /**
     * Record a completed trip.  Called by [TelemetryService] when a session ends
     * with a valid final GPS position.
     *
     * @param destLat  Latitude of the position where the car stopped
     * @param destLon  Longitude of the position where the car stopped
     */
    suspend fun recordTrip(destLat: Double, destLon: Double) = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        val dow  = cal.get(Calendar.DAY_OF_WEEK)
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        val existing = db.routePredictionDao().findNearby(destLat, destLon, dow, hour)
        if (existing != null) {
            db.routePredictionDao().incrementCount(existing.id, System.currentTimeMillis())
            Timber.d(
                "RoutePredictorEngine: pattern %d incremented to %d trips",
                existing.id, existing.tripCount + 1
            )
        } else {
            val label = resolveLabel(destLat, destLon)
            db.routePredictionDao().upsert(
                RoutePredictionEntity(
                    dayOfWeek  = dow,
                    hourOfDay  = hour,
                    destLat    = destLat,
                    destLon    = destLon,
                    destLabel  = label,
                    lastUsedMs = System.currentTimeMillis()
                )
            )
            Timber.d("RoutePredictorEngine: new pattern stored — %s at dow=%d h=%d", label, dow, hour)
        }

        // Purge patterns not used in 90 days
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        db.routePredictionDao().deleteOld(cutoff)
    }

    /**
     * Returns a route suggestion if a pattern matches the current time, or null.
     * Only patterns seen ≥ 2 times are offered.
     */
    suspend fun getSuggestion(): RouteSuggestion? = withContext(Dispatchers.IO) {
        val cal   = Calendar.getInstance()
        val dow   = cal.get(Calendar.DAY_OF_WEEK)
        val hour  = cal.get(Calendar.HOUR_OF_DAY)
        val match = db.routePredictionDao().findBestMatch(dow, hour - 1, hour + 1)
        if (match != null && match.tripCount >= 2) {
            Timber.d(
                "RoutePredictorEngine: suggesting %s (seen %d×)",
                match.destLabel, match.tripCount
            )
            RouteSuggestion(match.destLat, match.destLon, match.destLabel, match.tripCount)
        } else null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveLabel(lat: Double, lon: Double): String {
        val fallback = "%.4f, %.4f".format(lat, lon)
        return try {
            val geocoder = Geocoder(context)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val a = addresses[0]
                listOfNotNull(a.thoroughfare, a.locality)
                    .joinToString(", ")
                    .ifBlank { fallback }
            } else fallback
        } catch (e: Exception) {
            Timber.w(e, "RoutePredictorEngine: geocoding failed")
            fallback
        }
    }
}
