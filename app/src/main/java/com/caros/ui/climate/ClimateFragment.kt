package com.caros.ui.climate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caros.databinding.FragmentClimateBinding
import com.caros.ui.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ClimateFragment : Fragment() {

    private var _binding: FragmentClimateBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ClimateViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClimateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        observeState()
    }

    private fun setupButtons() {
        binding.btnTempUp.setOnClickListener { viewModel.tempUp() }
        binding.btnTempDown.setOnClickListener { viewModel.tempDown() }
        binding.btnFanUp.setOnClickListener { viewModel.fanUp() }
        binding.btnFanDown.setOnClickListener { viewModel.fanDown() }
        binding.btnAC.setOnClickListener { viewModel.toggleAC() }
        binding.btnRecirc.setOnClickListener { viewModel.toggleRecirc() }
        binding.btnDefrost.setOnClickListener { viewModel.toggleDefrost() }
        binding.btnAuto.setOnClickListener { viewModel.toggleAuto() }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { viewModel.targetTemp.collect { binding.tvTargetTemp.text = "%.1f°".format(it) } }
            launch { viewModel.fanSpeed.collect { binding.tvFanSpeed.text = it.toString() ; updateFanBars(it) } }
            launch { viewModel.acEnabled.collect { highlightBtn(binding.btnAC, it) } }
            launch { viewModel.recircEnabled.collect { highlightBtn(binding.btnRecirc, it) } }
            launch { viewModel.defrostEnabled.collect { highlightBtn(binding.btnDefrost, it) } }
            launch { viewModel.autoMode.collect { highlightBtn(binding.btnAuto, it) } }
            // Interior temp + initial state sync from CAN
            launch {
                mainViewModel.canFrame.collect { frame ->
                    val climate = frame.climateData
                    binding.tvInteriorTemp.text =
                        if (climate?.interiorTemp != null) "%.1f°".format(climate.interiorTemp) else "--°"
                    // Sync once with car's actual state
                    viewModel.syncFromCAN(
                        climate?.setTemp, climate?.fanSpeed, climate?.acOn, climate?.recircOn
                    )
                }
            }
            }
        }
    }

    private fun highlightBtn(btn: android.widget.Button, active: Boolean) {
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (active) 0xFF1565C0.toInt() else 0xFF1E1E1E.toInt()
        )
    }

    private fun updateFanBars(speed: Int) {
        val bars = listOf(binding.fanBar1, binding.fanBar2, binding.fanBar3,
                          binding.fanBar4, binding.fanBar5, binding.fanBar6, binding.fanBar7)
        bars.forEachIndexed { i, bar ->
            bar.setBackgroundColor(if (i < speed) 0xFF1565C0.toInt() else 0xFF2A2A2A.toInt())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
