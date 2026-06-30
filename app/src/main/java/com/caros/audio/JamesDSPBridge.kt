package com.caros.audio

import android.content.Context
import android.content.Intent

object JamesDSPBridge {
    private const val ACTION = "james.dsp.action.SET_PARAMETER"

    fun applyEQ(context: Context, gains: FloatArray) {
        val bands = gains.joinToString(",") { "%.2f".format(it) }
        send(context, "eq_band_levels", bands)
    }

    fun setEQBand(context: Context, band: Int, gainDb: Float) {
        send(context, "eq_band_${band}", "%.2f".format(gainDb))
    }

    fun setBass(context: Context, strength: Int) {
        // Map 0-100 → bass boost dB 0-12
        send(context, "bass_boost_db", "%.2f".format(strength / 100f * 12f))
    }

    fun setVocalClarity(context: Context, enabled: Boolean) {
        send(context, "vocal_clarity", if (enabled) "1" else "0")
    }

    fun setSurround(context: Context, strength: Int) {
        send(context, "surround_strength", strength.toString())
    }

    fun setCompression(context: Context, enabled: Boolean, threshold: Float) {
        send(context, "compressor_enabled", if (enabled) "1" else "0")
        if (enabled) send(context, "compressor_threshold", "%.2f".format(threshold))
    }

    fun setMasterGain(context: Context, db: Float) {
        send(context, "master_gain", "%.2f".format(db))
    }

    private fun send(context: Context, param: String, value: String) {
        context.sendBroadcast(Intent(ACTION).apply {
            putExtra("param", param)
            putExtra("value", value)
            setPackage("james.dsp")
        })
    }
}
