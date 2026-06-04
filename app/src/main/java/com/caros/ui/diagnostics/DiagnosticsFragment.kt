package com.caros.ui.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.caros.can.CANFrame
import com.caros.databinding.FragmentDiagnosticsBinding
import com.caros.ui.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.caros.db.CarOSDatabase

@AndroidEntryPoint
class DiagnosticsFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var db: CarOSDatabase

    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadTripStats()
        observeCANFrame()
    }

    private fun loadTripStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val session = db.telemetrySessionDao().getLatestSession()
                    val trip = db.tripDao().getLatestTrip()

                    withContext(Dispatchers.Main) {
                        binding.tvAvgSpeed.text = "Avg: %.0f km/h".format(trip?.avgSpeedKmh ?: 0f)
                        binding.tvMaxSpeed.text = "Max: %.0f km/h".format(trip?.maxSpeedKmh ?: 0f)
                        binding.tvDistance.text = "%.1f km".format(trip?.distanceKm ?: 0.0)

                        val durationSec = if (trip != null && trip.endTime != null)
                            (trip.endTime - trip.startTime) / 1_000L else 0L
                        binding.tvTripTime.text = "%02d:%02d".format(durationSec / 60, durationSec % 60)

                        // tvMaxRpm and tvAvgThrottle populated from live CAN in updateGauges
                        binding.tvMaxRpm.text = "--"
                        binding.tvAvgThrottle.text = "--"
                    }
                } catch (e: Exception) {
                    // Leave fields as default
                }
            }
        }
    }

    private fun observeCANFrame() {
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.canFrame.collect { frame ->
                updateGauges(frame)
            }
        }
    }

    private fun updateGauges(frame: CANFrame) {
        // Coolant Temp gauge
        frame.coolantTemp?.let { coolant ->
            binding.gaugeCoolant.setValue(coolant.celsius)
        }

        // Oil Temp gauge
        frame.oilTemp?.celsius?.let { oilTemp ->
            binding.gaugeOilTemp.setValue(oilTemp)
        }

        // Battery Voltage gauge
        frame.batteryVoltage?.let { batt ->
            binding.gaugeVoltage.setValue(batt.volts)
        }

        // DPF Load gauge
        frame.dpfData?.let { dpf ->
            binding.gaugeDPF.setValue(dpf.loadPercent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
