package com.caros.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caros.can.CANFrame
import com.caros.db.CarOSDatabase
import com.caros.profiles.DrivingMode
import com.caros.profiles.ProfileManager
import com.caros.service.DPFMonitor
import com.caros.service.ServiceAdvisor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val db: CarOSDatabase,
    private val profileManager: ProfileManager,
    private val serviceAdvisor: ServiceAdvisor,
    private val dpfMonitor: DPFMonitor
) : ViewModel() {

    // ── CAN frame ─────────────────────────────────────────────────────────────

    private val _canFrame = MutableStateFlow(CANFrame.EMPTY)
    val canFrame: StateFlow<CANFrame> = _canFrame.asStateFlow()

    // ── Driving mode ──────────────────────────────────────────────────────────

    val drivingMode: StateFlow<DrivingMode> = profileManager.drivingMode

    // ── Service urgent count ──────────────────────────────────────────────────

    private val _serviceItemCount = MutableStateFlow(0)
    val serviceItemCount: StateFlow<Int> = _serviceItemCount.asStateFlow()

    init {
        startServicePolling()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Update the CAN frame and derive driving mode from current speed.
     */
    fun updateCANFrame(frame: CANFrame) {
        _canFrame.value = frame
        val speedKmh = frame.vehicleSpeed?.kmh ?: 0f
        profileManager.setDrivingMode(speedKmh)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
}
