package com.caros.ui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.caros.databinding.FragmentAudioViperBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ViperFragment : Fragment() {
    private var _binding: FragmentAudioViperBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AudioViewModel by viewModels({ requireParentFragment() })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentAudioViperBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!viewModel.viperInstalled) {
            binding.tvViperStatus.text = viewModel.getInstallInstructions()
            binding.layoutViperControls.visibility = View.GONE
        } else {
            binding.tvViperStatus.text = "ViPER4Android aktivní"
            binding.layoutViperControls.visibility = View.VISIBLE
        }
        binding.switchViperMaster.setOnCheckedChangeListener { _, checked ->
            viewModel.setViperEnabled(checked)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
