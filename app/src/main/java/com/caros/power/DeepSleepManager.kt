package com.caros.power

// ─────────────────────────────────────────────────────────────────────────────
//  DeepSleepManager.kt — Low-power parking mode for ACC-off state
//
//  Deep sleep sequence:
//    1. Dim screen to 0 via root Settings.System write
//    2. Set CPU governor to powersave
//    3. Disable WiFi
//    4. Acquire partial WakeLock for ACC CAN monitoring
//    5. Send screen-off keyevent via root input
//
//  Wake sequence reverses all the above and broadcasts a wake event.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.caros.core.ShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Broadcast sent when CarOS wakes from deep sleep — all services should resume. */
const val ACTION_WAKE_FROM_SLEEP = "com.caros.power.ACTION_WAKE_FROM_SLEEP"

@Singleton
class DeepSleepManager @Inject constructor(
    private val shellExecutor: ShellExecutor,
    @ApplicationContext private val context: Context
) {

    private val TAG = "DeepSleepManager"

    // ── State ─────────────────────────────────────────────────────────────────

    private val _isInDeepSleep = MutableStateFlow(false)

    /** `true` while the device is in deep (parking) sleep mode. */
    val isInDeepSleep: StateFlow<Boolean> = _isInDeepSleep.asStateFlow()

    // ── WakeLock management ───────────────────────────────────────────────────

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Partial WakeLock — keeps CPU alive for CAN/ACC monitoring while screen is off. */
    private val partialWakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CarOS:DeepSleepPartial"
        ).apply { setReferenceCounted(false) }
    }

    /** Full WakeLock — acquired on wake to light up the screen. */
    private val fullWakeLock: PowerManager.WakeLock by lazy {
        @Suppress("DEPRECATION")
        powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "CarOS:WakeFromSleep"
        ).apply { setReferenceCounted(false) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Puts the device into a low-power parking sleep state.
     * Screen is turned off, CPU throttled, WiFi disabled.
     * A partial WakeLock keeps the CPU running for ACC monitoring.
     */
    suspend fun enterDeepSleep() = withContext(Dispatchers.IO) {
        if (_isInDeepSleep.value) {
            Timber.d("$TAG: already in deep sleep, ignoring")
            return@withContext
        }
        Timber.i("$TAG: entering deep sleep")

        // Step 1: dim screen to 0 via root Settings.System write
        setScreenBrightness(0)

        // Step 2: switch CPU governor to powersave to reduce consumption
        setCpuGovernor("powersave")

        // Step 3: disable WiFi — saves ~200mW
        disableWifi()

        // Step 4: acquire partial WakeLock so CAN monitoring continues
        if (!partialWakeLock.isHeld) {
            partialWakeLock.acquire()
            Timber.d("$TAG: partial WakeLock acquired")
        }

        // Step 5: turn screen off
        turnScreenOff()

        _isInDeepSleep.value = true
        Timber.i("$TAG: deep sleep active")
    }

    /**
     * Wakes the device from deep sleep, restores CPU performance,
     * re-enables WiFi, and broadcasts [ACTION_WAKE_FROM_SLEEP].
     */
    suspend fun wakeFromSleep() = withContext(Dispatchers.IO) {
        if (!_isInDeepSleep.value) {
            Timber.d("$TAG: not in deep sleep, ignoring wake request")
            return@withContext
        }
        Timber.i("$TAG: waking from deep sleep")

        // Step 1: restore CPU governor to a performance governor
        restoreCpuGovernor()

        // Step 2: acquire full WakeLock to turn screen on
        if (!fullWakeLock.isHeld) {
            fullWakeLock.acquire(FULL_WAKE_LOCK_TIMEOUT_MS)
            Timber.d("$TAG: full WakeLock acquired — screen should be on")
        }

        // Step 3: restore screen brightness to system default
        setScreenBrightness(BRIGHTNESS_AUTO_RESTORE)

        // Step 4: re-enable WiFi
        enableWifi()

        // Step 5: release partial WakeLock — full WakeLock keeps things running
        if (partialWakeLock.isHeld) {
            partialWakeLock.release()
            Timber.d("$TAG: partial WakeLock released")
        }

        _isInDeepSleep.value = false

        // Step 6: broadcast wake event so all services resume
        broadcastWakeEvent()
        Timber.i("$TAG: wake from sleep complete")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun setScreenBrightness(level: Int) {
        // Try root Settings.System write first (works even with WRITE_SETTINGS denied)
        val cmd = "settings put system screen_brightness $level"
        val result = shellExecutor.executeSuCommand(cmd)
        if (result.isFailure) {
            Timber.w("$TAG: root brightness set failed, trying ContentResolver")
            try {
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    level.coerceIn(0, 255)
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: ContentResolver brightness set also failed")
            }
        } else {
            Timber.d("$TAG: screen brightness set to $level")
        }
    }

    private suspend fun setCpuGovernor(governor: String) {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val commands = (0 until cpuCount).map { cpu ->
            "echo $governor > /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor"
        }
        val result = shellExecutor.executeSuCommands(commands)
        if (result.isSuccess) {
            Timber.i("$TAG: CPU governor set to $governor for $cpuCount cores")
        } else {
            Timber.w("$TAG: failed to set CPU governor to $governor: ${result.exceptionOrNull()?.message}")
        }
    }

    private suspend fun restoreCpuGovernor() {
        // Try schedutil first (preferred on modern Android kernels), fall back to interactive
        for (governor in listOf("schedutil", "interactive", "ondemand")) {
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val commands = (0 until cpuCount).map { cpu ->
                "echo $governor > /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor"
            }
            val result = shellExecutor.executeSuCommands(commands)
            if (result.isSuccess) {
                Timber.i("$TAG: CPU governor restored to $governor")
                return
            }
        }
        Timber.w("$TAG: could not restore CPU governor — device will use current default")
    }

    @Suppress("DEPRECATION")
    private fun disableWifi() {
        try {
            if (wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = false
                Timber.d("$TAG: WiFi disabled")
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: could not disable WiFi")
        }
    }

    @Suppress("DEPRECATION")
    private fun enableWifi() {
        try {
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
                Timber.d("$TAG: WiFi enabled")
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: could not enable WiFi")
        }
    }

    private suspend fun turnScreenOff() {
        // Primary method: send KEYCODE_POWER (keyevent 26) via root input
        val result = shellExecutor.executeSuCommand("input keyevent 26")
        if (result.isFailure) {
            Timber.w("$TAG: root keyevent 26 failed, trying PowerManager.goToSleep()")
            try {
                @Suppress("DiscouragedPrivateApi")
                val m = android.os.PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
                m.invoke(powerManager, android.os.SystemClock.uptimeMillis())
            } catch (e: Exception) {
                Timber.e(e, "$TAG: PowerManager.goToSleep() also failed")
            }
        } else {
            Timber.d("$TAG: screen off keyevent sent")
        }
    }

    private fun broadcastWakeEvent() {
        val intent = Intent(ACTION_WAKE_FROM_SLEEP).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Timber.d("$TAG: wake broadcast sent")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** How long the full WakeLock is held after wake (5 minutes — auto-released). */
        private const val FULL_WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1_000L

        /**
         * Sentinel value — when screen brightness is restored after sleep,
         * use -1 to re-enable automatic brightness control, or set a fixed level.
         * Using 128 (50%) as a safe default.
         */
        private const val BRIGHTNESS_AUTO_RESTORE = 128
    }
}
