package com.caros.ui.telemetry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentTelemetryBinding
import com.caros.db.CarOSDatabase
import com.caros.telemetry.DrivingStyleAnalyzer
import com.caros.telemetry.DrivingStyleScore
import com.caros.telemetry.TelemetryExporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TelemetryFragment : Fragment() {

    private var _binding: FragmentTelemetryBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var db: CarOSDatabase
    @Inject lateinit var drivingStyleAnalyzer: DrivingStyleAnalyzer
    @Inject lateinit var telemetryExporter: TelemetryExporter

    private var currentSessionId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTelemetryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadLastSession()
        setupExportButton()
    }

    private fun loadLastSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val session = db.telemetrySessionDao().getLatestSession()
                    if (session != null) {
                        currentSessionId = session.id

                        val durationSec = if (session.endTime != null)
                            (session.endTime - session.startTime) / 1_000L else 0L
                        val durationStr = "%02d:%02d".format(durationSec / 60, durationSec % 60)

                        val score = drivingStyleAnalyzer.analyzeSession(session.id)

                        withContext(Dispatchers.Main) {
                            updateUI(session, durationStr, score)
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "TelemetryFragment: failed to load session")
                }
            }
        }
    }

    private fun updateUI(
        session: com.caros.db.TelemetrySessionEntity,
        durationStr: String,
        score: DrivingStyleScore
    ) {
        // Session header
        val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        binding.tvSessionDate.text = dateFmt.format(java.util.Date(session.startTime))
        binding.tvSessionDuration.text = durationStr
        binding.tvSessionDistance.text = "%.1f km".format(session.distanceKm ?: 0.0)

        // Radar chart data
        binding.radarChartView.setScores(
            score.ecoScore,
            score.sportScore,
            score.mechanicalScore,
            score.smoothnessScore
        )

        // Score values
        binding.tvEcoScore.text = score.ecoScore.toString()
        binding.tvSportScore.text = score.sportScore.toString()
        binding.tvMechanicalScore.text = score.mechanicalScore.toString()
        binding.tvSmoothnessScore.text = score.smoothnessScore.toString()

        // Recommendations
        binding.tvRecommendations.text = score.recommendations.joinToString("\n\n• ", prefix = "• ")
    }

    private fun setupExportButton() {
        binding.btnExport.setOnClickListener {
            if (currentSessionId < 0) return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val file = telemetryExporter.exportToCSV(currentSessionId)
                        Timber.i("TelemetryFragment: exported to ${file.absolutePath}")
                    } catch (e: Exception) {
                        Timber.e(e, "TelemetryFragment: export failed")
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
