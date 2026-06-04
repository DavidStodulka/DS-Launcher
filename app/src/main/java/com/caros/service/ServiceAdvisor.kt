package com.caros.service

// ─────────────────────────────────────────────────────────────────────────────
//  ServiceAdvisor.kt — Aggregates all maintenance advisories into a sorted list
//
//  Coordinates OilLifeCalculator, DPFMonitor, DSGMonitor, and fixed-interval
//  services (coolant, air filter, fuel filter, glow plugs, timing belt).
//  Fires a NotificationManager alert for any URGENT items.
// ─────────────────────────────────────────────────────────────────────────────

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.caros.core.CarOSApplication
import com.caros.db.CarOSDatabase
import com.caros.db.ServiceHistoryEntity
import com.caros.db.ServiceType as DbServiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class ServiceAdvisor @Inject constructor(
    private val db: CarOSDatabase,
    private val oilCalc: OilLifeCalculator,
    private val dpfMonitor: DPFMonitor,
    private val dsgMonitor: DSGMonitor,
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ServiceAdvisor"
        private const val NOTIFICATION_ID = 3001

        // Fixed service intervals
        private const val COOLANT_INTERVAL_YEARS   = 4
        private const val AIR_FILTER_INTERVAL_KM   = 30_000
        private const val FUEL_FILTER_INTERVAL_KM  = 50_000
        private const val GLOW_PLUGS_INTERVAL_KM   = 100_000
        private const val TIMING_BELT_INTERVAL_KM  = 120_000
        private const val TIMING_BELT_INTERVAL_YEARS = 5

        // Warning thresholds for km-based items (show WARNING when less than this remains)
        private const val KM_WARNING_THRESHOLD    = 5_000
        private const val KM_INFO_THRESHOLD       = 10_000

        // Warning threshold for date-based items (days)
        private const val DAYS_WARNING_THRESHOLD  = 60
        private const val DAYS_INFO_THRESHOLD     = 90
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a complete list of service items for the vehicle, sorted by urgency descending.
     * Items with [ServiceUrgency.URGENT] status trigger a system notification.
     *
     * @param currentKm Current vehicle odometer reading in km.
     */
    suspend fun getAllServiceItems(currentKm: Int): List<ServiceItem> =
        withContext(Dispatchers.IO) {
            Timber.d("$TAG: computing all service items at currentKm=$currentKm")

            val items = mutableListOf<ServiceItem>()

            // 1. Oil life
            items.add(buildOilItem(currentKm))

            // 2. DPF status
            items.add(buildDpfItem())

            // 3. DSG oil
            items.add(buildDsgItem(currentKm))

            // 4. Coolant (4-year interval)
            items.add(buildCoolantItem(currentKm))

            // 5. Air filter (30 000 km)
            items.add(buildKmIntervalItem(
                type           = ServiceType.AIR_FILTER,
                dbType         = DbServiceType.AIR_FILTER,
                intervalKm     = AIR_FILTER_INTERVAL_KM,
                currentKm      = currentKm,
                label          = "Air filter"
            ))

            // 6. Fuel filter (50 000 km)
            items.add(buildKmIntervalItem(
                type           = ServiceType.FUEL_FILTER,
                dbType         = DbServiceType.FUEL_FILTER,
                intervalKm     = FUEL_FILTER_INTERVAL_KM,
                currentKm      = currentKm,
                label          = "Fuel filter"
            ))

            // 7. Glow plugs (100 000 km)
            items.add(buildKmIntervalItem(
                type           = ServiceType.GLOW_PLUGS,
                dbType         = DbServiceType.GLOW_PLUGS,
                intervalKm     = GLOW_PLUGS_INTERVAL_KM,
                currentKm      = currentKm,
                label          = "Glow plugs"
            ))

            // 8. Timing belt (120 000 km / 5 years)
            items.add(buildTimingBeltItem(currentKm))

            val sorted = items.sortedByDescending { it.status.ordinal }

            // Fire notification for any URGENT items
            val urgentItems = sorted.filter { it.status == ServiceUrgency.URGENT }
            if (urgentItems.isNotEmpty()) {
                sendUrgentNotification(urgentItems)
            }

            sorted
        }

    /**
     * Records a completed service event to the database.
     *
     * @param type     The [ServiceType] of service performed.
     * @param mileage  Odometer reading at the time of service.
     * @param date     Unix epoch millis of service date. Defaults to now.
     */
    suspend fun recordServiceDone(
        type: ServiceType,
        mileage: Int,
        date: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val dbType = type.toDbServiceType()
        val (nextDueKm, nextDueDate) = nextDueForType(type, mileage, date)

        val entity = ServiceHistoryEntity(
            serviceType          = dbType,
            serviceDate          = date,
            mileageAtServiceKm   = mileage,
            nextDueKm            = nextDueKm,
            nextDueDate          = nextDueDate
        )
        db.serviceHistoryDao().insert(entity)
        Timber.i("$TAG: recorded service $type at ${mileage}km on $date")
    }

    /**
     * Counts URGENT items in the provided list.
     *
     * @param items List returned by [getAllServiceItems].
     * @return Count of items with [ServiceUrgency.URGENT] status.
     */
    fun getUrgentCount(items: List<ServiceItem>): Int =
        items.count { it.status == ServiceUrgency.URGENT }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private builders
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun buildOilItem(currentKm: Int): ServiceItem {
        val lastRecord = db.serviceHistoryDao().getLatestForType(DbServiceType.OIL)
        val lastKm   = lastRecord?.mileageAtServiceKm ?: 0
        val lastDate = lastRecord?.serviceDate ?: (System.currentTimeMillis() - 365L * 86_400_000L)

        val result = oilCalc.calculateOilLife(currentKm, lastKm, lastDate)
        val lifePct = (result.lifePct * 100).toInt()
        val dueInKm = max(0, result.recommendedChangeKm - currentKm)
        val dueInDays = ((result.recommendedChangeDate - System.currentTimeMillis()) / 86_400_000L)
            .toInt().coerceAtLeast(0)

        val status = when {
            lifePct <= 0  -> ServiceUrgency.URGENT
            lifePct <= 15 -> ServiceUrgency.WARNING
            lifePct <= 25 -> ServiceUrgency.INFO
            else          -> ServiceUrgency.OK
        }

        val reasoning = mutableListOf<String>()
        result.degradationFactors.forEach { (k, v) ->
            if (v > 0) reasoning.add("${k.replace('_', ' ')}: -%.0f km".format(v))
        }
        if (lastRecord == null) reasoning.add("No oil change record found — using estimated date")

        return ServiceItem(
            type              = ServiceType.OIL,
            status            = status,
            currentValueStr   = "Oil life: $lifePct% (${dueInKm} km remaining)",
            recommendation    = if (status == ServiceUrgency.URGENT)
                "Change engine oil immediately!"
            else
                "Next oil change in ${dueInKm} km / ${dueInDays} days.",
            dueInKm           = dueInKm,
            dueInDays         = dueInDays,
            dataReasoning     = reasoning
        )
    }

    private suspend fun buildDpfItem(): ServiceItem {
        val status = dpfMonitor.getDPFStatus()
        val urgency = when (status.recommendation) {
            DPFRecommendation.OK                       -> ServiceUrgency.OK
            DPFRecommendation.HIGHWAY_DRIVE_RECOMMENDED -> ServiceUrgency.INFO
            DPFRecommendation.URGENT_REGEN             -> ServiceUrgency.WARNING
            DPFRecommendation.SERVICE_REQUIRED         -> ServiceUrgency.URGENT
        }

        val lastRegenStr = status.lastRegenTime
            ?.let { java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) }
            ?: "None detected"

        return ServiceItem(
            type            = ServiceType.DPF,
            status          = urgency,
            currentValueStr = "DPF load: %.0f%% | trend: %+.1f%%/hr".format(status.currentLoad, status.trend),
            recommendation  = when (status.recommendation) {
                DPFRecommendation.OK                        -> "DPF soot load is normal."
                DPFRecommendation.HIGHWAY_DRIVE_RECOMMENDED -> "Take a 20+ min motorway drive at 2500+ RPM to trigger passive regen."
                DPFRecommendation.URGENT_REGEN              -> "DPF regen in progress or imminent — avoid switching off engine."
                DPFRecommendation.SERVICE_REQUIRED          -> "DPF may be blocked. Professional cleaning required."
            },
            dueInKm   = null,
            dueInDays = null,
            dataReasoning = listOf(
                "Current load: %.0f%%".format(status.currentLoad),
                "Trend: %+.1f%% per hour".format(status.trend),
                "Last regeneration: $lastRegenStr"
            )
        )
    }

    private suspend fun buildDsgItem(currentKm: Int): ServiceItem {
        val dsg = dsgMonitor.getDSGStatus(currentKm)
        val lastRecord = db.serviceHistoryDao().getLatestForType(DbServiceType.DSG)
        val lastKm = lastRecord?.mileageAtServiceKm ?: 0
        val dueInKm = max(0, 60_000 - dsg.oilAgeKm)

        val reasoning = mutableListOf(
            "Oil age: ${dsg.oilAgeKm} km since last DSG service",
            "Last service at: ${lastKm} km"
        )
        if (dsg.clutchTempPeaks > 0) {
            reasoning.add("${dsg.clutchTempPeaks} high clutch-temperature events recorded")
        }

        return ServiceItem(
            type            = ServiceType.DSG_OIL,
            status          = dsg.urgency,
            currentValueStr = "DSG oil age: ${dsg.oilAgeKm} km",
            recommendation  = dsg.recommendation,
            dueInKm         = dueInKm,
            dueInDays       = null,
            dataReasoning   = reasoning
        )
    }

    private suspend fun buildCoolantItem(currentKm: Int): ServiceItem {
        val lastRecord = db.serviceHistoryDao().getLatestForType(DbServiceType.COOLANT)
        val lastDate   = lastRecord?.serviceDate ?: (System.currentTimeMillis() - 4L * 365 * 86_400_000L)
        val yearMs     = 365L * 24 * 3600 * 1000
        val ageMs      = System.currentTimeMillis() - lastDate
        val ageYears   = ageMs.toFloat() / yearMs
        val dueMs      = lastDate + COOLANT_INTERVAL_YEARS * yearMs
        val dueInDays  = ((dueMs - System.currentTimeMillis()) / 86_400_000L).toInt()
        val dueInKm    = lastRecord?.nextDueKm?.let { max(0, it - currentKm) }

        val status = when {
            dueInDays <= 0  -> ServiceUrgency.URGENT
            dueInDays <= DAYS_WARNING_THRESHOLD -> ServiceUrgency.WARNING
            dueInDays <= DAYS_INFO_THRESHOLD    -> ServiceUrgency.INFO
            else            -> ServiceUrgency.OK
        }

        val reasoning = mutableListOf(
            "Coolant age: %.1f years (interval: $COOLANT_INTERVAL_YEARS years)".format(ageYears)
        )
        if (lastRecord == null) reasoning.add("No coolant service record found")

        return ServiceItem(
            type            = ServiceType.COOLANT,
            status          = status,
            currentValueStr = "Coolant age: %.1f yr".format(ageYears),
            recommendation  = if (status == ServiceUrgency.URGENT)
                "Coolant change overdue! Service immediately."
            else
                "Coolant change due in $dueInDays days.",
            dueInKm         = dueInKm,
            dueInDays       = dueInDays.coerceAtLeast(0),
            dataReasoning   = reasoning
        )
    }

    private suspend fun buildKmIntervalItem(
        type: ServiceType,
        dbType: DbServiceType,
        intervalKm: Int,
        currentKm: Int,
        label: String
    ): ServiceItem {
        val lastRecord = db.serviceHistoryDao().getLatestForType(dbType)
        val lastKm     = lastRecord?.mileageAtServiceKm ?: 0
        val ageKm      = (currentKm - lastKm).coerceAtLeast(0)
        val dueInKm    = max(0, intervalKm - ageKm)

        val status = when {
            dueInKm <= 0                    -> ServiceUrgency.URGENT
            dueInKm <= KM_WARNING_THRESHOLD -> ServiceUrgency.WARNING
            dueInKm <= KM_INFO_THRESHOLD    -> ServiceUrgency.INFO
            else                            -> ServiceUrgency.OK
        }

        val reasoning = listOf(
            "$label age: ${ageKm} km (interval: $intervalKm km)",
            "Last service at: $lastKm km"
        )

        return ServiceItem(
            type            = type,
            status          = status,
            currentValueStr = "$label: ${ageKm} km old",
            recommendation  = if (status == ServiceUrgency.URGENT)
                "$label change overdue! Service immediately."
            else
                "$label replacement in $dueInKm km.",
            dueInKm         = dueInKm,
            dueInDays       = null,
            dataReasoning   = reasoning
        )
    }

    private suspend fun buildTimingBeltItem(currentKm: Int): ServiceItem {
        val lastRecord = db.serviceHistoryDao().getLatestForType(DbServiceType.TIMING_BELT)
        val lastKm     = lastRecord?.mileageAtServiceKm ?: 0
        val lastDate   = lastRecord?.serviceDate ?: (System.currentTimeMillis() - 5L * 365 * 86_400_000L)

        val ageKm   = (currentKm - lastKm).coerceAtLeast(0)
        val dueInKm = max(0, TIMING_BELT_INTERVAL_KM - ageKm)

        val yearMs     = 365L * 24 * 3600 * 1000
        val dueDate    = lastDate + TIMING_BELT_INTERVAL_YEARS * yearMs
        val dueInDays  = ((dueDate - System.currentTimeMillis()) / 86_400_000L).toInt()

        // Timing belt failure is catastrophic — use strict thresholds
        val statusByKm = when {
            dueInKm <= 0                        -> ServiceUrgency.URGENT
            dueInKm <= KM_WARNING_THRESHOLD     -> ServiceUrgency.URGENT  // no grace on timing belt
            dueInKm <= KM_INFO_THRESHOLD * 2    -> ServiceUrgency.WARNING
            else                                -> ServiceUrgency.OK
        }
        val statusByDate = when {
            dueInDays <= 0                          -> ServiceUrgency.URGENT
            dueInDays <= DAYS_WARNING_THRESHOLD     -> ServiceUrgency.WARNING
            dueInDays <= DAYS_INFO_THRESHOLD        -> ServiceUrgency.INFO
            else                                    -> ServiceUrgency.OK
        }
        // Use the worse of the two
        val status = if (statusByKm.ordinal > statusByDate.ordinal) statusByKm else statusByDate

        val reasoning = listOf(
            "Timing belt age: $ageKm km (interval: $TIMING_BELT_INTERVAL_KM km)",
            "Time since replacement: ${((System.currentTimeMillis() - lastDate) / yearMs).toInt()} yr " +
                    "(interval: $TIMING_BELT_INTERVAL_YEARS yr)",
            if (lastRecord == null) "No timing belt service record found — using estimated date" else
                "Last replacement at: $lastKm km"
        )

        return ServiceItem(
            type            = ServiceType.TIMING_BELT,
            status          = status,
            currentValueStr = "Timing belt: ${ageKm} km / ${
                ((System.currentTimeMillis() - lastDate) / yearMs).toInt()
            }yr",
            recommendation  = if (status == ServiceUrgency.URGENT)
                "CRITICAL: Timing belt replacement overdue! Engine damage risk — service immediately."
            else
                "Timing belt replacement in $dueInKm km / $dueInDays days.",
            dueInKm         = dueInKm,
            dueInDays       = dueInDays.coerceAtLeast(0),
            dataReasoning   = reasoning
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendUrgentNotification(urgentItems: List<ServiceItem>) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "CarOS: ${urgentItems.size} Service Item${if (urgentItems.size > 1) "s" else ""} URGENT"
            val body  = urgentItems.joinToString("\n") { "• ${it.type.name}: ${it.recommendation}" }

            val notification = NotificationCompat.Builder(context, CarOSApplication.CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(urgentItems.first().recommendation)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
            Timber.i("$TAG: urgent service notification sent for ${urgentItems.size} items")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to send urgent notification")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun nextDueForType(type: ServiceType, lastKm: Int, lastDate: Long): Pair<Int?, Long?> {
        val yearMs = 365L * 24 * 3600 * 1000
        return when (type) {
            ServiceType.OIL         -> Pair(lastKm + 15_000, lastDate + yearMs)
            ServiceType.DPF         -> Pair(null, null)
            ServiceType.DSG_OIL     -> Pair(lastKm + 60_000, lastDate + 6 * yearMs)
            ServiceType.COOLANT     -> Pair(null, lastDate + COOLANT_INTERVAL_YEARS * yearMs)
            ServiceType.AIR_FILTER  -> Pair(lastKm + AIR_FILTER_INTERVAL_KM, null)
            ServiceType.FUEL_FILTER -> Pair(lastKm + FUEL_FILTER_INTERVAL_KM, null)
            ServiceType.GLOW_PLUGS  -> Pair(lastKm + GLOW_PLUGS_INTERVAL_KM, null)
            ServiceType.TIMING_BELT -> Pair(lastKm + TIMING_BELT_INTERVAL_KM, lastDate + TIMING_BELT_INTERVAL_YEARS * yearMs)
        }
    }

    /** Maps the domain-layer [ServiceType] to the DB [DbServiceType] enum. */
    private fun ServiceType.toDbServiceType(): DbServiceType = when (this) {
        ServiceType.OIL          -> DbServiceType.OIL
        ServiceType.DPF          -> DbServiceType.DPF
        ServiceType.DSG_OIL      -> DbServiceType.DSG
        ServiceType.COOLANT      -> DbServiceType.COOLANT
        ServiceType.AIR_FILTER   -> DbServiceType.AIR_FILTER
        ServiceType.FUEL_FILTER  -> DbServiceType.FUEL_FILTER
        ServiceType.GLOW_PLUGS   -> DbServiceType.GLOW_PLUGS
        ServiceType.TIMING_BELT  -> DbServiceType.TIMING_BELT
    }
}
