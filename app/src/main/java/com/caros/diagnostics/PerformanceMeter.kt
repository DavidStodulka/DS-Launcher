package com.caros.diagnostics

import com.caros.can.CANFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LiveMetrics(
    val speedKmh: Float = 0f,
    val rpm: Int = 0,
    val coolantC: Float = 0f,
    val oilC: Float? = null,
    val voltageV: Float = 0f,
    val boostKpa: Float = 0f,
    val dpfLoadPct: Float = 0f,
    val gear: String = "N",
    val throttlePct: Float = 0f
)

@Singleton
class PerformanceMeter @Inject constructor() {
    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics: StateFlow<LiveMetrics> = _metrics

    fun update(frame: CANFrame) {
        _metrics.value = LiveMetrics(
            speedKmh = frame.vehicleSpeed?.kmh ?: 0f,
            rpm = frame.engineRpm?.rpm ?: 0,
            coolantC = frame.coolantTemp?.celsius ?: 0f,
            oilC = frame.oilTemp?.celsius,
            voltageV = frame.batteryVoltage?.volts ?: 0f,
            boostKpa = frame.boostPressure?.kPa ?: 0f,
            dpfLoadPct = frame.dpfData?.loadPercent ?: 0f,
            gear = frame.dsgData?.gear ?: "N",
            throttlePct = frame.throttlePosition?.percent ?: 0f
        )
    }
}
