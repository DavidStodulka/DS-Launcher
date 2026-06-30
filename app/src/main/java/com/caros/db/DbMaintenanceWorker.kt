package com.caros.db

// ─────────────────────────────────────────────────────────────────────────────
//  DbMaintenanceWorker.kt — Periodic Room database maintenance
//
//  Telemetry frames accumulate at 2 Hz while driving (~170k rows / 24 h of
//  driving), so the database grows without bound unless pruned.  This worker
//  runs once a day and deletes telemetry data older than RETENTION_DAYS.
//
//  Scheduled from CarOSApplication.onCreate() via [schedule].
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class DbMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: CarOSDatabase
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L

            val framesDeleted = db.telemetryFrameDao().deleteOlderThan(cutoff)
            val sessionsDeleted = db.telemetrySessionDao().deleteOlderThan(cutoff)

            Timber.i(
                "DbMaintenanceWorker: pruned %d frames and %d sessions older than %d days",
                framesDeleted, sessionsDeleted, RETENTION_DAYS
            )
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DbMaintenanceWorker: maintenance failed")
            Result.retry()
        }
    }

    companion object {
        /** Telemetry data older than this many days is deleted. */
        private const val RETENTION_DAYS = 60L

        private const val WORK_NAME = "caros_db_maintenance"

        /** Enqueue the daily maintenance job (idempotent — KEEPs an existing schedule). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DbMaintenanceWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(15, TimeUnit.MINUTES)  // don't compete with boot-time startup
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.d("DbMaintenanceWorker: daily maintenance scheduled")
        }
    }
}
