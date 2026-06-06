package com.caros.ui.voice

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caros.databinding.FragmentVoiceSetupBinding
import com.caros.voice.GeminiCommandProcessor
import com.caros.voice.SteeringWheelButtonDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VoiceSetupFragment : Fragment() {
    private var _binding: FragmentVoiceSetupBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var gemini: GeminiCommandProcessor
    @Inject lateinit var steeringDetector: SteeringWheelButtonDetector

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentVoiceSetupBinding.inflate(i, c, false); return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAPIKeyCard()
        setupSteeringButtonCard()
        setupHistoryCard()
    }

    private fun setupAPIKeyCard() {
        binding.btnTestApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isBlank()) { Toast.makeText(context, "Zadej API klíč", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            binding.tvApiKeyStatus.text = "Testuji..."
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = gemini.testApiKey(key)
                if (ok) {
                    gemini.setApiKey(key)
                    binding.tvApiKeyStatus.text = "✅ Klíč funguje"
                    binding.tvApiKeyStatus.setTextColor(0xFF2E7D32.toInt())
                } else {
                    binding.tvApiKeyStatus.text = "❌ Klíč nefunguje"
                    binding.tvApiKeyStatus.setTextColor(0xFFC62828.toInt())
                }
            }
        }
    }

    private fun setupSteeringButtonCard() {
        val kc = steeringDetector.getSavedKeyCode()
        binding.tvCurrentKeyCode.text = if (kc != -1) "Nastaveno: KeyCode $kc" else "Není nastaveno"
        binding.btnCalibrateButton.setOnClickListener {
            binding.tvCalibrateStatus.text = "Stiskni tlačítko na volantu do 10 sekund..."
            binding.btnCalibrateButton.isEnabled = false
            binding.countdownArc.startCountdown(10_000L, onExpired = {
                binding.tvCalibrateStatus.text = "⏱ Vypršel čas — zkus znovu"
                binding.tvCalibrateStatus.setTextColor(0xFFC62828.toInt())
                binding.btnCalibrateButton.isEnabled = true
            })
            steeringDetector.startCalibration { keyCode ->
                binding.countdownArc.stopCountdown()
                binding.tvCurrentKeyCode.text = "Nastaveno: KeyCode $keyCode"
                binding.tvCalibrateStatus.text = "✅ Nastaveno"
                binding.tvCalibrateStatus.setTextColor(0xFF2E7D32.toInt())
                binding.btnCalibrateButton.isEnabled = true
            }
        }
    }

    private fun setupHistoryCard() {
        // Show last 10 commands from SharedPreferences
        binding.btnClearHistory.setOnClickListener {
            binding.tvCommandHistory.text = "Historie vymazána"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
