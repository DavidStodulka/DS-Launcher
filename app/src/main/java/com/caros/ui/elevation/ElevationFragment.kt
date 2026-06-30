package com.caros.ui.elevation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentElevationBinding
import com.caros.elevation.ElevationTracker
import com.caros.elevation.RouteRecorder
import com.caros.views.ElevationViewPoint
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ElevationFragment : Fragment() {

    private var _binding: FragmentElevationBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var elevationTracker: ElevationTracker
    @Inject lateinit var routeRecorder: RouteRecorder

    private var isRecording = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentElevationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeElevation()
        setupRecordingButton()
    }

    private fun observeElevation() {
        viewLifecycleOwner.lifecycleScope.launch {
            elevationTracker.currentAlt.collect { alt ->
                binding.tvAltitude.text = "%.0f m n.m.".format(alt)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            elevationTracker.currentSlope.collect { slope ->
                val sign = if (slope >= 0) "+" else ""
                binding.tvSlope.text = "$sign%.1f%%".format(slope)
                val color = when {
                    slope > 8f   -> android.graphics.Color.parseColor("#F44336")
                    slope > 4f   -> android.graphics.Color.parseColor("#FF9800")
                    slope < -8f  -> android.graphics.Color.parseColor("#2196F3")
                    slope < -4f  -> android.graphics.Color.parseColor("#03A9F4")
                    else         -> android.graphics.Color.parseColor("#4CAF50")
                }
                binding.tvSlope.setTextColor(color)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            elevationTracker.points.collect { points ->
                if (points.isNotEmpty()) {
                    val profileData = points.mapIndexed { idx, point ->
                        ElevationViewPoint(
                            distanceKm = idx.toFloat() * 0.01f,  // crude 10m spacing
                            altitudeM  = point.altM,
                            slopePct   = point.slopePercent
                        )
                    }
                    binding.elevationProfileView.setData(profileData)

                    // Estimate fuel consumption (diesel base 6 L/100 km, slope correction ±0.06 per %)
                    val totalDistKm = points.size * 0.01
                    val totalFuelL = points.sumOf { pt ->
                        val rate = (6.0 + pt.slopePercent * 0.06).coerceAtLeast(0.5)
                        rate * 0.01 / 100.0
                    }
                    val per100 = if (totalDistKm > 0.01) totalFuelL / totalDistKm * 100 else 0.0
                    binding.tvFuelEstimate.text =
                        "Spotřeba: %.2f L  (%.1f L/100 km)".format(totalFuelL, per100)
                }
            }
        }
    }

    private fun setupRecordingButton() {
        binding.btnRecording.setOnClickListener {
            if (!isRecording) {
                routeRecorder.startRecording()
                isRecording = true
                binding.btnRecording.text = "Zastavit záznam"
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    routeRecorder.stopAndSave()
                    isRecording = false
                    binding.btnRecording.text = "Spustit záznam"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
