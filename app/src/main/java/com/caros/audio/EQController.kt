package com.caros.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EQController
 *
 * Wraps Android's [Equalizer], [BassBoost], and [Virtualizer] audio effects.
 * All effects target audioSessionId = 0 (global output mix).
 *
 * Call [initialize] once (e.g. from Application.onCreate or a bound service)
 * before using any other method. Always call [release] when done to free
 * native resources.
 */
@Singleton
class EQController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    // Priority 0 = lowest; audioSessionId 0 = global mix.
    private val PRIORITY = 0
    private val AUDIO_SESSION_ID = 0

    // -------------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------------

    /** Instantiate and enable the three audio effects. Safe to call multiple times. */
    fun initialize() {
        release() // clean up any previous instances first
        try {
            equalizer = Equalizer(PRIORITY, AUDIO_SESSION_ID).apply { enabled = true }
            Timber.d("EQ initialised: %d bands", equalizer?.numberOfBands)
        } catch (e: Exception) {
            Timber.w(e, "Equalizer not available")
        }
        try {
            bassBoost = BassBoost(PRIORITY, AUDIO_SESSION_ID).apply { enabled = false }
        } catch (e: Exception) {
            Timber.w(e, "BassBoost not available")
        }
        try {
            virtualizer = Virtualizer(PRIORITY, AUDIO_SESSION_ID).apply { enabled = false }
        } catch (e: Exception) {
            Timber.w(e, "Virtualizer not available")
        }
    }

    /** Release all native audio effect resources. */
    fun release() {
        try { equalizer?.release() } catch (e: Exception) { /* ignore */ }
        try { bassBoost?.release() } catch (e: Exception) { /* ignore */ }
        try { virtualizer?.release() } catch (e: Exception) { /* ignore */ }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }

    // -------------------------------------------------------------------------
    //  Profile application
    // -------------------------------------------------------------------------

    /**
     * Apply all settings from [profile] to the hardware effects in one call.
     * Silently ignores bands beyond [Equalizer.getNumberOfBands].
     */
    fun applyProfile(profile: AudioProfile) {
        applyEQBands(profile.eqBands)

        bassBoost?.apply {
            try {
                enabled = profile.bassBoostEnabled
                if (profile.bassBoostEnabled) {
                    setStrength(profile.bassBoostStrength.toShort())
                }
            } catch (e: Exception) {
                Timber.w(e, "BassBoost apply failed")
            }
        }

        virtualizer?.apply {
            try {
                enabled = profile.virtualizerEnabled
                if (profile.virtualizerEnabled) {
                    setStrength(profile.virtualizerStrength.toShort())
                }
            } catch (e: Exception) {
                Timber.w(e, "Virtualizer apply failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Band-level control
    // -------------------------------------------------------------------------

    /**
     * Set the gain for a single EQ band.
     *
     * @param band  Band index (0-based).
     * @param db    Gain in dB, typically in the range [-12, +12].
     */
    fun setBandLevel(band: Int, db: Float) {
        try {
            equalizer?.setBandLevel(band.toShort(), dbToMillibel(db))
        } catch (e: Exception) {
            Timber.w(e, "setBandLevel(%d, %.1f) failed", band, db)
        }
    }

    /**
     * Get the current gain for a single EQ band.
     *
     * @param band Band index (0-based).
     * @return Gain in dB, or 0.0 if the equalizer is unavailable.
     */
    fun getBandLevel(band: Int): Float = try {
        (equalizer?.getBandLevel(band.toShort()) ?: 0).toFloat() / 100f
    } catch (e: Exception) {
        0f
    }

    /** Number of bands supported by the hardware equalizer (default 10). */
    fun getNumBands(): Int = try {
        equalizer?.numberOfBands?.toInt() ?: DEFAULT_BANDS
    } catch (e: Exception) {
        DEFAULT_BANDS
    }

    /**
     * Centre frequency of [band] in millihertz (divide by 1000 for Hz).
     * Returns 0 if the equalizer is unavailable.
     */
    fun getBandFreqMilliHz(band: Int): Int = try {
        equalizer?.getCenterFreq(band.toShort()) ?: 0
    } catch (e: Exception) {
        0
    }

    /**
     * Centre frequency of [band] in Hz (float).
     * Returns 0.0 if the equalizer is unavailable.
     */
    fun getBandFreqHz(band: Int): Float = getBandFreqMilliHz(band) / 1000f

    // -------------------------------------------------------------------------
    //  Internal helpers
    // -------------------------------------------------------------------------

    private fun applyEQBands(bands: FloatArray) {
        val eq = equalizer ?: return
        val numBands = try { eq.numberOfBands.toInt() } catch (e: Exception) { return }
        for (i in 0 until minOf(bands.size, numBands)) {
            try {
                eq.setBandLevel(i.toShort(), dbToMillibel(bands[i]))
            } catch (e: Exception) {
                Timber.w(e, "EQ band %d set failed", i)
            }
        }
    }

    /** Android audio effects use millibels (1 dB = 100 mB). */
    private fun dbToMillibel(db: Float): Short = (db * 100f).toInt().toShort()

    companion object {
        private const val DEFAULT_BANDS = 10
    }
}
