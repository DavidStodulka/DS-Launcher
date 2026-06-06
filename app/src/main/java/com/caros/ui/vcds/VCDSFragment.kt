package com.caros.ui.vcds

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.caros.databinding.FragmentVcdsBinding
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VCDSFragment : Fragment() {
    private var _binding: FragmentVcdsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VCDSViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentVcdsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.viewPager.adapter = VCDSPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) { 0 -> "Live"; 1 -> "DTCs"; 2 -> "Coding"; else -> "Long Coding" }
        }.attach()
        // Auto-connect when fragment appears
        viewLifecycleOwner.lifecycleScope.launch { viewModel.connect() }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

private class VCDSPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = 4
    override fun createFragment(pos: Int): Fragment = when (pos) {
        0 -> LiveDataFragment()
        1 -> FaultCodesFragment()
        2 -> CodingPresetsFragment()
        else -> LongCodingFragment()
    }
}

