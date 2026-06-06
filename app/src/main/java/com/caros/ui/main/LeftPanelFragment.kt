package com.caros.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.caros.R
import com.caros.databinding.FragmentLeftPanelBinding
import com.caros.multimedia.QuickLaunchManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LeftPanelFragment : Fragment() {

    private var _binding: FragmentLeftPanelBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var quickLaunch: QuickLaunchManager
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLeftPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshInstallStatus()
        setupClicks()
    }

    private fun refreshInstallStatus() {
        val status = quickLaunch.getInstallStatus()

        fun applyStatus(btn: android.widget.LinearLayout, pkg: String, label: String) {
            val tv = btn.getChildAt(1) as? android.widget.TextView
            if (status[pkg] == true) {
                tv?.text = label
                btn.isEnabled = true
                btn.alpha = 1.0f
            } else {
                tv?.text = "Není nainstalováno"
                btn.isEnabled = false
                btn.alpha = 0.4f
            }
        }

        applyStatus(binding.btnWaze,    QuickLaunchManager.PKG_WAZE,    "Waze")
        applyStatus(binding.btnSpotify, QuickLaunchManager.PKG_SPOTIFY,  "Spotify")
        applyStatus(binding.btnYoutube, QuickLaunchManager.PKG_YOUTUBE,  "YouTube")
    }

    private fun setupClicks() {
        binding.btnWaze.setOnClickListener {
            quickLaunch.launchWaze()
        }

        binding.btnSpotify.setOnClickListener {
            quickLaunch.launchSpotify()
        }

        binding.btnYoutube.setOnClickListener {
            quickLaunch.launchYouTube()
        }

        binding.btnPhone.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL))
        }

        binding.btnAudio.setOnClickListener {
            findNavController().navigate(R.id.audioFragment)
        }

        binding.btnFMRadio.setOnClickListener {
            findNavController().navigate(R.id.fmRadioFragment)
        }

        binding.btnClimate.setOnClickListener {
            findNavController().navigate(R.id.climateFragment)
        }

        // Short press = toggle voice listening; long press = open voice setup
        binding.btnVoice.setOnClickListener {
            mainViewModel.toggleVoiceListening()
        }
        binding.btnVoice.setOnLongClickListener {
            findNavController().navigate(R.id.voiceSetupFragment)
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
