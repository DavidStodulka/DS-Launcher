package com.caros.audio

import kotlinx.serialization.Serializable

enum class AudioProfileType { FLAT, BASS_BOOST, VOCAL, STAGE, NIGHT, CUSTOM1, CUSTOM2, CUSTOM3 }

@Serializable
data class AudioProfile(
    val id: Long = 0,
    val type: AudioProfileType = AudioProfileType.FLAT,
    val name: String = "",
    val eqBands: FloatArray = FloatArray(10) { 0f }, // 10-band, -12 to +12 dB
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 500,               // 0-1000
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 500,
    val viperEnabled: Boolean = false,
    val viperSettings: String = "{}",               // JSON blob
    val masterVolume: Float = 1.0f
) {
    // FloatArray requires manual equals/hashCode when used in a data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioProfile) return false
        return id == other.id &&
            type == other.type &&
            name == other.name &&
            eqBands.contentEquals(other.eqBands) &&
            bassBoostEnabled == other.bassBoostEnabled &&
            bassBoostStrength == other.bassBoostStrength &&
            virtualizerEnabled == other.virtualizerEnabled &&
            virtualizerStrength == other.virtualizerStrength &&
            viperEnabled == other.viperEnabled &&
            viperSettings == other.viperSettings &&
            masterVolume == other.masterVolume
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + eqBands.contentHashCode()
        result = 31 * result + bassBoostEnabled.hashCode()
        result = 31 * result + bassBoostStrength
        result = 31 * result + virtualizerEnabled.hashCode()
        result = 31 * result + virtualizerStrength
        result = 31 * result + viperEnabled.hashCode()
        result = 31 * result + viperSettings.hashCode()
        result = 31 * result + masterVolume.hashCode()
        return result
    }

    companion object {
        val FLAT = AudioProfile(
            type = AudioProfileType.FLAT,
            name = "Flat"
        )
        val BASS_BOOST = AudioProfile(
            type = AudioProfileType.BASS_BOOST,
            name = "Bass Boost",
            eqBands = floatArrayOf(8f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            bassBoostEnabled = true,
            bassBoostStrength = 700
        )
        val VOCAL = AudioProfile(
            type = AudioProfileType.VOCAL,
            name = "Vocal",
            eqBands = floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 2f, 1f, 0f, -1f)
        )
        val STAGE = AudioProfile(
            type = AudioProfileType.STAGE,
            name = "Stage",
            eqBands = floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 2f, 3f, 4f, 4f),
            virtualizerEnabled = true,
            virtualizerStrength = 600
        )
        val NIGHT = AudioProfile(
            type = AudioProfileType.NIGHT,
            name = "Night",
            eqBands = floatArrayOf(-2f, -1f, 0f, 1f, 2f, 2f, 1f, 0f, -1f, -2f)
        )
        val ALL = listOf(FLAT, BASS_BOOST, VOCAL, STAGE, NIGHT)
    }
}
