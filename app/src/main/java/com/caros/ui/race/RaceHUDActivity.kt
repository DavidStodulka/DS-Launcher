package com.caros.ui.race

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.ActivityRaceHudBinding
import com.caros.race.MeasurementState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class RaceHUDActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRaceHudBinding
    private val raceViewModel: RaceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRaceHudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Force landscape, fullscreen, keep screen on
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.hide(WindowInsets.Type.systemBars())

        setupObservers()
        setupExitButton()
    }

    private fun setupObservers() {
        // Measurement state — time display
        lifecycleScope.launch {
            raceViewModel.measurementState.collect { state ->
                when (state) {
                    MeasurementState.IDLE,
                    MeasurementState.WAITING_FOR_START -> {
                        binding.hudTimer.text = "00:00.000"
                    }
                    MeasurementState.COMPLETE -> {
                        raceViewModel.measurementResult.value?.let { result ->
                            binding.hudTimer.text = formatDuration(result.durationMs)
                        }
                    }
                    MeasurementState.FAILED -> {
                        binding.hudTimer.text = "ERR"
                    }
                    MeasurementState.MEASURING -> {
                        // Timer updated live in canFrame collector
                    }
                }
            }
        }

        // Live CAN data from shared ViewModel
        lifecycleScope.launch {
            raceViewModel.measurementResult.collect { result ->
                result?.let {
                    binding.hudTimer.text = formatDuration(it.durationMs)
                    binding.hudSpeed.text = "%.0f".format(it.maxSpeedKmh)
                }
            }
        }

        // RPM bar from main viewmodel via gForce fallback
        lifecycleScope.launch {
            raceViewModel.gForce.collect { gf ->
                // Display gForce lateral in rpmBar as a rough proxy when real rpm not wired here
                val normalizedLat = ((gf.lateral + 3f) / 6f * 100).toInt().coerceIn(0, 100)
                binding.rpmBar.progress = normalizedLat
            }
        }
    }

    private fun setupExitButton() {
        binding.hudExitButton.setOnClickListener {
            finish()
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1_000
        val millis  = ms % 1_000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
