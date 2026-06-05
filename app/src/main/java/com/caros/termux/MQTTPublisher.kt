package com.caros.termux

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MQTTPublisher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Simple HTTP bridge to bridge.py on localhost:8765
    private val bridgeUrl = "http://localhost:8765"

    suspend fun publishCANFrame(data: Map<String, Any>) = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildJsonString(data)
            postToBridge(json)
        }.onFailure { Timber.w("MQTT bridge unavailable: ${it.message}") }
    }

    suspend fun publish(topic: String, value: String) = withContext(Dispatchers.IO) {
        publishCANFrame(mapOf("topic" to topic, "value" to value))
    }

    private fun postToBridge(json: String) {
        val url = java.net.URL(bridgeUrl)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 500
        conn.readTimeout = 500
        conn.outputStream.use { it.write(json.toByteArray()) }
        conn.responseCode  // trigger send
        conn.disconnect()
    }

    private fun buildJsonString(map: Map<String, Any>): String {
        val sb = StringBuilder("{")
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"$k\":")
            when (v) {
                is String -> sb.append("\"$v\"")
                is Boolean -> sb.append(v)
                is Number -> sb.append(v)
                else -> sb.append("\"$v\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }
}
