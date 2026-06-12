package com.caros.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import com.caros.core.HealthModules
import com.caros.core.ServiceHealthMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioBackend { JAMESDSP, VIPER4ANDROID, NATIVE }

@Singleton
class AudioEngineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthMonitor: ServiceHealthMonitor
) {
    val activeBackend: AudioBackend = detectBackend()

    private var nativeEq: Equalizer? = null
    private var nativeBass: BassBoost? = null
    private var nativeVirtualizer: Virtualizer? = null

    // current 10-band gains in dB
    private val currentGains = FloatArray(10)

    private fun detectBackend(): AudioBackend {
        val jdsp = runCatching {
            context.packageManager.getPackageInfo("james.dsp", 0)
            true
        }.getOrDefault(false)
        if (jdsp) return AudioBackend.JAMESDSP

        val v4a = runCatching {
            context.packageManager.getPackageInfo("com.pittvandewitt.viperfx", 0) != null ||
            context.packageManager.getPackageInfo("com.audlabs.viperfx", 0) != null
        }.getOrDefault(false)
        if (v4a) return AudioBackend.VIPER4ANDROID

        return AudioBackend.NATIVE
    }

    fun setEQBand(band: Int, gainDb: Float) {
        if (band !in 0..9) return
        currentGains[band] = gainDb.coerceIn(-12f, 12f)
        applyBandToBackend(band, gainDb)
    }

    fun applyFullEQ(gains: FloatArray) {
        // Called every 2 s by AdaptiveEQEngine — doubles as the audio liveness signal
        healthMonitor.heartbeat(HealthModules.AUDIO)
        gains.forEachIndexed { i, g -> if (i < 10) currentGains[i] = g.coerceIn(-12f, 12f) }
        when (activeBackend) {
            AudioBackend.JAMESDSP -> JamesDSPBridge.applyEQ(context, currentGains)
            AudioBackend.VIPER4ANDROID -> V4ABridge.applyEQ(context, currentGains)
            AudioBackend.NATIVE -> applyNativeEQ()
        }
    }

    fun setBass(strength: Int) {
        val clamped = strength.coerceIn(0, 100)
        when (activeBackend) {
            AudioBackend.JAMESDSP -> JamesDSPBridge.setBass(context, clamped)
            AudioBackend.VIPER4ANDROID -> V4ABridge.setBass(context, clamped)
            AudioBackend.NATIVE -> nativeBass?.setStrength((clamped * 10).toShort())
        }
    }

    fun setVocalClarity(enabled: Boolean) {
        when (activeBackend) {
            AudioBackend.JAMESDSP -> JamesDSPBridge.setVocalClarity(context, enabled)
            AudioBackend.VIPER4ANDROID -> V4ABridge.setVocalClarity(context, enabled)
            AudioBackend.NATIVE -> { /* no native equivalent */ }
        }
    }

    fun setSurroundStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 100)
        when (activeBackend) {
            AudioBackend.JAMESDSP -> JamesDSPBridge.setSurround(context, clamped)
            AudioBackend.VIPER4ANDROID -> V4ABridge.setSurround(context, clamped)
            AudioBackend.NATIVE -> nativeVirtualizer?.setStrength((clamped * 10).toShort())
        }
    }

    fun setCompression(enabled: Boolean, threshold: Float) {
        when (activeBackend) {
            AudioBackend.JAMESDSP -> JamesDSPBridge.setCompression(context, enabled, threshold)
            AudioBackend.VIPER4ANDROID -> V4ABridge.setCompression(context, enabled, threshold)
            AudioBackend.NATIVE -> { }
        }
    }

    fun setMasterGain(db: Float) {
        when (activeBackend) {
            AudioBackend.JAMESDSP -> JamesDSPBridge.setMasterGain(context, db)
            AudioBackend.VIPER4ANDROID -> V4ABridge.setMasterGain(context, db)
            AudioBackend.NATIVE -> { }
        }
    }

    fun setProfile(profile: AudioProfile) {
        setBass(profile.bassStrength)
        setSurroundStrength(profile.surroundStrength)
        setVocalClarity(profile.vocalClarity)
        setCompression(profile.compressionEnabled, profile.compressionThreshold)
        setMasterGain(profile.masterGain)
        applyFullEQ(profile.eqBands)
    }

    private fun applyBandToBackend(band: Int, gainDb: Float) {
        when (activeBackend) {
            AudioBackend.JAMESDSP -> JamesDSPBridge.setEQBand(context, band, gainDb)
            AudioBackend.VIPER4ANDROID -> V4ABridge.setEQBand(context, band, gainDb)
            AudioBackend.NATIVE -> applyNativeEQ()
        }
    }

    private fun applyNativeEQ() {
        val eq = nativeEq ?: return
        val bandCount = minOf(eq.numberOfBands.toInt(), 10)
        for (i in 0 until bandCount) {
            val milliDb = (currentGains[i] * 100).toInt()
            runCatching { eq.setBandLevel(i.toShort(), (milliDb + eq.getBandLevel(i.toShort())).toShort()) }
        }
    }

    fun backendBadgeText(): String = when (activeBackend) {
        AudioBackend.JAMESDSP -> "● JDSP"
        AudioBackend.VIPER4ANDROID -> "● V4A"
        AudioBackend.NATIVE -> "● Základní"
    }
}
