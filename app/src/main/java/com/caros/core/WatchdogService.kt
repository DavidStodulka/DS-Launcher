package com.caros.core

// ─────────────────────────────────────────────────────────────────────────────
//  WatchdogService.kt — Heartbeat supervisor for CarOS background services
//
//  Started from CarOSApplication.onCreate(). Every 10 s it asks
//  ServiceHealthMonitor for modules whose heartbeat is older than 30 s and
//  restarts them via their registered restart actions, without restarting
//  the whole app.
// ─────────────────────────────────────────────────────────────────────────────

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class WatchdogService : Service() {

    @Inject lateinit var healthMonitor: ServiceHealthMonitor

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Timber.e(t, "WatchdogService: uncaught exception in watch loop")
        }
    )

    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatchLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("WatchdogService: onDestroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startWatchLoop() {
        if (watchJob?.isActive == true) return
        Timber.i("WatchdogService: watch loop started (check every %d ms)", CHECK_INTERVAL_MS)
        watchJob = serviceScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                healthMonitor.heartbeat(HealthModules.WATCHDOG)
                try {
                    val restarted = healthMonitor.checkStaleModules()
                    if (restarted.isNotEmpty()) {
                        Timber.w("WatchdogService: restarted stale modules: %s", restarted)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "WatchdogService: stale check failed")
                }
            }
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 10_000L

        /** Convenience: start the watchdog from any Context. */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, WatchdogService::class.java))
            }.onFailure { Timber.w(it, "WatchdogService: start failed") }
        }
    }
}
