package com.caros.profiles

import com.caros.audio.AudioProfile

/**
 * DriveProfile
 *
 * A named collection of system settings that can be applied together with a
 * single action.  Each profile captures display brightness, media volume,
 * an [AudioProfile], and optional behavioural flags.
 *
 * Built-in profiles ([DAILY], [NIGHT], [SPORT], [COMFORT]) are immutable
 * singletons; user-created profiles should be stored in the database via the
 * `ProfileEntity` Room entity.
 *
 * @param id                     Unique numeric identifier (0 = not yet persisted).
 * @param name                   Display name shown in the profile selector.
 * @param displayBrightness      Target screen brightness [0, 255].
 * @param volume                 Target media stream volume step [0, 15].
 * @param audioProfile           [AudioProfile] applied when this profile is activated.
 * @param isDrivingModeEnabled   When `true`, driving-safety restrictions are active.
 * @param autoApplyConditions    List of condition identifiers used by [AutomationEngine]
 *                               to activate this profile automatically (e.g. "speed_above_5").
 */
data class DriveProfile(
    val id: Long = 0L,
    val name: String = "",
    val displayBrightness: Int = 128,
    val volume: Int = 10,
    val audioProfile: AudioProfile = AudioProfile.FLAT,
    val isDrivingModeEnabled: Boolean = true,
    val autoApplyConditions: List<String> = emptyList()
) {
    // -------------------------------------------------------------------------
    //  Profile type enum (for categorisation / icons in the UI)
    // -------------------------------------------------------------------------

    /** Semantic category of a [DriveProfile], used for icon selection and ordering. */
    enum class ProfileType {
        /** Standard everyday driving. */
        DAILY,

        /** Reduced brightness for night-time driving. */
        NIGHT,

        /** Higher volume and stage-mode EQ for sporty driving. */
        SPORT,

        /** Relaxed settings for long highway cruising. */
        COMFORT,

        /** Workshop / diagnostic mode — disables most automated behaviour. */
        SERVICE
    }

    // -------------------------------------------------------------------------
    //  Built-in profiles
    // -------------------------------------------------------------------------

    companion object {
        /** Standard daytime driving profile. */
        val DAILY = DriveProfile(
            id = -1L,
            name = "Denní",
            displayBrightness = 180,
            volume = 12,
            audioProfile = AudioProfile.FLAT,
            isDrivingModeEnabled = true
        )

        /** Reduced-brightness night driving profile. */
        val NIGHT = DriveProfile(
            id = -2L,
            name = "Noční",
            displayBrightness = 60,
            volume = 8,
            audioProfile = AudioProfile.NIGHT,
            isDrivingModeEnabled = true
        )

        /** High-energy sport profile with Stage EQ and higher volume. */
        val SPORT = DriveProfile(
            id = -3L,
            name = "Sport",
            displayBrightness = 200,
            volume = 15,
            audioProfile = AudioProfile.STAGE,
            isDrivingModeEnabled = true
        )

        /** Relaxed comfort profile with Vocal EQ for pleasant highway cruising. */
        val COMFORT = DriveProfile(
            id = -4L,
            name = "Komfort",
            displayBrightness = 140,
            volume = 10,
            audioProfile = AudioProfile.VOCAL,
            isDrivingModeEnabled = false
        )

        /** Workshop profile — diagnostic use, automation disabled. */
        val SERVICE = DriveProfile(
            id = -5L,
            name = "Servis",
            displayBrightness = 220,
            volume = 5,
            audioProfile = AudioProfile.FLAT,
            isDrivingModeEnabled = false
        )

        /** All built-in profiles in display order. */
        val ALL = listOf(DAILY, NIGHT, SPORT, COMFORT, SERVICE)
    }
}
