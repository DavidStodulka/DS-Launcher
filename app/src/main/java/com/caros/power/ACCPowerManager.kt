package com.caros.power

// ─────────────────────────────────────────────────────────────────────────────
//  ACCPowerManager.kt — Manages device shutdown/sleep lifecycle tied to ACC state
//
//  When ACC turns off, starts a configurable countdown (default 60 s).
//  On countdown expiry, delegates to either DeepSleepManager or ShutdownManager.
//  When ACC turns back on, cancels any pending countdown and wakes from sleep.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ACCPowerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shutdownManager: ShutdownManager,
    private val deepSleepManager: DeepSleepManager
) {

    // ── State machine ─────────────────────────────────────────────────────────

    enum class State { ACTIVE, COUNTDOWN, SLEEPING, SHUTTING_DOWN }
    enum class ShutdownMode { DEEP_SLEEP, POWER_OFF }

    private val _currentState = MutableStateFlow(State.ACTIVE)
    val currentState: StateFlow<State> = _currentState.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(0)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var countdownJob: Job? = null

    // ── Preferences ───────────────────────────────────────────────────────────

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Seconds to wait after ACC-off before executing shutdown/sleep.
     * Range [0, 300]. Persisted to SharedPreferences.
     */
    var shutdownDelay: Int
        get() = prefs.getInt(KEY_SHUTDOWN_DELAY, DEFAULT_DELAY_SECONDS)
            .coerceIn(0, MAX_DELAY_SECONDS)
        set(value) {
            prefs.edit().putInt(KEY_SHUTDOWN_DELAY, value.coerceIn(0, MAX_DELAY_SECONDS)).apply()
        }

    /**
     * Whether to deep-sleep or fully power off on countdown expiry.
     * Persisted to SharedPreferences.
     */
    var shutdownMode: ShutdownMode
        get() = runCatching {
            ShutdownMode.valueOf(
                prefs.getString(KEY_SHUTDOWN_MODE, ShutdownMode.DEEP_SLEEP.name)!!
            )
        }.getOrDefault(ShutdownMode.DEEP_SLEEP)
        set(value) {
            prefs.edit().putString(KEY_SHUTDOWN_MODE, value.name).apply()
        }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when ACC turns off. Starts the countdown timer.
     * If delay == 0, transitions immediately to sleep/shutdown.
     */
    fun onACCOff() {
        if (_currentState.value == State.SLEEPING ||
            _currentState.value == State.SHUTTING_DOWN
        ) {
            Timber.d("ACCPowerManager: ACC off received but already in ${_currentState.value}")
            return
        }

        Timber.i("ACCPowerManager: ACC off — starting countdown (${shutdownDelay}s, mode=${shutdownMode})")
        startCountdown()
    }

    /**
     * Called when ACC turns on. Cancels any pending countdown and wakes from sleep.
     */
    fun onACCOn() {
        Timber.i("ACCPowerManager: ACC on")
        val wasAsleep = _currentState.value == State.SLEEPING

        cancelCountdownJob()
        _countdownSeconds.value = 0
        _currentState.value = State.ACTIVE

        if (wasAsleep) {
            scope.launch {
                Timber.i("ACCPowerManager: waking from deep sleep")
                deepSleepManager.wakeFromSleep()
            }
        }
    }

    /**
     * Aborts a pending countdown, returning state to ACTIVE.
     * No-op if not currently counting down.
     */
    fun cancelShutdown() {
        if (_currentState.value != State.COUNTDOWN) {
            Timber.d("ACCPowerManager: cancelShutdown() called but not in COUNTDOWN state")
            return
        }
        Timber.i("ACCPowerManager: countdown cancelled by user")
        cancelCountdownJob()
        _countdownSeconds.value = 0
        _currentState.value = State.ACTIVE
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun startCountdown() {
        cancelCountdownJob()

        val delaySeconds = shutdownDelay
        _countdownSeconds.value = delaySeconds
        _currentState.value = State.COUNTDOWN

        countdownJob = scope.launch {
            var remaining = delaySeconds

            if (remaining <= 0) {
                executeShutdownAction()
                return@launch
            }

            while (remaining > 0) {
                _countdownSeconds.value = remaining
                Timber.v("ACCPowerManager: countdown %ds remaining", remaining)
                delay(1_000L)
                remaining--
            }

            _countdownSeconds.value = 0
            executeShutdownAction()
        }
    }

    private suspend fun executeShutdownAction() {
        when (shutdownMode) {
            ShutdownMode.DEEP_SLEEP -> {
                Timber.i("ACCPowerManager: countdown expired — entering deep sleep")
                _currentState.value = State.SLEEPING
                deepSleepManager.enterDeepSleep()
            }
            ShutdownMode.POWER_OFF -> {
                Timber.i("ACCPowerManager: countdown expired — shutting down")
                _currentState.value = State.SHUTTING_DOWN
                shutdownManager.shutdown()
            }
        }
    }

    private fun cancelCountdownJob() {
        countdownJob?.cancel()
        countdownJob = null
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME            = "acc_power_manager"
        private const val KEY_SHUTDOWN_DELAY    = "acc_shutdown_delay"
        private const val KEY_SHUTDOWN_MODE     = "acc_shutdown_mode"
        private const val DEFAULT_DELAY_SECONDS = 60
        private const val MAX_DELAY_SECONDS     = 300
    }
}
