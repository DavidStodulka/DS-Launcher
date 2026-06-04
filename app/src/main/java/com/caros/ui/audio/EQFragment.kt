package com.caros.ui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.caros.R
import com.caros.audio.AudioProfile
import com.caros.databinding.FragmentAudioEqBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EQFragment : Fragment() {

    private var _binding: FragmentAudioEqBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AudioViewModel by viewModels()

    // SeekBars: bands 1–10 (index 0–9)
    private val seekBars: Array<SeekBar?> by lazy {
        arrayOf(
            binding.eqBand1, binding.eqBand2, binding.eqBand3, binding.eqBand4, binding.eqBand5,
            binding.eqBand6, binding.eqBand7, binding.eqBand8, binding.eqBand9, binding.eqBand10
        )
    }
    private val dbLabels: Array<TextView?> by lazy {
        arrayOf(
            binding.eqBand1Db, binding.eqBand2Db, binding.eqBand3Db, binding.eqBand4Db,
            binding.eqBand5Db, binding.eqBand6Db, binding.eqBand7Db, binding.eqBand8Db,
            binding.eqBand9Db, binding.eqBand10Db
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioEqBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSeekBars()
        observeActiveProfile()
        setupPresetButtons()
    }

    private fun setupSeekBars() {
        seekBars.forEachIndexed { index, seekBar ->
            seekBar?.max = 240  // 0–240 maps to -12..+12 dB with 0.1 dB steps
            seekBar?.progress = 120  // 0 dB default
            seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val db = (progress - 120) / 10f  // -12.0 to +12.0 dB
                    viewModel.setBand(index, db)
                    dbLabels[index]?.text = "%+.1f".format(db)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun observeActiveProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeProfile.collect { profile ->
                applyProfileToUI(profile)
            }
        }
    }

    private fun applyProfileToUI(profile: AudioProfile) {
        profile.eqBands.forEachIndexed { index, db ->
            if (index < seekBars.size) {
                val progress = ((db * 10) + 120).toInt().coerceIn(0, 240)
                seekBars[index]?.progress = progress
                dbLabels[index]?.text = "%+.1f".format(db)
            }
        }
    }

    private fun setupPresetButtons() {
        binding.eqPresetFlat.setOnClickListener {
            viewModel.applyProfile(AudioProfile.FLAT)
        }
        binding.eqPresetBass.setOnClickListener {
            viewModel.applyProfile(AudioProfile.BASS_BOOST)
        }
        binding.eqPresetVocal.setOnClickListener {
            viewModel.applyProfile(AudioProfile.VOCAL)
        }
        binding.eqPresetStage.setOnClickListener {
            viewModel.applyProfile(AudioProfile.STAGE)
        }
        binding.eqPresetNight.setOnClickListener {
            viewModel.applyProfile(AudioProfile.NIGHT)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
