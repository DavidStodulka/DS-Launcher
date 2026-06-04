package com.caros.elevation

// ─────────────────────────────────────────────────────────────────────────────
//  ElevationProfile.kt — Immutable value object summarising an entire route's
//  elevation characteristics.  Built from a list of ElevationPoints via the
//  companion factory.
// ─────────────────────────────────────────────────────────────────────────────

import android.location.Location

/**
 * Immutable summary of the elevation characteristics of a saved or in-progress route.
 *
 * @param points           Ordered list of GPS/altitude samples that make up this profile
 * @param totalDistanceKm  Route length in kilometres computed from Haversine distances
 * @param totalAscentM     Cumulative uphill gain in metres
 * @param totalDescentM    Cumulative downhill loss in metres (positive value)
 * @param minAltM          Minimum altitude recorded in the route (metres)
 * @param maxAltM          Maximum altitude recorded in the route (metres)
 * @param routeId          Database row ID of the associated [com.caros.db.RouteEntity], or 0
 */
data class ElevationProfile(
    val points: List<ElevationPoint>,
    val totalDistanceKm: Float,
    val totalAscentM: Float,
    val totalDescentM: Float,
    val minAltM: Float,
    val maxAltM: Float,
    val routeId: Long = 0L
) {
    /** Net altitude change: positive = net climb, negative = net descent. */
    val netAltitudeChangeM: Float
        get() = totalAscentM - totalDescentM

    /** True when at least one point has been recorded. */
    val isEmpty: Boolean
        get() = points.isEmpty()

    companion object {

        /**
         * Build an [ElevationProfile] from an ordered list of [ElevationPoint]s.
         *
         * Distance is computed using [Location.distanceBetween] (Vincenty
         * algorithm) between consecutive points.  Ascent/descent are computed as
         * the sum of positive/negative altitude deltas.
         *
         * @param points  Track points; if empty an empty profile is returned
         * @param routeId Database row ID to associate, defaults to 0
         */
        fun from(points: List<ElevationPoint>, routeId: Long = 0L): ElevationProfile {
            if (points.isEmpty()) {
                return ElevationProfile(
                    points = emptyList(),
                    totalDistanceKm = 0f,
                    totalAscentM = 0f,
                    totalDescentM = 0f,
                    minAltM = 0f,
                    maxAltM = 0f,
                    routeId = routeId
                )
            }

            // ── Distance ──────────────────────────────────────────────────────
            var totalDistM = 0f
            val distArr = FloatArray(1)
            for (i in 1 until points.size) {
                Location.distanceBetween(
                    points[i - 1].lat, points[i - 1].lon,
                    points[i].lat,     points[i].lon,
                    distArr
                )
                totalDistM += distArr[0]
            }

            // ── Ascent / descent ──────────────────────────────────────────────
            val ascent = points.zipWithNext()
                .sumOf { (a, b) -> if (b.altM > a.altM) (b.altM - a.altM).toDouble() else 0.0 }
                .toFloat()

            val descent = points.zipWithNext()
                .sumOf { (a, b) -> if (b.altM < a.altM) (a.altM - b.altM).toDouble() else 0.0 }
                .toFloat()

            return ElevationProfile(
                points = points,
                totalDistanceKm = totalDistM / 1000f,
                totalAscentM = ascent,
                totalDescentM = descent,
                minAltM = points.minOf { it.altM },
                maxAltM = points.maxOf { it.altM },
                routeId = routeId
            )
        }
    }
}
