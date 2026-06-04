package com.caros.system

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import com.caros.core.ShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SystemSettingsManager
 *
 * Centralised access to Android system settings (brightness, screen timeout,
 * Wi-Fi) and low-level device information (CPU usage, CPU temperature, RAM).
 *
 * ADB-over-Wi-Fi toggling and SELinux state changes require root; they are
 * delegated to [ShellExecutor].
 *
 * **Required permissions:**
 *  - `WRITE_SETTINGS` — brightness / screen-timeout writes
 *  - `CHANGE_WIFI_STATE` / `ACCESS_WIFI_STATE` — Wi-Fi toggle
 *  - Root (`su`) — ADB wireless, SELinux permissive
 */
@Singleton
class SystemSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellExecutor: ShellExecutor
) {
    // -------------------------------------------------------------------------
    //  Display brightness
    // -------------------------------------------------------------------------

    /**
     * Set screen brightness.
     *
     * @param level Brightness level in [0, 255].
     */
    fun setBrightness(level: Int) {
        val clamped = level.coerceIn(0, 255)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped
            )
            Timber.d("Brightness set to %d", clamped)
        } catch (e: SecurityException) {
            Timber.w(e, "WRITE_SETTINGS permission required for brightness")
        } catch (e: Exception) {
            Timber.e(e, "setBrightness failed")
        }
    }

    /**
     * Get the current screen brightness.
     *
     * @return Brightness level [0, 255]; 128 as fallback if not readable.
     */
    fun getBrightness(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    } catch (e: Exception) {
        128
    }

    /**
     * Enable or disable automatic brightness adjustment.
     *
     * @param enabled `true` for automatic mode; `false` for manual.
     */
    fun setAutoBrightness(enabled: Boolean) {
        val mode = if (enabled)
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        else
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode
            )
        } catch (e: Exception) {
            Timber.w(e, "setAutoBrightness(%b) failed", enabled)
        }
    }

    // -------------------------------------------------------------------------
    //  Screen timeout
    // -------------------------------------------------------------------------

    /**
     * Set the screen-off / sleep timeout.
     *
     * @param ms Timeout in milliseconds. Use [Int.MAX_VALUE] to keep screen on.
     */
    fun setScreenTimeout(ms: Int) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                ms.coerceAtLeast(0)
            )
        } catch (e: Exception) {
            Timber.w(e, "setScreenTimeout(%d) failed", ms)
        }
    }

    /** Get the current screen-off timeout in milliseconds. */
    fun getScreenTimeout(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
    } catch (e: Exception) {
        30_000
    }

    // -------------------------------------------------------------------------
    //  Wi-Fi
    // -------------------------------------------------------------------------

    /**
     * Enable or disable Wi-Fi.
     *
     * Note: [WifiManager.setWifiEnabled] is deprecated in API 29 and no longer
     * callable by third-party apps.  On Android 10+ this silently does nothing
     * unless the app is a system app or a Device Owner.
     *
     * @param enabled `true` to enable Wi-Fi; `false` to disable.
     */
    @Suppress("DEPRECATION")
    suspend fun setWifiEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.isWifiEnabled = enabled
            Timber.d("Wi-Fi %s", if (enabled) "enabled" else "disabled")
        } catch (e: Exception) {
            Timber.w(e, "setWifiEnabled(%b) failed (expected on API 29+)", enabled)
        }
    }

    // -------------------------------------------------------------------------
    //  ADB over Wi-Fi
    // -------------------------------------------------------------------------

    /**
     * Toggle ADB-over-TCP on [port] (default 5555) via the system properties.
     * Requires root.
     *
     * @param enabled `true` to activate wireless ADB; `false` to disable.
     * @param port    TCP port to listen on when enabling. Ignored when disabling.
     * @return Human-readable status string with the `adb connect` command if successful.
     */
    suspend fun setADBWireless(enabled: Boolean, port: Int = 5555): String =
        withContext(Dispatchers.IO) {
            return@withContext if (enabled) {
                shellExecutor.executeSuCommands(
                    listOf(
                        "setprop service.adb.tcp.port $port",
                        "stop adbd",
                        "start adbd"
                    )
                )
                val ip = getWifiIP()
                if (ip != null) "ADB wireless on — connect with: adb connect $ip:$port"
                else "ADB wireless enabled but device IP could not be determined (is Wi-Fi connected?)"
            } else {
                shellExecutor.executeSuCommands(
                    listOf(
                        "setprop service.adb.tcp.port -1",
                        "stop adbd",
                        "start adbd"
                    )
                )
                "ADB wireless disabled"
            }
        }

    /**
     * Return the device's current Wi-Fi IPv4 address in dotted-decimal notation.
     *
     * @return IP string such as "192.168.1.42", or `null` if Wi-Fi is disconnected.
     */
    fun getWifiIP(): String? {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    // -------------------------------------------------------------------------
    //  SELinux
    // -------------------------------------------------------------------------

    /**
     * Query the current SELinux enforcement mode.
     *
     * @return `"Enforcing"`, `"Permissive"`, or `"Unknown"` if the command fails.
     */
    suspend fun getSELinuxStatus(): String =
        shellExecutor.executeSuCommand("getenforce").getOrDefault("Unknown").trim()

    /**
     * Set SELinux to permissive mode. Requires root.
     * This change does not survive a reboot.
     */
    suspend fun setSELinuxPermissive(): Result<String> =
        shellExecutor.executeSuCommand("setenforce 0")

    /**
     * Set SELinux to enforcing mode. Requires root.
     */
    suspend fun setSELinuxEnforcing(): Result<String> =
        shellExecutor.executeSuCommand("setenforce 1")

    // -------------------------------------------------------------------------
    //  CPU & memory diagnostics
    // -------------------------------------------------------------------------

    /**
     * Read overall CPU utilisation from `/proc/stat`.
     *
     * @return CPU usage in percent [0, 100], or 0 on any read error.
     */
    suspend fun getCPUUsage(): Float = withContext(Dispatchers.IO) {
        try {
            val line = java.io.File("/proc/stat").bufferedReader().readLine() ?: return@withContext 0f
            // Format: "cpu  user nice system idle iowait irq softirq steal guest guest_nice"
            val tokens = line.trimStart().split(Regex("\\s+")).drop(1)
            val values = tokens.mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return@withContext 0f
            val idle = values[3]
            val total = values.sum()
            if (total == 0L) return@withContext 0f
            100f * (1f - idle.toFloat() / total.toFloat())
        } catch (e: Exception) {
            Timber.w(e, "getCPUUsage failed")
            0f
        }
    }

    /**
     * Read the CPU (SoC) temperature from a thermal zone sysfs node.
     *
     * @return Temperature in °C, or 0.0 if no thermal zone file is readable.
     */
    suspend fun getCPUTemp(): Float = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/kernel/debug/tegra_thermal/temp_tj"
        )
        candidates.firstNotNullOfOrNull { path ->
            try {
                val raw = java.io.File(path).readText().trim().toLong()
                // Kernel reports millidegrees on most SoCs; values > 1000 need dividing
                if (raw > 1000L) raw.toFloat() / 1000f else raw.toFloat()
            } catch (e: Exception) {
                null
            }
        } ?: 0f
    }

    /**
     * Query system RAM from [ActivityManager].
     *
     * @return Pair of (availableBytes, totalBytes).
     */
    fun getRAMInfo(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Pair(info.availMem, info.totalMem)
    }

    /**
     * Check whether the system is currently under memory pressure (low memory).
     */
    fun isLowMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
    }
}
