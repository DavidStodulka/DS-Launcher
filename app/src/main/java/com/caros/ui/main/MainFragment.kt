package com.caros.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caros.databinding.FragmentMainCarStatusBinding
import com.caros.profiles.DrivingMode
import com.caros.ui.map.MapActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainCarStatusBinding? = null
    private val binding get() = _binding!!

    // Activity-scoped — must be the same instance MainActivity feeds CAN frames into
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainCarStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeCANFrame()
        observeDrivingMode()
        observeRouteSuggestion()
        observeAggressionScore()
    }

    private fun observeCANFrame() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.canFrame.collect { frame ->
                // Speed
                val speedKmh = frame.vehicleSpeed?.kmh ?: 0f
                binding.speedDisplay.text = speedKmh.toInt().toString()

                // Gear indicator
                val gear = frame.dsgData?.gear ?: "-"
                binding.gearIndicator.text = gear

                // No coolant field on this layout — handled in RightPanelFragment

                // DPF bar
                frame.dpfData?.let { dpf ->
                    binding.dpfProgressBar.progress = dpf.loadPercent.toInt()
                    binding.dpfPercent.text = "%.0f%%".format(dpf.loadPercent)
                }

                // Door indicators
                frame.doorState?.let { doors ->
                    val openColor = android.graphics.Color.parseColor("#F44336")
                    val closedColor = android.graphics.Color.parseColor("#4CAF50")
                    binding.doorFrontLeft.setBackgroundColor(if (doors.driver) openColor else closedColor)
                    binding.doorFrontRight.setBackgroundColor(if (doors.passenger) openColor else closedColor)
                    binding.doorRearLeft.setBackgroundColor(if (doors.rearLeft) openColor else closedColor)
                    binding.doorRearRight.setBackgroundColor(if (doors.rearRight) openColor else closedColor)
                }

                // DTC badge
                if (frame.activeDtcs.isNotEmpty()) {
                    binding.dtcBadgeRow.visibility = View.VISIBLE
                    binding.dtcCountBadge.text = frame.activeDtcs.size.toString()
                } else {
                    binding.dtcBadgeRow.visibility = View.GONE
                }
            }
            }
        }
    }

    private fun observeDrivingMode() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.drivingMode.collect { mode ->
                when (mode) {
                    DrivingMode.DRIVING -> {
                        // Show only speed/gear large; hide detailed status panels
                        binding.speedDisplay.textSize = 96f
                        binding.dpfSection.visibility = View.GONE
                        binding.seatbeltRow.visibility = View.GONE
                    }
                    DrivingMode.PARKED -> {
                        // Full status visible
                        binding.speedDisplay.textSize = 72f
                        binding.dpfSection.visibility = View.VISIBLE
                        binding.seatbeltRow.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /** Show a dialog offering to navigate to the predicted destination. */
    private fun observeRouteSuggestion() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.routeSuggestion.collect { suggestion ->
                    if (suggestion == null) return@collect
                    AlertDialog.Builder(requireContext())
                        .setTitle("Navigovat?")
                        .setMessage("Obvykle jezdíš do: ${suggestion.label}\n(${suggestion.confidence}× navštíveno)")
                        .setPositiveButton("Ano") { _, _ ->
                            // Launch navigation — trigger voice command or intent
                            viewModel.clearRouteSuggestion()
                            Timber.i("MainFragment: user accepted route suggestion to ${suggestion.label}")
                        }
                        .setNegativeButton("Ne") { _, _ -> viewModel.clearRouteSuggestion() }
                        .show()
                }
            }
        }
    }

    private fun observeAggressionScore() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.aggressiveDetector.sessionScore.collect { score ->
                    Timber.d("MainFragment: aggression score=$score")
                    // Score can be surfaced in a ScoreBar or DrivingStyleFragment
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
