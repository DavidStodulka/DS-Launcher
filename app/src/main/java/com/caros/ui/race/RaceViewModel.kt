package com.caros.ui.race

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caros.db.RaceSessionEntity
import com.caros.race.GForce
import com.caros.race.Measurement
import com.caros.race.MeasurementState
import com.caros.race.MeasurementType
import com.caros.race.RaceChronoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RaceViewModel @Inject constructor(
    private val raceChronoManager: RaceChronoManager
) : ViewModel() {

    // ── Delegated state ───────────────────────────────────────────────────────

    val measurementState: StateFlow<MeasurementState> =
        raceChronoManager.performanceEngine.state

    val measurementResult: StateFlow<Measurement?> =
        raceChronoManager.performanceEngine.result

    val gForce: StateFlow<GForce> =
        raceChronoManager.gForce.gForce

    fun sessions(): Flow<List<RaceSessionEntity>> =
        raceChronoManager.getSessions()

    // ── Public API ────────────────────────────────────────────────────────────

    fun startMeasurement(type: MeasurementType) {
        raceChronoManager.startPerformanceMeasurement(type)
    }

    fun reset() {
        raceChronoManager.performanceEngine.reset()
    }

    fun saveSession(location: String = "") {
        viewModelScope.launch {
            raceChronoManager.saveCurrentMeasurement(location)
        }
    }
}
