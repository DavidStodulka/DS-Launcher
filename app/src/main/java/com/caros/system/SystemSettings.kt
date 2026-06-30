package com.caros.system

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SystemSettings — thin wrapper for global system settings that require
 * WRITE_SETTINGS or root access on the CarOS device.
 */
@Singleton
class SystemSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "SystemSettings"
    }

    /**
     * Set the screen backlight level (0-255).
     * Requires WRITE_SETTINGS permission.
     */
    fun setBrightness(value: Int) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value.coerceIn(0, 255)
            )
            Timber.d("$TAG: brightness set to $value")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to set brightness")
        }
    }

    /**
     * Enable or disable ADB over TCP/IP on port 5555.
     * Requires root (executed via `adb shell` properties).
     */
    fun setADBWireless(enabled: Boolean) {
        val value = if (enabled) "5555" else "0"
        // su may block on the root-grant prompt — never wait for it on the caller's thread
        Thread {
            try {
                runSuCommand("setprop service.adb.tcp.port $value")
                runSuCommand("stop adbd && start adbd")
                Timber.i("$TAG: ADB wireless ${if (enabled) "enabled on :$value" else "disabled"}")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: failed to toggle ADB wireless")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun runSuCommand(command: String) {
        val proc = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        try {
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Timber.w("$TAG: su command timed out: $command")
                proc.destroyForcibly()
            }
        } finally {
            runCatching { proc.inputStream.close() }
            runCatching { proc.outputStream.close() }
            proc.destroy()
        }
    }

    /**
     * Return the device's current WiFi IP address, or null when WiFi is not connected.
     */
    fun getWifiIpAddress(): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null
            else "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: getWifiIpAddress failed")
            null
        }
    }

    /**
     * Return the current WiFi SSID, or null if not connected.
     */
    fun getWifiSsid(): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.connectionInfo.ssid?.removeSurrounding("\"")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read CPU temperature from thermal zone 0 (degrees Celsius).
     * Returns null if the thermal zone is unavailable.
     */
    fun getCpuTemperature(): Float? {
        return try {
            val temp = java.io.File("/sys/class/thermal/thermal_zone0/temp")
                .readText().trim().toFloat()
            temp / 1000f
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Return system uptime in seconds since last boot.
     */
    fun getUptimeSeconds(): Long = android.os.SystemClock.elapsedRealtime() / 1000L
}
