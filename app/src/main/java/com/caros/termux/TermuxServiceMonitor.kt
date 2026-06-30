package com.caros.termux

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class ServiceStatus(
    val ssh: Boolean = false,
    val mqtt: Boolean = false,
    val influx: Boolean = false,
    val pythonBridge: Boolean = false,
    val syncthing: Boolean = false,
    val mqttClients: Int = 0,
    val influxDataMB: Long = 0L,
    val sessionCount: Int = 0
)

@Singleton
class TermuxServiceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val statusFlow: Flow<ServiceStatus> = flow {
        while (true) {
            emit(pollStatus())
            delay(10_000)
        }
    }.flowOn(Dispatchers.IO)

    private fun pollStatus(): ServiceStatus {
        val processes = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps aux"))
            BufferedReader(InputStreamReader(p.inputStream)).readLines()
        }.getOrDefault(emptyList())

        val ssh = processes.any { it.contains("sshd") }
        val mqtt = processes.any { it.contains("mosquitto") }
        val influx = processes.any { it.contains("influxd") }
        val python = processes.any { it.contains("bridge.py") }
        val sync = processes.any { it.contains("syncthing") }

        val mqttClients = if (mqtt) runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "netstat -n | grep :1883 | grep ESTABLISHED | wc -l"))
            BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()?.toIntOrNull() ?: 0
        }.getOrDefault(0) else 0

        return ServiceStatus(ssh, mqtt, influx, python, sync, mqttClients)
    }

    fun restartService(name: String) {
        val cmd = when (name) {
            "mqtt" -> "run-as com.termux bash -c 'mosquitto -d -c ~/.config/mosquitto/mosquitto.conf'"
            "influx" -> "run-as com.termux bash -c 'influxd run &'"
            "python" -> "run-as com.termux bash -c 'python3 ~/caros/bridge.py &'"
            "ssh" -> "run-as com.termux bash -c 'sshd'"
            "syncthing" -> "run-as com.termux bash -c 'syncthing serve --no-browser &'"
            else -> return
        }
        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)) }
    }

    fun getLocalIP(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ip route get 1 | awk '{print $7}'"))
        BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim() ?: "N/A"
    }.getOrDefault("N/A")
}
