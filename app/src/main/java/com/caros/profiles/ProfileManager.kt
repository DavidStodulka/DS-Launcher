package com.caros.profiles

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import com.caros.audio.AudioProfileManager
import com.caros.system.SystemSettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Supporting types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * High-level vehicle operating mode derived from speed (and optionally gear /
 * ACC state).
 *
 * [DRIVING] — vehicle is in motion; safety restrictions apply.
 * [PARKED]  — vehicle is stationary; full UI access allowed.
 */
enum class DrivingMode {
    /** Engine off or vehicle stationary — full UI available. */
    PARKED,
    /** Vehicle moving — simplified driving-safe UI. */
    DRIVING
}

// ─────────────────────────────────────────────────────────────────────────────
//  ProfileManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ProfileManager
 *
 * Orchestrates [DriveProfile] activation: applies display brightness, media
 * volume, and the associated [com.caros.audio.AudioProfile] in a single call.
 *
 * Also tracks the current [DrivingMode] based on vehicle speed delivered from
 * the CAN layer; observe [drivingMode] from the UI to enable/disable controls
 * that should be locked while moving.
 *
 * The last active profile name is persisted in [SharedPreferences] so it
 * survives app restarts; call [restoreLastProfile] from Application.onCreate.
 */
@Singleton
class ProfileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioProfileManager: AudioProfileManager,
    private val systemSettings: SystemSettingsManager
) {
    // -------------------------------------------------------------------------
    //  Persistence
    // -------------------------------------------------------------------------

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    //  Observable state
    // -------------------------------------------------------------------------

    private val _activeProfile = MutableStateFlow(DriveProfile.DAILY)

    /** The currently active [DriveProfile]. Changes whenever [applyProfile] is called. */
    val activeProfile: StateFlow<DriveProfile> = _activeProfile.asStateFlow()

    private val _drivingMode = MutableStateFlow(
        if (prefs.getString(KEY_MODE, null) == DrivingMode.DRIVING.name)
            DrivingMode.DRIVING
        else DrivingMode.PARKED
    )

    /**
     * Current [DrivingMode].
     * Updated by [setDrivingMode]; the UI should observe this to show/hide controls.
     */
    val drivingMode: StateFlow<DrivingMode> = _drivingMode.asStateFlow()

    // -------------------------------------------------------------------------
    //  Profile application
    // -------------------------------------------------------------------------

    /**
     * Apply [profile] by configuring brightness, volume, and the audio profile.
     *
     * This is the **single entry point** for profile activation.  All UI and
     * automation code should go through this method rather than calling the
     * sub-managers directly.
     *
     * @param profile The [DriveProfile] to activate.
     */
    fun applyProfile(profile: DriveProfile) {
        Timber.d("ProfileManager: applying profile '%s'", profile.name)

        // 1. Display brightness
        systemSettings.setBrightness(profile.displayBrightness)

        // 2. Media stream volume
        setMediaVolume(profile.volume)

        // 3. Audio EQ / effects profile
        audioProfileManager.applyProfile(profile.audioProfile)

        // 4. Update state and persist
        _activeProfile.value = profile
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profile.name).apply()
    }

    // -------------------------------------------------------------------------
    //  Driving mode
    // -------------------------------------------------------------------------

    /**
     * Derive the [DrivingMode] from the current vehicle speed and update
     * [drivingMode] if the mode has changed.
     *
     * Hysteresis:
     *  - Transition to [DrivingMode.DRIVING] when speed >= 5 km/h.
     *  - Return to [DrivingMode.PARKED] when speed < 2 km/h.
     *
     * @param speedKmh Current vehicle speed in km/h, from the CAN parser.
     */
    fun setDrivingMode(speedKmh: Float) {
        val current = _drivingMode.value
        val newMode = when {
            speedKmh >= DRIVING_THRESHOLD_KMH -> DrivingMode.DRIVING
            speedKmh < PARKED_THRESHOLD_KMH   -> DrivingMode.PARKED
            else -> current // hysteresis band — keep current mode
        }
        if (newMode != current) {
            Timber.d("ProfileManager: driving mode → %s (%.1f km/h)", newMode, speedKmh)
            _drivingMode.value = newMode
            prefs.edit().putString(KEY_MODE, newMode.name).apply()
        }
    }

    /**
     * Force a specific [DrivingMode] regardless of speed (e.g. for testing or manual override).
     */
    fun forceMode(mode: DrivingMode) {
        _drivingMode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        Timber.i("ProfileManager: mode forced to %s", mode)
    }

    // -------------------------------------------------------------------------
    //  Profile list
    // -------------------------------------------------------------------------

    /**
     * Return the full list of available [DriveProfile] instances.
     * Currently returns built-in profiles only; custom profiles stored in Room
     * should be merged here by the ViewModel.
     */
    fun getProfiles(): List<DriveProfile> = DriveProfile.ALL

    // -------------------------------------------------------------------------
    //  Persistence helpers
    // -------------------------------------------------------------------------

    /**
     * Re-apply the profile that was active when the app last exited.
     * Falls back to [DriveProfile.DAILY] if nothing was previously saved.
     */
    fun restoreLastProfile() {
        val savedName = prefs.getString(KEY_ACTIVE_PROFILE, null)
        val profile = DriveProfile.ALL.firstOrNull { it.name == savedName }
            ?: DriveProfile.DAILY
        Timber.d("ProfileManager: restoring profile '%s'", profile.name)
        applyProfile(profile)
    }

    // -------------------------------------------------------------------------
    //  Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Set the system media stream volume to [step].
     *
     * @param step Volume step in [0, [AudioManager.getStreamMaxVolume]].
     */
    private fun setMediaVolume(step: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                step.coerceIn(0, max),
                0 /* no UI flag */
            )
        } catch (e: Exception) {
            Timber.w(e, "ProfileManager: setMediaVolume(%d) failed", step)
        }
    }

    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val PREFS_NAME            = "drive_profiles"
        private const val KEY_ACTIVE_PROFILE    = "active_profile_name"
        private const val KEY_MODE              = "driving_mode"

        /** Speed at or above which the vehicle is considered to be driving. */
        private const val DRIVING_THRESHOLD_KMH = 5f

        /** Speed below which the vehicle is considered parked. */
        private const val PARKED_THRESHOLD_KMH  = 2f
    }
}
