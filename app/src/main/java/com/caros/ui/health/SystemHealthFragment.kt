package com.caros.ui.health

// ─────────────────────────────────────────────────────────────────────────────
//  SystemHealthFragment.kt — Self-diagnostic dashboard for CarOS itself
//
//  Shows (without needing ADB):
//    • CPU usage + SoC temperature, system RAM, app heap, eMMC writes
//    • Crash count over the last 7 days (from /sdcard/CarOS/crashreports)
//    • Watchdog restart count this session
//    • Live module list from ServiceHealthMonitor with per-module Restart button
// ─────────────────────────────────────────────────────────────────────────────

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caros.core.ModuleHealth
import com.caros.core.ServiceHealthMonitor
import com.caros.databinding.FragmentSystemHealthBinding
import com.caros.system.SystemSettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SystemHealthFragment : Fragment() {

    private var _binding: FragmentSystemHealthBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var healthMonitor: ServiceHealthMonitor
    @Inject lateinit var systemSettings: SystemSettingsManager

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSystemHealthBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startStatsRefresh()
        observeModules()
    }

    // ── System stats (CPU / RAM / disk / crashes) ─────────────────────────────

    private fun startStatsRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshStats()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun refreshStats() {
        val cpuPct = systemSettings.getCPUUsage()
        val cpuTemp = systemSettings.getCPUTemp()
        binding.tvHealthCPU.text =
            "CPU: %.0f %%   Teplota: %.1f °C".format(cpuPct, cpuTemp)

        val (availBytes, totalBytes) = systemSettings.getRAMInfo()
        binding.tvHealthRAM.text = "RAM systém: %d / %d MB volné".format(
            availBytes / MB, totalBytes / MB
        )

        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapMaxMb = runtime.maxMemory() / MB
        binding.tvHealthAppRAM.text = "RAM aplikace: $heapUsedMb / $heapMaxMb MB"

        binding.tvHealthDiskWrites.text =
            "Zápisy na disk (od startu): ${readProcessWriteBytes() / MB} MB"

        val crashes = countRecentCrashes()
        binding.tvHealthCrashes.text = "Pády za 7 dní: $crashes"
        binding.tvHealthCrashes.setTextColor(
            if (crashes == 0) Color.parseColor("#2E7D32") else Color.parseColor("#E65100")
        )

        val restarts = healthMonitor.watchdogRestartTotal.value
        binding.tvHealthWatchdog.text = "Watchdog restarty (session): $restarts"
        binding.tvHealthWatchdog.setTextColor(
            if (restarts == 0) Color.parseColor("#2E7D32") else Color.parseColor("#F9A825")
        )
    }

    /** Bytes this process has written to storage, from /proc/self/io. */
    private fun readProcessWriteBytes(): Long = runCatching {
        File("/proc/self/io").readLines()
            .firstOrNull { it.startsWith("write_bytes:") }
            ?.substringAfter(':')?.trim()?.toLong() ?: 0L
    }.getOrDefault(0L)

    /** Number of crash report files younger than 7 days. */
    private fun countRecentCrashes(): Int = runCatching {
        val cutoff = System.currentTimeMillis() - CRASH_WINDOW_MS
        File("/sdcard/CarOS/crashreports").listFiles()
            ?.count { it.lastModified() > cutoff } ?: 0
    }.getOrDefault(0)

    // ── Module list ───────────────────────────────────────────────────────────

    private fun observeModules() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                healthMonitor.modules.collectLatest { modules ->
                    renderModules(modules.values.sortedBy { it.name })
                }
            }
        }
    }

    private fun renderModules(modules: List<ModuleHealth>) {
        val container = binding.moduleContainer
        container.removeAllViews()

        if (modules.isEmpty()) {
            container.addView(binding.tvNoModules.also { (it.parent as? ViewGroup)?.removeView(it) })
            return
        }

        val now = System.currentTimeMillis()
        modules.forEach { module ->
            container.addView(buildModuleRow(module, now))
        }
    }

    private fun buildModuleRow(module: ModuleHealth, nowMs: Long): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val alive = module.isAlive(nowMs)
        val ageSec = (nowMs - module.lastHeartbeatMs) / 1000
        val statusText = buildString {
            append(module.name)
            append("   ")
            append(if (alive) "● Běží" else "○ Neodpovídá (${ageSec}s)")
            if (module.restartCount > 0) append("   ⟳ ${module.restartCount}×")
        }

        row.addView(TextView(ctx).apply {
            text = statusText
            textSize = 13f
            setTextColor(
                if (alive) Color.parseColor("#2E7D32") else Color.parseColor("#E65100")
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (module.canRestart) {
            row.addView(Button(ctx).apply {
                text = "Restart"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#1565C0"))
                setTextColor(Color.WHITE)
                setOnClickListener { healthMonitor.restartModule(module.name) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 3_000L
        const val CRASH_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val MB = 1_048_576L
    }
}
