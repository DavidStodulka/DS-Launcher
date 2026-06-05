package com.caros.termux

import com.caros.can.CANFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryBridge @Inject constructor(
    private val mqtt: MQTTPublisher
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startBridging(canFlow: Flow<CANFrame>) {
        scope.launch {
            canFlow.collect { frame ->
                val data = mutableMapOf<String, Any>()
                frame.vehicleSpeed?.let { data["speed"] = it.kmh }
                frame.engineRpm?.let { data["rpm"] = it.rpm }
                frame.coolantTemp?.let { data["coolant_temp"] = it.celsius }
                frame.batteryVoltage?.let { data["voltage"] = it.volts }
                frame.dsgData?.let { data["gear"] = it.gear ?: 0 }
                frame.dpfData?.let { data["dpf_load"] = it.loadPercent }
                if (data.isNotEmpty()) {
                    runCatching { mqtt.publishCANFrame(data) }
                }
            }
        }
    }
}
