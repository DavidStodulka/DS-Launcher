package com.caros.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentSettingsBinding
import com.caros.multimedia.AndroidAutoManager
import com.caros.profiles.ProfileManager
import com.caros.core.ShellExecutor
import com.caros.system.AutoAction
import com.caros.system.AutoCondition
import com.caros.system.AutomationEngine
import com.caros.system.AutomationRule
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
    @Inject lateinit var androidAutoManager: AndroidAutoManager
    @Inject lateinit var automationEngine: AutomationEngine

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
        setupAndroidAuto()
        loadCustomRules()
        setupAutomation()
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
            binding.rbDeepSleep.isChecked = true
        } else {
            binding.rbPowerOff.isChecked = true
        }
        binding.rgShutdownMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.rbDeepSleep.id) "deep_sleep" else "power_off"
            prefs.edit().putString("acc_power_mode", mode).apply()
        }

        val delay = prefs.getInt("shutdown_delay_sec", 60)
        binding.seekShutdownDelay.max = 300
        binding.seekShutdownDelay.progress = delay
        binding.tvShutdownDelay.text = "${delay}s"

        binding.seekShutdownDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvShutdownDelay.text = "${progress}s"
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

    // ── Android Auto ──────────────────────────────────────────────────────────

    private fun setupAndroidAuto() {
        binding.switchAndroidAuto.isChecked = androidAutoManager.autoConnectEnabled.value
        binding.switchAndroidAuto.setOnCheckedChangeListener { _, isChecked ->
            androidAutoManager.setAutoConnect(isChecked)
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

    // ── Automation rules ──────────────────────────────────────────────────────

    private fun setupAutomation() {
        refreshAutomationRules()
        binding.btnAddAutomationRule.setOnClickListener { showAddRuleDialog() }
    }

    private fun refreshAutomationRules() {
        val container = binding.llAutomationRules
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val builtinIds = setOf("driving_mode", "parked_mode", "dpf_warning", "voltage_warning", "coolant_warning", "night_dim")

        automationEngine.getRules().forEach { rule ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (1 * dp).toInt() }
                setBackgroundColor(0xFF1A1A1A.toInt())
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }

            val nameView = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = rule.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
            }

            val toggle = Switch(requireContext()).apply {
                isChecked = rule.isEnabled
                setOnCheckedChangeListener { _, checked ->
                    automationEngine.setRuleEnabled(rule.id, checked)
                }
            }

            row.addView(nameView)
            row.addView(toggle)

            if (rule.id !in builtinIds) {
                val del = TextView(requireContext()).apply {
                    text = "  ✕"
                    setTextColor(0xFFC62828.toInt())
                    textSize = 16f
                    setPadding((8 * dp).toInt(), 0, 0, 0)
                    setOnClickListener {
                        automationEngine.removeRule(rule.id)
                        removeCustomRule(rule.id)
                        refreshAutomationRules()
                    }
                }
                row.addView(del)
            }

            container.addView(row)
        }
    }

    private val conditionLabels = listOf(
        "Rychlost nad (km/h)" to "SPEED_ABOVE",
        "Rychlost pod (km/h)" to "SPEED_BELOW",
        "Teplota chladiva nad (°C)" to "COOLANT_ABOVE",
        "Napětí pod (V)" to "VOLTAGE_BELOW",
        "DPF zátěž nad (%)" to "DPF_ABOVE",
        "ACC zapnuto" to "ACC_ON",
        "ACC vypnuto" to "ACC_OFF",
        "Čas dne (HH:MM)" to "TIME_OF_DAY"
    )

    private val actionLabels = listOf(
        "Notifikace (nadpis|text)" to "NOTIFICATION",
        "Nastav jas (0–255)" to "BRIGHTNESS",
        "Spusť aplikaci (pkg)" to "APP",
        "Driving mód" to "DRIVING_MODE",
        "Parked mód" to "PARKED_MODE"
    )

    private fun showAddRuleDialog() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (8 * dp).toInt())
        }

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            setTextColor(0xFF757575.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
        }

        val etName = android.widget.EditText(ctx).apply {
            hint = "Název pravidla"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF757575.toInt())
        }

        val condSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                conditionLabels.map { it.first }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val etCondValue = android.widget.EditText(ctx).apply {
            hint = "Hodnota podmínky"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF757575.toInt())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val actionSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                actionLabels.map { it.first }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val etActionValue = android.widget.EditText(ctx).apply {
            hint = "Hodnota akce"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF757575.toInt())
        }

        condSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val key = conditionLabels[pos].second
                etCondValue.visibility = if (key == "ACC_ON" || key == "ACC_OFF") View.GONE else View.VISIBLE
                if (key == "TIME_OF_DAY") {
                    etCondValue.hint = "HH:MM"
                    etCondValue.inputType = android.text.InputType.TYPE_CLASS_TEXT
                } else {
                    etCondValue.hint = "Hodnota podmínky"
                    etCondValue.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        actionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val key = actionLabels[pos].second
                etActionValue.visibility =
                    if (key == "DRIVING_MODE" || key == "PARKED_MODE") View.GONE else View.VISIBLE
                when (key) {
                    "NOTIFICATION" -> {
                        etActionValue.hint = "Nadpis|Text zprávy"
                        etActionValue.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    }
                    "BRIGHTNESS" -> {
                        etActionValue.hint = "Jas (0–255)"
                        etActionValue.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }
                    "APP" -> {
                        etActionValue.hint = "Package name (com.example.app)"
                        etActionValue.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    }
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        layout.addView(label("Název"))
        layout.addView(etName)
        layout.addView(label("Podmínka"))
        layout.addView(condSpinner)
        layout.addView(etCondValue)
        layout.addView(label("Akce"))
        layout.addView(actionSpinner)
        layout.addView(etActionValue)

        AlertDialog.Builder(ctx)
            .setTitle("Nové pravidlo")
            .setView(layout)
            .setPositiveButton("Přidat") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                val condKey = conditionLabels[condSpinner.selectedItemPosition].second
                val condVal = etCondValue.text.toString().trim()
                val actionKey = actionLabels[actionSpinner.selectedItemPosition].second
                val actionVal = etActionValue.text.toString().trim()
                val condition = buildCondition(condKey, condVal) ?: return@setPositiveButton
                val action = buildAction(actionKey, actionVal) ?: return@setPositiveButton
                val id = "custom_${System.currentTimeMillis()}"
                automationEngine.addRule(AutomationRule(id, name, condition, action))
                saveCustomRule(id, name, condKey, condVal, actionKey, actionVal)
                refreshAutomationRules()
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    private fun buildCondition(type: String, value: String): AutoCondition? = runCatching {
        when (type) {
            "SPEED_ABOVE"  -> AutoCondition.SpeedAbove(value.toFloat())
            "SPEED_BELOW"  -> AutoCondition.SpeedBelow(value.toFloat())
            "COOLANT_ABOVE"-> AutoCondition.CoolantTempAbove(value.toFloat())
            "VOLTAGE_BELOW"-> AutoCondition.VoltageBelow(value.toFloat())
            "DPF_ABOVE"    -> AutoCondition.DPFLoadAbove(value.toFloat())
            "ACC_ON"       -> AutoCondition.ACCOn
            "ACC_OFF"      -> AutoCondition.ACCOff
            "TIME_OF_DAY"  -> {
                val parts = value.split(":")
                AutoCondition.TimeOfDay(parts[0].toInt(), parts[1].toInt())
            }
            else -> null
        }
    }.getOrNull()

    private fun buildAction(type: String, value: String): AutoAction? = runCatching {
        when (type) {
            "NOTIFICATION" -> {
                val parts = value.split("|", limit = 2)
                AutoAction.ShowNotification(parts[0].trim(), parts.getOrElse(1) { "" }.trim())
            }
            "BRIGHTNESS"   -> AutoAction.SetBrightness(value.toInt().coerceIn(0, 255))
            "APP"          -> AutoAction.LaunchApp(value.trim())
            "DRIVING_MODE" -> AutoAction.SetDrivingMode
            "PARKED_MODE"  -> AutoAction.SetParkedMode
            else -> null
        }
    }.getOrNull()

    private fun saveCustomRule(id: String, name: String, condKey: String, condVal: String,
                                actionKey: String, actionVal: String) {
        val current = prefs.getString("custom_rules", "") ?: ""
        val entry = "$id\t$name\t$condKey\t$condVal\t$actionKey\t$actionVal"
        val updated = if (current.isBlank()) entry else "$current\n$entry"
        prefs.edit().putString("custom_rules", updated).apply()
    }

    private fun removeCustomRule(id: String) {
        val current = prefs.getString("custom_rules", "") ?: ""
        val filtered = current.lines().filter { it.isNotBlank() && !it.startsWith("$id\t") }.joinToString("\n")
        prefs.edit().putString("custom_rules", filtered).apply()
    }

    private fun loadCustomRules() {
        val saved = prefs.getString("custom_rules", "") ?: ""
        if (saved.isBlank()) return
        saved.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split("\t")
            if (parts.size < 6) return@forEach
            val id = parts[0]; val name = parts[1]; val condKey = parts[2]
            val condVal = parts[3]; val actionKey = parts[4]; val actionVal = parts[5]
            val condition = buildCondition(condKey, condVal) ?: return@forEach
            val action = buildAction(actionKey, actionVal) ?: return@forEach
            automationEngine.addRule(AutomationRule(id, name, condition, action))
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
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L
        val totalMb = runtime.maxMemory() / 1_048_576L

        binding.tvCPUUsage.text = try {
            val load = java.io.File("/proc/loadavg").readText().trim().split(" ")
            val tempStr = if (temp != null) "  Teplota: %.1f°C".format(temp) else ""
            "CPU: ${load[0]}$tempStr"
        } catch (e: Exception) {
            "CPU: N/A" + (if (temp != null) "  Teplota: %.1f°C".format(temp) else "")
        }

        binding.tvRAMUsage.text = "RAM: ${usedMb} / ${totalMb} MB"
        binding.tvBuildInfo.text = "Build: ${android.os.Build.DISPLAY}"
    }

    private fun refreshNetworkInfo() {
        val ssid = systemSettings.getWifiSsid()
        val ip = systemSettings.getWifiIpAddress()
        binding.tvCurrentSSID.text = ssid ?: "Nepřipojeno"
        binding.tvIP.text = ip ?: ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
