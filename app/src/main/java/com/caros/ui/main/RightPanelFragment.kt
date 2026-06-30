package com.caros.ui.main

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caros.databinding.FragmentRightPanelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RightPanelFragment : Fragment() {

    private var _binding: FragmentRightPanelBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRightPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeCANFrame()
    }

    private fun observeCANFrame() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            mainViewModel.canFrame.collect { frame ->
                // RPM gauge
                frame.engineRpm?.let { rpm ->
                    (binding.rpmGaugeView as? com.caros.views.RPMGaugeView)?.setRpm(rpm.rpm)
                }

                // Speed numeric
                frame.vehicleSpeed?.let { spd ->
                    binding.speedNumeric.text = "%.0f".format(spd.kmh)
                }

                // Coolant temperature
                frame.coolantTemp?.let { coolant ->
                    binding.coolantTempValue.text = "%.0f°C".format(coolant.celsius)
                    val color = when {
                        coolant.celsius > 110f -> Color.parseColor("#F44336")
                        coolant.celsius > 95f  -> Color.parseColor("#FF9800")
                        coolant.celsius > 60f  -> Color.parseColor("#4CAF50")
                        else                   -> Color.parseColor("#2196F3")
                    }
                    binding.coolantTempValue.setTextColor(color)
                }

                // Battery voltage
                frame.batteryVoltage?.let { batt ->
                    binding.batteryVoltageValue.text = "%.1f V".format(batt.volts)
                    val color = when {
                        batt.volts < 11.8f -> Color.parseColor("#F44336")
                        batt.volts < 12.4f -> Color.parseColor("#FF9800")
                        else               -> Color.parseColor("#4CAF50")
                    }
                    binding.batteryVoltageValue.setTextColor(color)
                }

                // Climate control panel
                frame.climateData?.let { climate ->
                    binding.acTempSetpoint.text = "%.1f°C".format(climate.setTemp)
                    binding.acFanSpeedValue.text = climate.fanSpeed.toString()
                    binding.acModeAc.isActivated = climate.acOn
                    binding.acModeRecirculate.isActivated = climate.recircOn
                }

                // Door / belt status indicators
                frame.doorState?.let { doors ->
                    val openColor  = Color.parseColor("#F44336")
                    val closedColor = Color.parseColor("#4CAF50")
                    binding.doorIndicatorFL.setBackgroundColor(if (doors.driver)    openColor else closedColor)
                    binding.doorIndicatorFR.setBackgroundColor(if (doors.passenger) openColor else closedColor)
                    binding.doorIndicatorRL.setBackgroundColor(if (doors.rearLeft)  openColor else closedColor)
                    binding.doorIndicatorRR.setBackgroundColor(if (doors.rearRight) openColor else closedColor)
                }

                frame.seatbeltState?.let { belts ->
                    val onColor  = Color.parseColor("#4CAF50")
                    val offColor = Color.parseColor("#F44336")
                    binding.beltIndicatorFL.setBackgroundColor(if (belts.driver)    onColor else offColor)
                    binding.beltIndicatorFR.setBackgroundColor(if (belts.passenger) onColor else offColor)
                    binding.beltIndicatorRL.setBackgroundColor(if (belts.rearLeft)  onColor else offColor)
                    binding.beltIndicatorRR.setBackgroundColor(if (belts.rearRight) onColor else offColor)
                }
            }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
