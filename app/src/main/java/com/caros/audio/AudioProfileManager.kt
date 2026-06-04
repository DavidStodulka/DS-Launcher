package com.caros.audio

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioProfileManager
 *
 * Single source of truth for the currently active [AudioProfile].
 * Persists the active profile and up to three custom profiles in
 * [SharedPreferences].  Delegates hardware application to [EQController]
 * and [ViperController].
 *
 * Observe [activeProfile] from any ViewModel or Fragment to react to
 * profile changes.
 */
@Singleton
class AudioProfileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val eqController: EQController,
    val viperController: ViperController
) {
    // -------------------------------------------------------------------------
    //  SharedPreferences
    // -------------------------------------------------------------------------

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // -------------------------------------------------------------------------
    //  State
    // -------------------------------------------------------------------------

    private val _activeProfile = MutableStateFlow(AudioProfile.FLAT)

    /** The currently active [AudioProfile]. Updated every time [applyProfile] is called. */
    val activeProfile: StateFlow<AudioProfile> = _activeProfile.asStateFlow()

    // -------------------------------------------------------------------------
    //  Profile access
    // -------------------------------------------------------------------------

    /**
     * Return the full list of available profiles: the five built-ins plus up to
     * three custom profiles loaded from persistent storage.
     */
    fun loadProfiles(): List<AudioProfile> {
        val customs = (1..3).mapNotNull { slot ->
            prefs.getString("custom_$slot", null)?.let { serialized ->
                try { json.decodeFromString<AudioProfile>(serialized) }
                catch (e: Exception) { Timber.w(e, "Failed to deserialise custom profile $slot"); null }
            }
        }
        return AudioProfile.ALL + customs
    }

    // -------------------------------------------------------------------------
    //  Applying profiles
    // -------------------------------------------------------------------------

    /**
     * Apply [profile] to the hardware audio effects and persist it as the
     * active profile so it survives app restarts.
     *
     * @param profile The [AudioProfile] to activate.
     */
    fun applyProfile(profile: AudioProfile) {
        eqController.applyProfile(profile)
        viperController.applyProfile(profile)
        _activeProfile.value = profile
        try {
            prefs.edit()
                .putString(KEY_ACTIVE_PROFILE, json.encodeToString(profile))
                .apply()
            Timber.d("Audio profile applied: %s", profile.name)
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist active profile")
        }
    }

    /**
     * Persist a custom profile to the slot determined by its [AudioProfileType]
     * (CUSTOM1 → slot 1, CUSTOM2 → slot 2, CUSTOM3 → slot 3).
     *
     * @param profile Must have type [AudioProfileType.CUSTOM1], [AudioProfileType.CUSTOM2],
     *                or [AudioProfileType.CUSTOM3].
     */
    fun saveCustomProfile(profile: AudioProfile) {
        val slot = when (profile.type) {
            AudioProfileType.CUSTOM1 -> 1
            AudioProfileType.CUSTOM2 -> 2
            AudioProfileType.CUSTOM3 -> 3
            else -> {
                Timber.w("saveCustomProfile called on non-custom profile type %s", profile.type)
                return
            }
        }
        try {
            prefs.edit().putString("custom_$slot", json.encodeToString(profile)).apply()
            Timber.d("Custom profile saved to slot %d: %s", slot, profile.name)
        } catch (e: Exception) {
            Timber.w(e, "Failed to save custom profile to slot %d", slot)
        }
    }

    /**
     * Re-apply the last active profile saved to [SharedPreferences].
     * Falls back to [AudioProfile.FLAT] if nothing was previously saved.
     * Typically called from Application.onCreate or a bound audio service.
     */
    fun restoreLastProfile() {
        val restored = prefs.getString(KEY_ACTIVE_PROFILE, null)?.let { serialized ->
            try { json.decodeFromString<AudioProfile>(serialized) }
            catch (e: Exception) { Timber.w(e, "Failed to restore last profile"); null }
        } ?: AudioProfile.FLAT
        Timber.d("Restoring audio profile: %s", restored.name)
        applyProfile(restored)
    }

    // -------------------------------------------------------------------------
    //  Convenience band-level shortcut
    // -------------------------------------------------------------------------

    /**
     * Adjust a single EQ band on the fly without changing the active profile
     * object.  Useful for real-time slider interaction in the UI.
     *
     * @param band Band index (0-based).
     * @param db   Gain in dB, typically in [-12, +12].
     */
    fun setLiveBandLevel(band: Int, db: Float) {
        eqController.setBandLevel(band, db)
    }

    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val PREFS_NAME = "audio_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
    }
}
