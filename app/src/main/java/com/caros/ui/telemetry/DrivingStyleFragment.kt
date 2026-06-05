package com.caros.ui.telemetry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentDrivingStyleBinding
import com.caros.ui.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DrivingStyleFragment : Fragment() {

    private var _binding: FragmentDrivingStyleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDrivingStyleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeData()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.canFrame.collect { frame ->
                val rpm = frame.engineRpm?.rpm ?: 0
                val throttle = frame.throttlePosition?.percent ?: 0f
                val speed = frame.vehicleSpeed?.kmh ?: 0f
                // Score 0-100 based on smoothness
                val score = computeScore(rpm, throttle, speed)
                binding.tvDrivingScore.text = "$score"
                binding.tvScoreLabel.text = when {
                    score >= 80 -> "Ekonomická jízda"
                    score >= 60 -> "Normální jízda"
                    score >= 40 -> "Sportovní jízda"
                    else -> "Agresivní jízda"
                }
                binding.progressDrivingScore.progress = score
                binding.tvRpmValue.text = "$rpm ot/min"
                binding.tvThrottleValue.text = "%.0f%%".format(throttle)
                binding.tvSpeedValue.text = "%.0f km/h".format(speed)
            }
        }
    }

    private fun computeScore(rpm: Int, throttle: Float, speed: Float): Int {
        val rpmScore = when {
            rpm < 1500 -> 100
            rpm < 2500 -> 85
            rpm < 3500 -> 65
            rpm < 4500 -> 40
            else -> 20
        }
        val throttleScore = (100 - throttle.coerceIn(0f, 100f)).toInt()
        return ((rpmScore * 0.6 + throttleScore * 0.4)).toInt().coerceIn(0, 100)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
