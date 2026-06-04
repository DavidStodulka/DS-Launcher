package com.caros.multimedia

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FMController
 *
 * Manages FM tuner state: frequency, play/stop, presets, and RDS text.
 *
 * On head-unit ROMs that expose an FM service, hardware commands are sent via
 * broadcast intents.  On hardware without a native FM service the state is
 * tracked in memory only, and the broadcasts are simply ignored by the OS.
 *
 * FM frequency range: 87.5 – 108.0 MHz in 0.1 MHz steps.
 *
 * Presets are persisted in [SharedPreferences] and survive app restarts.
 */
@Singleton
class FMController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    companion object {
        const val FREQ_MIN = 87.5f
        const val FREQ_MAX = 108.0f
        const val FREQ_STEP = 0.1f
        const val PRESET_COUNT = 6
        private const val PREFS_NAME = "fm_controller"
        private const val KEY_FREQUENCY = "frequency"
        private const val KEY_PRESET_PREFIX = "preset_"
    }

    // -------------------------------------------------------------------------
    //  Persistence
    // -------------------------------------------------------------------------

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    //  Observable state
    // -------------------------------------------------------------------------

    private val _currentFreq = MutableStateFlow(
        prefs.getFloat(KEY_FREQUENCY, 98.0f).coerceIn(FREQ_MIN, FREQ_MAX)
    )

    /** Currently tuned frequency in MHz (87.5 – 108.0). */
    val currentFreq: StateFlow<Float> = _currentFreq.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)

    /** Whether the FM tuner is actively playing. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _rdsText = MutableStateFlow("")

    /** Most recent RDS RadioText string (PS name or RT, depending on station). */
    val rdsText: StateFlow<String> = _rdsText.asStateFlow()

    // -------------------------------------------------------------------------
    //  Presets (in-memory + persisted)
    // -------------------------------------------------------------------------

    private val presets: FloatArray = FloatArray(PRESET_COUNT) { idx ->
        val default = when (idx) { 0 -> 89.0f; 1 -> 91.5f; 2 -> 94.0f; 3 -> 98.0f; 4 -> 100.3f; else -> 102.7f }
        prefs.getFloat("$KEY_PRESET_PREFIX$idx", default)
    }

    // -------------------------------------------------------------------------
    //  Playback control
    // -------------------------------------------------------------------------

    /** Start FM playback at the current frequency. */
    fun play() {
        _isPlaying.value = true
        sendFMBroadcast("PLAY", _currentFreq.value)
        Timber.d("FM play at %.1f MHz", _currentFreq.value)
    }

    /** Stop FM playback. */
    fun stop() {
        _isPlaying.value = false
        sendFMBroadcast("STOP", _currentFreq.value)
        Timber.d("FM stopped")
    }

    // -------------------------------------------------------------------------
    //  Tuning
    // -------------------------------------------------------------------------

    /**
     * Tune the FM tuner to [freqMhz].
     * The value is clamped to [FREQ_MIN]..[FREQ_MAX] and rounded to one decimal place.
     *
     * @param freqMhz Target frequency in MHz.
     */
    fun tuneToFreq(freqMhz: Float) {
        val clamped = freqMhz.coerceIn(FREQ_MIN, FREQ_MAX)
        val rounded = (Math.round(clamped * 10) / 10f)
        _currentFreq.value = rounded
        _rdsText.value = "" // clear stale RDS on channel change
        sendFMBroadcast("TUNE", rounded)
        prefs.edit().putFloat(KEY_FREQUENCY, rounded).apply()
        Timber.d("FM tuned to %.1f MHz", rounded)
    }

    /** Step frequency up by [FREQ_STEP] MHz. Wraps around at [FREQ_MAX]. */
    fun stepUp() {
        val next = _currentFreq.value + FREQ_STEP
        tuneToFreq(if (next > FREQ_MAX) FREQ_MIN else next)
    }

    /** Step frequency down by [FREQ_STEP] MHz. Wraps around at [FREQ_MIN]. */
    fun stepDown() {
        val next = _currentFreq.value - FREQ_STEP
        tuneToFreq(if (next < FREQ_MIN) FREQ_MAX else next)
    }

    /** Initiate auto-scan upward; the hardware or FM service finds the next valid station. */
    fun scanUp() {
        sendFMBroadcast("SCAN_UP", _currentFreq.value)
    }

    /** Initiate auto-scan downward. */
    fun scanDown() {
        sendFMBroadcast("SCAN_DOWN", _currentFreq.value)
    }

    // -------------------------------------------------------------------------
    //  Presets
    // -------------------------------------------------------------------------

    /**
     * Return a copy of the preset frequency array.
     *
     * @return [FloatArray] of length [PRESET_COUNT] with frequencies in MHz.
     */
    fun getPresets(): List<Float> = presets.toList()

    /**
     * Tune to the preset at [index].
     *
     * @param index Preset slot (0-based, 0..[PRESET_COUNT]-1).
     */
    fun recallPreset(index: Int) {
        if (index !in 0 until PRESET_COUNT) return
        tuneToFreq(presets[index])
    }

    /**
     * Save the current frequency to preset slot [index].
     *
     * @param index Preset slot (0-based, 0..[PRESET_COUNT]-1).
     */
    fun savePreset(index: Int) {
        if (index !in 0 until PRESET_COUNT) return
        presets[index] = _currentFreq.value
        prefs.edit().putFloat("$KEY_PRESET_PREFIX$index", _currentFreq.value).apply()
        Timber.d("FM preset %d saved: %.1f MHz", index, _currentFreq.value)
    }

    // -------------------------------------------------------------------------
    //  RDS
    // -------------------------------------------------------------------------

    /**
     * Update the RDS text displayed to the user.  Typically called by the
     * FM hardware service or a broadcast receiver when a new RDS frame arrives.
     *
     * @param text RDS PS or RT string.
     */
    fun updateRdsText(text: String) {
        _rdsText.value = text
    }

    // -------------------------------------------------------------------------
    //  Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Send a broadcast intent to the platform FM service.
     * The frequency is encoded as an integer in kHz (freq * 1000).
     *
     * @param action  Action suffix appended to the FM intent action prefix.
     * @param freqMhz Frequency in MHz.
     */
    private fun sendFMBroadcast(action: String, freqMhz: Float) {
        try {
            val intent = Intent("android.intent.action.FM_$action").apply {
                putExtra("frequency", (freqMhz * 1000).toInt())
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Timber.w(e, "FM broadcast failed: %s", action)
        }
    }
}
