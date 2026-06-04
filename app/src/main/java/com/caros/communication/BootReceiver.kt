package com.caros.communication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.caros.can.CANService
import com.caros.telemetry.TelemetryService
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "caros_boot"
        private const val KEY_FIRST_BOOT = "first_boot_done"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.i("$TAG: boot completed — starting CarOS services")

        // Start CAN service as foreground
        try {
            context.startForegroundService(Intent(context, CANService::class.java))
            Timber.d("$TAG: CANService started")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to start CANService")
        }

        // Start Telemetry service as foreground
        try {
            context.startForegroundService(Intent(context, TelemetryService::class.java))
            Timber.d("$TAG: TelemetryService started")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to start TelemetryService")
        }

        // First boot setup
        val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstBootDone = prefs.getBoolean(KEY_FIRST_BOOT, false)
        if (!firstBootDone) {
            setupDefaultProfiles(context, prefs)
        }
    }

    private fun setupDefaultProfiles(context: Context, prefs: SharedPreferences) {
        try {
            // Write default settings
            val settingsPrefs = context.getSharedPreferences("caros_settings", Context.MODE_PRIVATE)
            settingsPrefs.edit()
                .putString("acc_power_mode", "deep_sleep")
                .putInt("shutdown_delay_sec", 30)
                .putBoolean("use_mock_can", false)
                .apply()

            // Mark first boot as complete
            prefs.edit().putBoolean(KEY_FIRST_BOOT, true).apply()
            Timber.i("$TAG: first-boot default profiles set up")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to set up default profiles")
        }
    }
}
