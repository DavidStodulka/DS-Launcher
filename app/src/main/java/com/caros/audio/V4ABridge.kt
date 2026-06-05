package com.caros.audio

import android.content.Context
import android.content.Intent

object V4ABridge {
    private const val ACTION = "com.pittvandewitt.viperfx.ACTION_COMMAND"

    fun applyEQ(context: Context, gains: FloatArray) {
        gains.forEachIndexed { i, g -> setEQBand(context, i, g) }
    }

    fun setEQBand(context: Context, band: Int, gainDb: Float) {
        // V4A uses 0-100 scale for EQ bands (50 = 0dB)
        val v4aVal = ((gainDb / 12f) * 50 + 50).toInt().coerceIn(0, 100)
        send(context, "eq_band_${band}_gain", v4aVal.toString())
    }

    fun setBass(context: Context, strength: Int) {
        send(context, "viperbass_strength", strength.toString())
    }

    fun setVocalClarity(context: Context, enabled: Boolean) {
        send(context, "clarity_mode", if (enabled) "1" else "0")
    }

    fun setSurround(context: Context, strength: Int) {
        send(context, "surround_level", strength.toString())
    }

    fun setCompression(context: Context, enabled: Boolean, threshold: Float) {
        send(context, "dynamic_system_enable", if (enabled) "1" else "0")
    }

    fun setMasterGain(context: Context, db: Float) {
        val v4aGain = ((db + 12f) / 24f * 200).toInt().coerceIn(0, 200)
        send(context, "master_limiter_output", v4aGain.toString())
    }

    private fun send(context: Context, command: String, value: String) {
        val intent = Intent(ACTION).apply {
            putExtra("command", command)
            putExtra("value", value)
        }
        // Try both known V4A package names
        for (pkg in listOf("com.pittvandewitt.viperfx", "com.audlabs.viperfx")) {
            runCatching { context.sendBroadcast(intent.also { it.setPackage(pkg) }) }
        }
    }
}
