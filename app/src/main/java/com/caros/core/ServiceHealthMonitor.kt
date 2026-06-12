package com.caros.core

// ─────────────────────────────────────────────────────────────────────────────
//  ServiceHealthMonitor.kt — Central heartbeat registry for CarOS modules
//
//  Each long-running module (CANService, TelemetryService, audio engine, …)
//  periodically calls heartbeat(). WatchdogService polls checkStaleModules()
//  every 10 s and restarts any module whose heartbeat is older than 30 s and
//  that has a registered restart action.
//
//  The modules StateFlow drives the System Health dashboard UI.
// ─────────────────────────────────────────────────────────────────────────────

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Well-known module names used as heartbeat keys and dashboard labels. */
object HealthModules {
    const val CAN       = "CAN sběrnice"
    const val TELEMETRY = "Telemetrie"
    const val AUDIO     = "Audio engine"
    const val WATCHDOG  = "Watchdog"
}

data class ModuleHealth(
    val name: String,
    /** Wall-clock time of the most recent heartbeat. */
    val lastHeartbeatMs: Long,
    /** How many times the watchdog (or the user) restarted this module. */
    val restartCount: Int,
    /** True when a restart action is registered for this module. */
    val canRestart: Boolean
) {
    fun isAlive(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - lastHeartbeatMs < STALE_TIMEOUT_MS

    companion object {
        /** A module silent for longer than this is considered stale. */
        const val STALE_TIMEOUT_MS = 30_000L
    }
}

@Singleton
class ServiceHealthMonitor @Inject constructor() {

    private val lock = Any()
    private val restartActions = mutableMapOf<String, () -> Unit>()

    private val _modules = MutableStateFlow<Map<String, ModuleHealth>>(emptyMap())
    /** Live snapshot of all known modules — drives the health dashboard. */
    val modules: StateFlow<Map<String, ModuleHealth>> = _modules.asStateFlow()

    private val _watchdogRestartTotal = MutableStateFlow(0)
    /** Total number of automatic watchdog restarts this session. */
    val watchdogRestartTotal: StateFlow<Int> = _watchdogRestartTotal.asStateFlow()

    /** Record a liveness heartbeat for [name]. Creates the entry on first call. */
    fun heartbeat(name: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = _modules.value[name]
            _modules.value = _modules.value + (name to ModuleHealth(
                name            = name,
                lastHeartbeatMs = now,
                restartCount    = current?.restartCount ?: 0,
                canRestart      = restartActions.containsKey(name)
            ))
        }
    }

    /**
     * Register how to restart [name] when its heartbeat goes stale.
     * Typically `{ SomeService.start(applicationContext) }`.
     */
    fun registerRestartAction(name: String, action: () -> Unit) {
        synchronized(lock) {
            restartActions[name] = action
            _modules.value[name]?.let {
                _modules.value = _modules.value + (name to it.copy(canRestart = true))
            }
        }
    }

    /**
     * Remove the module from monitoring — call from Service.onDestroy() so an
     * intentional stop (user pressed Stop) is not "fixed" by the watchdog.
     */
    fun unregister(name: String) {
        synchronized(lock) {
            restartActions.remove(name)
            _modules.value = _modules.value - name
        }
    }

    /**
     * Restart a module by name (manual restart from the health dashboard).
     * @return true when a restart action existed and was invoked.
     */
    fun restartModule(name: String): Boolean {
        val action = synchronized(lock) { restartActions[name] } ?: return false
        bumpRestartCount(name)
        runCatching { action() }
            .onFailure { Timber.e(it, "ServiceHealthMonitor: restart of %s failed", name) }
        return true
    }

    /**
     * Restart every module whose heartbeat is older than [ModuleHealth.STALE_TIMEOUT_MS]
     * and that has a restart action. Called by WatchdogService every 10 s.
     *
     * @return names of the modules that were restarted.
     */
    fun checkStaleModules(): List<String> {
        val now = System.currentTimeMillis()
        val stale = synchronized(lock) {
            _modules.value.values.filter {
                !it.isAlive(now) && restartActions.containsKey(it.name)
            }.map { it.name }
        }
        stale.forEach { name ->
            Timber.w("ServiceHealthMonitor: module '%s' is stale — restarting", name)
            _watchdogRestartTotal.value += 1
            restartModule(name)
        }
        return stale
    }

    private fun bumpRestartCount(name: String) {
        synchronized(lock) {
            val current = _modules.value[name] ?: return
            // Refresh the heartbeat so the watchdog gives the module time to boot
            // instead of restarting it again on the next 10 s check.
            _modules.value = _modules.value + (name to current.copy(
                restartCount    = current.restartCount + 1,
                lastHeartbeatMs = System.currentTimeMillis()
            ))
        }
    }
}
