package com.caros.audio

import kotlinx.serialization.Serializable

@Serializable
data class AudioProfile(
    val id: String,
    val name: String,
    val icon: Int = 0,
    val bassStrength: Int = 50,
    val trebleStrength: Int = 50,
    val midrangeStrength: Int = 50,
    val vocalClarity: Boolean = false,
    val surroundStrength: Int = 0,
    val compressionEnabled: Boolean = false,
    val compressionThreshold: Float = -18f,
    val reverbEnabled: Boolean = false,
    val reverbRoomSize: Float = 0f,
    val reverbWetLevel: Float = 0f,
    val eqBands: FloatArray = FloatArray(10),   // user offset per band
    val masterGain: Float = 0f,
    val autoEQEnabled: Boolean = true,
    val autoSwitchSource: AudioSource? = null
) {
    companion object {
        val FLAT = AudioProfile("flat", "Flat", autoEQEnabled = true)
        val BASS_PLUS = AudioProfile(
            "bass", "Bass+", bassStrength = 85,
            eqBands = floatArrayOf(4f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)
        )
        val VOCAL = AudioProfile(
            "vocal", "Vocal", vocalClarity = true,
            eqBands = floatArrayOf(-1f, -1f, 0f, 2f, 3f, 2f, 1f, 0f, 0f, 0f)
        )
        val STAGE = AudioProfile(
            "stage", "Stage", surroundStrength = 60,
            eqBands = floatArrayOf(2f, 3f, 1f, 0f, 0f, 1f, 2f, 2f, 1f, 0f)
        )
        val NIGHT = AudioProfile(
            "night", "Night", compressionEnabled = true,
            compressionThreshold = -24f, autoEQEnabled = true,
            eqBands = floatArrayOf(-1f, -0.5f, 0f, 0f, 0f, 0.5f, 1f, 0f, 0f, 0f)
        )
        val SPORT = AudioProfile(
            "sport", "Sport", bassStrength = 70, surroundStrength = 40,
            eqBands = floatArrayOf(3f, 4f, 2f, 0f, 0f, 1f, 2f, 2f, 1f, 1f)
        )
        val ALL = listOf(FLAT, BASS_PLUS, VOCAL, STAGE, NIGHT, SPORT)

        // Legacy aliases kept for compatibility with EQFragment preset buttons
        val BASS_BOOST get() = BASS_PLUS
    }

    // FloatArray equals by content
    override fun equals(other: Any?) = other is AudioProfile && id == other.id
    override fun hashCode() = id.hashCode()
}
