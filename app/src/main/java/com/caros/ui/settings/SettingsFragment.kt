package com.caros.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentSettingsBinding
import com.caros.profiles.ProfileManager
import com.caros.core.ShellExecutor
import com.caros.system.SystemSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var systemSettings: SystemSettings
    @Inject lateinit var profileManager: ProfileManager
    @Inject lateinit var shellExecutor: ShellExecutor

    private lateinit var prefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences("caros_settings", android.content.Context.MODE_PRIVATE)
        setupBrightness()
        setupPowerMode()
        setupMockCAN()
        setupADB()
        startSystemInfoRefresh()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemInfo()
        refreshNetworkInfo()
    }

    // ── Brightness ────────────────────────────────────────────────────────────

    private fun setupBrightness() {
        binding.seekBrightness.max = 255
        binding.seekBrightness.progress = try {
            android.provider.Settings.System.getInt(
                requireContext().contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 128
            )
        } catch (e: Exception) { 128 }

        binding.tvBrightnessValue.text = binding.seekBrightness.progress.toString()

        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvBrightnessValue.text = progress.toString()
                if (fromUser) systemSettings.setBrightness(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Power mode ────────────────────────────────────────────────────────────

    private fun setupPowerMode() {
        val savedMode = prefs.getString("acc_power_mode", "deep_sleep") ?: "deep_sleep"
        if (savedMode == "deep_sleep") {
            binding.radioDeepSleep.isChecked = true
        } else {
            binding.radioPowerOff.isChecked = true
        }
        binding.rgPowerMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.radioDeepSleep.id) "deep_sleep" else "power_off"
            prefs.edit().putString("acc_power_mode", mode).apply()
        }

        // Shutdown delay
        val delay = prefs.getInt("shutdown_delay_sec", 30)
        binding.seekBarShutdownDelay.max = 300
        binding.seekBarShutdownDelay.progress = delay
        binding.tvShutdownDelay.text = "$delay s"

        binding.seekBarShutdownDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvShutdownDelay.text = "$progress s"
                if (fromUser) prefs.edit().putInt("shutdown_delay_sec", progress).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Mock CAN ──────────────────────────────────────────────────────────────

    private fun setupMockCAN() {
        binding.switchMockCAN.isChecked = prefs.getBoolean("use_mock_can", false)
        binding.switchMockCAN.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("use_mock_can", checked).apply()
        }
    }

    // ── ADB ───────────────────────────────────────────────────────────────────

    private fun setupADB() {
        binding.btnADBToggle.setOnClickListener {
            systemSettings.setADBWireless(true)
            val ip = systemSettings.getWifiIpAddress() ?: "N/A"
            AlertDialog.Builder(requireContext())
                .setTitle("ADB Wireless")
                .setMessage("ADB aktivováno na portu 5555\nIP: $ip\n\nadb connect $ip:5555")
                .setPositiveButton("OK", null)
                .show()
            binding.tvADBStatus.text = "ADB: $ip:5555"
        }
    }

    // ── System info ───────────────────────────────────────────────────────────

    private fun startSystemInfoRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshSystemInfo()
                delay(5_000L)
            }
        }
    }

    private fun refreshSystemInfo() {
        val temp = systemSettings.getCpuTemperature()
        binding.tvTemp.text = if (temp != null) "CPU temp: %.1f°C".format(temp) else "CPU temp: N/A"

        val uptime = systemSettings.getUptimeSeconds()
        val uptimeStr = "%02d:%02d:%02d".format(uptime / 3600, (uptime % 3600) / 60, uptime % 60)
        binding.tvUptime.text = "Uptime: $uptimeStr"

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L
        val totalMb = runtime.maxMemory() / 1_048_576L
        binding.tvRAM.text = "RAM: ${usedMb}MB / ${totalMb}MB"

        binding.tvBuild.text = "Build: ${android.os.Build.DISPLAY}"

        // CPU usage via /proc/stat is not straightforward; show load average instead
        binding.tvCPU.text = try {
            val load = java.io.File("/proc/loadavg").readText().trim().split(" ")
            "CPU Load: ${load[0]} ${load[1]} ${load[2]}"
        } catch (e: Exception) {
            "CPU Load: N/A"
        }
    }

    private fun refreshNetworkInfo() {
        val ssid = systemSettings.getWifiSsid()
        val ip = systemSettings.getWifiIpAddress()
        binding.tvCurrentSSID.text = "SSID: ${ssid ?: "Nepřipojeno"}"
        binding.tvIP.text = "IP: ${ip ?: "N/A"}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
