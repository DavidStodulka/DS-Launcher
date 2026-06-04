package com.caros.ui.race

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.caros.databinding.FragmentRaceBinding
import com.caros.race.MeasurementState
import com.caros.race.MeasurementType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RaceFragment : Fragment() {
    private var _binding: FragmentRaceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RaceViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentRaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mode selector buttons
        binding.btn0to100.setOnClickListener { viewModel.startMeasurement(MeasurementType.ZERO_TO_100) }
        binding.btn0to200.setOnClickListener { viewModel.startMeasurement(MeasurementType.ZERO_TO_200) }
        binding.btn80to120.setOnClickListener { viewModel.startMeasurement(MeasurementType.OVERTAKE_80_120) }
        binding.btnBraking.setOnClickListener { viewModel.startMeasurement(MeasurementType.BRAKING) }
        binding.btnReset.setOnClickListener { viewModel.reset() }

        // Launch HUD for active measurement
        binding.btnLaunchHUD.setOnClickListener {
            startActivity(Intent(requireContext(), RaceHUDActivity::class.java))
        }

        // Observe state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.measurementState.collectLatest { state ->
                binding.tvMeasurementState.text = when (state) {
                    MeasurementState.IDLE -> "Připraven"
                    MeasurementState.WAITING_FOR_START -> "Čeká na start..."
                    MeasurementState.MEASURING -> "MĚŘENÍ..."
                    MeasurementState.COMPLETE -> "HOTOVO"
                    MeasurementState.FAILED -> "Chyba"
                }
                binding.btnLaunchHUD.visibility = if (state == MeasurementState.MEASURING) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.measurementResult.collectLatest { result ->
                if (result != null) {
                    binding.tvResult.text = String.format("%.3f s", result.durationMs / 1000f)
                    binding.tvMaxSpeed.text = String.format("%.1f km/h", result.maxSpeedKmh)
                    binding.tvAvgAccel.text = String.format("%.2f m/s²", result.avgAccelerationMs2)
                }
            }
        }

        // Sessions history
        binding.rvSessions.layoutManager = LinearLayoutManager(context)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sessions.collectLatest { sessions ->
                // TODO: bind to adapter
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
