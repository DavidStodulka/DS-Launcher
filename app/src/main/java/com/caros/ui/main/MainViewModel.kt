package com.caros.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.caros.audio.AdaptiveEQEngine
import com.caros.can.CANFrame
import com.caros.core.RootManager
import com.caros.core.RootStatus
import com.caros.db.CarOSDatabase
import com.caros.fuel.FuelStats
import com.caros.fuel.InstantFuelComputer
import com.caros.profiles.DrivingMode
import com.caros.profiles.ProfileManager
import com.caros.race.AggressiveDrivingDetector
import com.caros.service.DPFPrediction
import com.caros.service.DPFPredictorEngine
import com.caros.service.ServiceAdvisor
import com.caros.telemetry.RoutePredictorEngine
import com.caros.telemetry.RouteSuggestion
import com.caros.vcds.ConnectionType
import com.caros.vcds.OBDConnection
import com.caros.voice.VoiceCommandExecutor
import com.caros.voice.VoiceInputManager
import com.caros.voice.VoiceListeningState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CarOSDatabase,
    private val profileManager: ProfileManager,
    private val serviceAdvisor: ServiceAdvisor,
    private val voiceInputManager: VoiceInputManager,
    private val voiceCommandExecutor: VoiceCommandExecutor,
    private val adaptiveEQEngine: AdaptiveEQEngine,
    private val rootManager: RootManager,
    private val fuelComputer: InstantFuelComputer,
    private val dpfPredictorEngine: DPFPredictorEngine,
    private val routePredictorEngine: RoutePredictorEngine,
    val aggressiveDetector: AggressiveDrivingDetector,
    obdConnection: OBDConnection
) : ViewModel() {

    // ── CAN frame ─────────────────────────────────────────────────────────────

    private val _canFrame = MutableStateFlow(CANFrame.EMPTY)
    val canFrame: StateFlow<CANFrame> = _canFrame.asStateFlow()

    // ── Driving mode ──────────────────────────────────────────────────────────

    val drivingMode: StateFlow<DrivingMode> = profileManager.drivingMode

    // ── Service urgent count ──────────────────────────────────────────────────

    private val _serviceItemCount = MutableStateFlow(0)
    val serviceItemCount: StateFlow<Int> = _serviceItemCount.asStateFlow()

    // ── Voice state ───────────────────────────────────────────────────────────

    val voiceState: StateFlow<VoiceListeningState> = voiceInputManager.state

    /** Steering-wheel voice keycode read from EncryptedSharedPreferences. */
    val voiceKeyCode: StateFlow<Int?> = MutableStateFlow(readVoiceKeyCode()).also { flow ->
        // Wire canFrameFlow into VoiceCommandExecutor for car-info queries
        voiceCommandExecutor.canFrameFlow = canFrame
    }

    // ── Adaptive EQ enabled flag ──────────────────────────────────────────────

    val adaptiveEQEnabled: StateFlow<Boolean> = adaptiveEQEngine.isEnabled

    // ── Root status ───────────────────────────────────────────────────────────

    val rootStatus: StateFlow<RootStatus> = rootManager.rootStatus

    // ── OBD adapter status ────────────────────────────────────────────────────

    val obdState: StateFlow<ConnectionType> = obdConnection.connectionState

    // ── Fuel computer ─────────────────────────────────────────────────────────

    val fuelStats: StateFlow<FuelStats> = fuelComputer.stats

    // ── DPF prediction ────────────────────────────────────────────────────────

    private val _dpfPrediction = MutableStateFlow<DPFPrediction?>(null)
    val dpfPrediction: StateFlow<DPFPrediction?> = _dpfPrediction.asStateFlow()

    // ── Route suggestion (predictive navigation) ──────────────────────────────

    private val _routeSuggestion = MutableStateFlow<RouteSuggestion?>(null)
    val routeSuggestion: StateFlow<RouteSuggestion?> = _routeSuggestion.asStateFlow()

    init {
        startServicePolling()
        startDPFPolling()
        checkRouteSuggestion()
        viewModelScope.launch { rootManager.checkRootAvailability() }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Update the CAN frame and derive driving mode from current speed.
     */
    fun updateCANFrame(frame: CANFrame) {
        _canFrame.value = frame
        val speedKmh = frame.vehicleSpeed?.kmh ?: 0f
        profileManager.setDrivingMode(speedKmh)
        fuelComputer.update(frame)
    }

    /** Dismiss a route suggestion after the user acts on it (or declines). */
    fun clearRouteSuggestion() { _routeSuggestion.value = null }

    /**
     * Toggle voice listening on/off. If IDLE, starts listening; otherwise stops.
     */
    fun toggleVoiceListening() {
        if (voiceInputManager.state.value == VoiceListeningState.IDLE) {
            voiceInputManager.startListening(
                onResult = { text ->
                    Timber.d("MainViewModel: voice result: $text")
                },
                onError = { msg ->
                    Timber.w("MainViewModel: voice error: $msg")
                }
            )
        } else {
            voiceInputManager.stopListening()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun readVoiceKeyCode(): Int? {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context, "caros_voice_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val code = prefs.getInt("voice_keycode", -1)
            if (code == -1) null else code
        }.getOrElse {
            val prefs = context.getSharedPreferences("caros_voice_prefs", Context.MODE_PRIVATE)
            val code = prefs.getInt("voice_keycode", -1)
            if (code == -1) null else code
        }
    }

    private fun startServicePolling() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val currentKm = db.tripDao().totalDistanceKm()?.toInt() ?: 0
                    val items = serviceAdvisor.getAllServiceItems(currentKm)
                    _serviceItemCount.value = serviceAdvisor.getUrgentCount(items)
                } catch (e: Exception) {
                    Timber.w(e, "MainViewModel: service polling error")
                }
                delay(30_000L)
            }
        }
    }

    private fun startDPFPolling() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    _dpfPrediction.value = dpfPredictorEngine.getPrediction()
                } catch (e: Exception) {
                    Timber.w(e, "MainViewModel: DPF prediction error")
                }
                delay(60_000L)
            }
        }
    }

    private fun checkRouteSuggestion() {
        viewModelScope.launch {
            try {
                _routeSuggestion.value = routePredictorEngine.getSuggestion()
            } catch (e: Exception) {
                Timber.w(e, "MainViewModel: route suggestion check failed")
            }
        }
    }
}
