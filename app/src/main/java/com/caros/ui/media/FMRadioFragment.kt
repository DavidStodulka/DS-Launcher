package com.caros.ui.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentFmRadioBinding
import com.caros.multimedia.FMRadioViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FMRadioFragment : Fragment() {

    private var _binding: FragmentFmRadioBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FMRadioViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFmRadioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        observeState()
    }

    private fun setupButtons() {
        binding.btnPlay.setOnClickListener { viewModel.play() }
        binding.btnStop.setOnClickListener { viewModel.stop() }
        binding.btnStepDown.setOnClickListener { viewModel.stepDown() }
        binding.btnStepUp.setOnClickListener { viewModel.stepUp() }
        binding.btnScanDown.setOnClickListener { viewModel.scanDown() }
        binding.btnScanUp.setOnClickListener { viewModel.scanUp() }
        // Preset buttons 1-6
        listOf(binding.btnPreset1, binding.btnPreset2, binding.btnPreset3,
               binding.btnPreset4, binding.btnPreset5, binding.btnPreset6)
            .forEachIndexed { i, btn ->
                btn.setOnClickListener { viewModel.loadPreset(i) }
                btn.setOnLongClickListener { viewModel.savePreset(i); true }
            }
        // Fan speed buttons
        binding.btnFanAuto.setOnClickListener { viewModel.setFanSpeed(0) }
        binding.btnFanLow.setOnClickListener { viewModel.setFanSpeed(1) }
        binding.btnFanMed.setOnClickListener { viewModel.setFanSpeed(2) }
        binding.btnFanHigh.setOnClickListener { viewModel.setFanSpeed(3) }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            launch { viewModel.frequency.collect { binding.tvFrequency.text = "%.1f MHz".format(it) } }
            launch { viewModel.rdsText.collect { binding.tvRds.text = it.ifEmpty { "– – –" } } }
            launch { viewModel.isPlaying.collect { binding.btnPlay.isEnabled = !it; binding.btnStop.isEnabled = it } }
            launch { viewModel.cpuTemp.collect { binding.tvCpuTemp.text = "%.0f°C".format(it) } }
            launch {
                viewModel.presets.collect { presets ->
                    listOf(binding.btnPreset1, binding.btnPreset2, binding.btnPreset3,
                           binding.btnPreset4, binding.btnPreset5, binding.btnPreset6)
                        .forEachIndexed { i, btn ->
                            btn.text = if (presets[i] > 0f) "%.1f".format(presets[i]) else "P${i+1}"
                        }
                }
            }
            launch {
                viewModel.fanSpeed.collect { speed ->
                    val accent = 0xFF1565C0.toInt()
                    val grey = 0xFF1E1E1E.toInt()
                    binding.btnFanAuto.setBackgroundColor(if (speed == 0) accent else grey)
                    binding.btnFanLow.setBackgroundColor(if (speed == 1) accent else grey)
                    binding.btnFanMed.setBackgroundColor(if (speed == 2) accent else grey)
                    binding.btnFanHigh.setBackgroundColor(if (speed == 3) accent else grey)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
