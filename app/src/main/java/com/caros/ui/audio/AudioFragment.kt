package com.caros.ui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.caros.audio.AudioProfile
import com.caros.databinding.FragmentAudioBinding
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AudioFragment : Fragment() {
    private var _binding: FragmentAudioBinding? = null
    private val binding get() = _binding!!

    private val audioViewModel: AudioViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        saved: Bundle?
    ): View {
        _binding = FragmentAudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ViewPager + tabs
        binding.viewPager.adapter = AudioPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) { 0 -> "V4A"; 1 -> "EQ"; 2 -> "Profily"; else -> "Zdroj" }
        }.attach()

        // Backend badge
        binding.tvBackendBadge.text = audioViewModel.engineManager.backendBadgeText()

        // Profile card click listeners
        val profileCards: List<Pair<CardView, AudioProfile>> = listOf(
            binding.cardProfileFlat  to AudioProfile.FLAT,
            binding.cardProfileBass  to AudioProfile.BASS_PLUS,
            binding.cardProfileVocal to AudioProfile.VOCAL,
            binding.cardProfileStage to AudioProfile.STAGE,
            binding.cardProfileNight to AudioProfile.NIGHT,
            binding.cardProfileSport to AudioProfile.SPORT
        )
        profileCards.forEach { (card, profile) ->
            card.setOnClickListener { audioViewModel.setProfile(profile) }
        }

        // Observe active profile to highlight the corresponding card
        viewLifecycleOwner.lifecycleScope.launch {
            audioViewModel.currentProfile.collect { active ->
                profileCards.forEach { (card, profile) ->
                    card.setCardBackgroundColor(
                        if (profile.id == active.id) 0xFF1565C0.toInt() else 0xFF1E1E1E.toInt()
                    )
                }
            }
        }

        // Auto-EQ banner: observe enabled state
        viewLifecycleOwner.lifecycleScope.launch {
            audioViewModel.autoEQEnabled.collect { enabled ->
                updateAutoEqBanner(enabled)
            }
        }

        // Banner toggle button
        binding.btnAutoEqToggle.setOnClickListener {
            audioViewModel.toggleAutoEQ()
        }
    }

    private fun updateAutoEqBanner(enabled: Boolean) {
        if (enabled) {
            binding.autoEqBanner.setBackgroundColor(0xFF1B5E20.toInt())
            binding.tvAutoEqStatus.text = "Auto EQ: Aktivní"
            binding.btnAutoEqToggle.text = "Vypnout"
        } else {
            binding.autoEqBanner.setBackgroundColor(0xFF2A2A2A.toInt())
            binding.tvAutoEqStatus.text = "Auto EQ: Vypnuto"
            binding.btnAutoEqToggle.text = "Zapnout"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class AudioPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = 4
    override fun createFragment(pos: Int): Fragment = when (pos) {
        0 -> ViperFragment()
        1 -> EQFragment()
        2 -> AudioProfilesFragment()
        else -> AudioSourceFragment()
    }
}
