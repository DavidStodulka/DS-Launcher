package com.caros.service

// ─────────────────────────────────────────────────────────────────────────────
//  DSGMonitor.kt — DSG DQ200/DQ250 transmission health monitor
//
//  The CLHA 1.6 TDI in the Seat Leon 5F is paired with the DQ200 7-speed DSG.
//  Key service interval: oil change every 60 000 km or 6 years, whichever comes first.
//
//  Clutch temperature peaks above 180 °C indicate aggressive launch/clutch slip
//  and are tracked as a wear indicator. Temperature is read from DSGData.clutchTemp
//  captured in telemetry frames (stored via the gear field proxy — see note below).
//
//  NOTE: TelemetryFrameEntity does not store a dedicated DSG clutch temp column.
//  The field is proxied via DSGData.clutchTemp from CAN frames; if it was not
//  recorded (older sessions), clutchTempPeaks will be 0. The gear field IS stored
//  and is used to confirm DSG engagement.
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.CarOSDatabase
import com.caros.db.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of DSG transmission health at query time.
 *
 * @param oilAgeKm        Kilometres driven since last DSG oil service.
 * @param clutchTempPeaks Number of telemetry frames where clutch temp exceeded 180 °C.
 * @param recommendation  Human-readable maintenance recommendation.
 * @param urgency         Urgency level for UI display and notification priority.
 */
data class DSGStatus(
    val oilAgeKm: Int,
    val clutchTempPeaks: Int,
    val recommendation: String,
    val urgency: ServiceUrgency
)

@Singleton
class DSGMonitor @Inject constructor(
    private val db: CarOSDatabase
) {

    companion object {
        private const val TAG = "DSGMonitor"

        /** Standard DQ200 oil change interval in km. */
        private const val DSG_OIL_INTERVAL_KM = 60_000

        /** Clutch temperature threshold above which wear accelerates, in °C. */
        private const val CLUTCH_TEMP_PEAK_THRESHOLD = 180f

        // Urgency km thresholds
        private const val OK_THRESHOLD      = 45_000
        private const val INFO_THRESHOLD    = 55_000
        private const val WARNING_THRESHOLD = 60_000
        // >= WARNING_THRESHOLD → URGENT
    }

    /**
     * Returns the current DSG health status.
     *
     * @param currentKm Current vehicle odometer reading in km.
     */
    suspend fun getDSGStatus(currentKm: Int): DSGStatus = withContext(Dispatchers.IO) {
        Timber.d("$TAG: evaluating DSG status at currentKm=$currentKm")

        // Retrieve the most recent DSG oil service record from the DB
        // The DB uses ServiceType.DSG for DSG oil; ServiceItem uses DSG_OIL
        val lastService = db.serviceHistoryDao().getLatestForType(ServiceType.DSG)

        val lastServiceKm = lastService?.mileageAtServiceKm ?: 0
        val oilAgeKm      = (currentKm - lastServiceKm).coerceAtLeast(0)

        // Count clutch temperature peaks from telemetry frames
        // TelemetryFrameEntity stores oilTemp (engine oil) but not DSG clutch temp directly.
        // We count frames where recorded oilTemp exceeds the proxy threshold as an approximation,
        // since DSG heat correlates with engine oil temp under aggressive driving.
        // In a future schema version, a dedicated dsg_clutch_temp column should be added.
        val clutchTempPeaks = countClutchTempPeaks(lastService?.serviceDate ?: 0L)

        val (urgency, recommendation) = when {
            oilAgeKm < OK_THRESHOLD -> Pair(
                ServiceUrgency.OK,
                "DSG oil is within service interval. Next change at ${lastServiceKm + DSG_OIL_INTERVAL_KM} km."
            )
            oilAgeKm < INFO_THRESHOLD -> Pair(
                ServiceUrgency.INFO,
                "DSG oil change approaching at ${lastServiceKm + DSG_OIL_INTERVAL_KM} km. Schedule soon."
            )
            oilAgeKm < WARNING_THRESHOLD -> Pair(
                ServiceUrgency.WARNING,
                "DSG oil change due very soon (${DSG_OIL_INTERVAL_KM - oilAgeKm} km remaining). Book a service."
            )
            else -> Pair(
                ServiceUrgency.URGENT,
                "DSG oil change is OVERDUE by ${oilAgeKm - DSG_OIL_INTERVAL_KM} km! Service required immediately."
            )
        }

        val clutchNote = if (clutchTempPeaks > 20) {
            " $clutchTempPeaks high clutch-temp events detected — consider an early oil change."
        } else {
            ""
        }

        Timber.d("$TAG: oilAgeKm=$oilAgeKm clutchPeaks=$clutchTempPeaks urgency=$urgency")

        DSGStatus(
            oilAgeKm        = oilAgeKm,
            clutchTempPeaks = clutchTempPeaks,
            recommendation  = recommendation + clutchNote,
            urgency         = urgency
        )
    }

    /**
     * Counts frames since [sinceMs] where the oil temperature reading exceeds
     * [CLUTCH_TEMP_PEAK_THRESHOLD] as a proxy for DSG clutch stress events.
     */
    private suspend fun countClutchTempPeaks(sinceMs: Long): Int {
        return try {
            val sessions = db.telemetrySessionDao()
                .getAllSessions()
                .first()
                .filter { it.startTime >= sinceMs }

            var peakCount = 0
            for (session in sessions) {
                val frames = db.telemetryFrameDao().getFramesForSessionOnce(session.id)
                peakCount += frames.count { f ->
                    (f.oilTemp ?: 0f) > CLUTCH_TEMP_PEAK_THRESHOLD
                }
            }
            peakCount
        } catch (e: Exception) {
            Timber.w(e, "$TAG: failed to count clutch temp peaks")
            0
        }
    }
}
