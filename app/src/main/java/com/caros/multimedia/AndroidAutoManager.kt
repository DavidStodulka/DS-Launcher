package com.caros.multimedia

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAutoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("android_auto_prefs", Context.MODE_PRIVATE)

    private val _autoConnectEnabled = MutableStateFlow(prefs.getBoolean("auto_connect", true))
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun setAutoConnect(enabled: Boolean) {
        _autoConnectEnabled.value = enabled
        prefs.edit().putBoolean("auto_connect", enabled).apply()
    }

    fun onUsbDeviceAttached() {
        if (_autoConnectEnabled.value) {
            launchAndroidAuto()
        }
    }

    fun launchAndroidAuto() {
        try {
            val intent = context.packageManager
                .getLaunchIntentForPackage("com.google.android.projection.gearhead")
                ?: Intent("com.google.android.projection.gearhead.MAIN").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            _isConnected.value = true
        } catch (e: Exception) {
            Log.w("AndroidAutoManager", "Android Auto not available: ${e.message}")
        }
    }

    fun disconnect() {
        _isConnected.value = false
    }
}
