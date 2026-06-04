package com.caros.service

// ─────────────────────────────────────────────────────────────────────────────
//  ServiceItem.kt — Domain model for a single maintenance service status item
//
//  ServiceType mirrors the DB enum from ServiceHistoryEntity but lives in the
//  service package as the UI/domain layer representation.
//  ServiceUrgency drives colour-coding and notification priority.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Categories of maintenance service tracked by CarOS for the Seat Leon 5F CLHA.
 * Must stay in sync with [com.caros.db.ServiceType] in the DB layer.
 */
enum class ServiceType {
    OIL,
    DPF,
    DSG_OIL,       // Maps to db.ServiceType.DSG
    COOLANT,
    AIR_FILTER,
    FUEL_FILTER,
    GLOW_PLUGS,
    TIMING_BELT
}

/** Urgency level for a service item — drives notification priority and UI colour. */
enum class ServiceUrgency {
    /** Service is within normal interval — no action needed. */
    OK,
    /** Service is approaching but not yet urgent — informational only. */
    INFO,
    /** Service is overdue or approaching a critical threshold. */
    WARNING,
    /** Service is immediately required — safety or drivetrain at risk. */
    URGENT
}

/**
 * A single maintenance advisory item produced by [ServiceAdvisor].
 *
 * @param type             Category of this service item.
 * @param status           Urgency level — drives notification and display priority.
 * @param currentValueStr  Human-readable current state, e.g. "Oil life: 23 %".
 * @param recommendation   Short actionable recommendation for the driver.
 * @param dueInKm          Kilometres until next service is due, or null if unknown / not applicable.
 * @param dueInDays        Calendar days until next service is due, or null if not applicable.
 * @param dataReasoning    Ordered list of factors that contributed to this assessment,
 *                         e.g. ["12 cold starts detected", "42 min idle time"].
 */
data class ServiceItem(
    val type: ServiceType,
    val status: ServiceUrgency,
    val currentValueStr: String,
    val recommendation: String,
    val dueInKm: Int?,
    val dueInDays: Int?,
    val dataReasoning: List<String>
)
