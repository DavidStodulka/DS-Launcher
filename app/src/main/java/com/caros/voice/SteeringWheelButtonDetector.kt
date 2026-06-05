package com.caros.voice

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SteeringWheelButtonDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, "caros_voice_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { context.getSharedPreferences("caros_voice_prefs", Context.MODE_PRIVATE) }
    }

    private val _calibrationEvents = Channel<KeyEvent>(Channel.BUFFERED)
    val calibrationEvents: Flow<KeyEvent> = _calibrationEvents.receiveAsFlow()

    var isCalibrating = false
        private set

    private var savedKeyCode: Int
        get() = prefs.getInt("voice_keycode", -1)
        set(v) { prefs.edit().putInt("voice_keycode", v).apply() }

    val isConfigured: Boolean get() = savedKeyCode != -1

    fun isVoiceButtonEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (savedKeyCode != -1 && event.keyCode == savedKeyCode) return true
        // Common steering wheel voice keycodes
        return event.keyCode in listOf(
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_SEARCH, 286, 287, 288
        )
    }

    fun startCalibration(onDetected: (Int) -> Unit) {
        isCalibrating = true
        Timber.d("Voice button calibration started")
        // MainActivity will route KeyEvents to onKeyEvent() during calibration
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isCalibrating || event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_HOME) return false
        savedKeyCode = event.keyCode
        isCalibrating = false
        Timber.i("Voice button calibrated: keyCode=${event.keyCode}")
        _calibrationEvents.trySend(event)
        return true
    }

    fun clearCalibration() { savedKeyCode = -1 }
    fun getSavedKeyCode(): Int = savedKeyCode
}
