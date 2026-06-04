package com.caros.race

// ─────────────────────────────────────────────────────────────────────────────
//  RaceSession.kt — In-memory domain model for a single race/performance
//  session.  Persisted to the database via RaceChronoManager.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * In-memory snapshot of a completed or ongoing race session.
 *
 * This is the domain model layer; for persistence see [com.caros.db.RaceSessionEntity].
 *
 * @param id            Local database row ID, 0 when not yet persisted
 * @param date          Session start time as Unix epoch millis
 * @param location      Free-text location description, e.g. "Circuit Brno" or "Motorway A1"
 * @param measurements  All performance measurement results recorded in this session
 * @param lapResults    All lap timer results recorded in this session
 * @param sessionType   High-level classification of what was recorded
 */
data class RaceSession(
    val id: Long = 0,
    val date: Long = System.currentTimeMillis(),
    val location: String = "",
    val measurements: List<Measurement> = emptyList(),
    val lapResults: List<LapResult> = emptyList(),
    val sessionType: SessionType = SessionType.PERFORMANCE
) {
    /** High-level classification of a race session. */
    enum class SessionType {
        /** Only performance measurements (0–100, 0–200, 80–120, braking, custom). */
        PERFORMANCE,
        /** Only lap-timing runs. */
        LAP_TIMING,
        /** Both performance measurements and lap timing in the same session. */
        MIXED
    }

    /** Returns true if this session contains at least one completed measurement or lap. */
    val hasResults: Boolean
        get() = measurements.isNotEmpty() || lapResults.isNotEmpty()

    /**
     * The best (fastest) 0–100 result in this session, or null if none were recorded.
     */
    val best0to100: Measurement?
        get() = measurements
            .filter { it.type == MeasurementType.ZERO_TO_100 }
            .minByOrNull { it.durationMs }

    /**
     * The best lap time recorded in this session, or null if no laps were completed.
     */
    val bestLap: LapResult?
        get() = lapResults.minByOrNull { it.timeMs }
}
