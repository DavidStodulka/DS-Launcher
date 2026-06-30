package com.caros.ui.vcds

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.caros.R
import com.caros.databinding.FragmentVcdsCodingPresetsBinding
import com.caros.vcds.ECUDatabase
import com.caros.vcds.PresetState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CodingPresetsFragment : Fragment() {

    private var _binding: FragmentVcdsCodingPresetsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: VCDSViewModel by activityViewModels()
    private val adapter = PresetAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVcdsCodingPresetsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.codingPresetsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.codingPresetsRecyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.presetStates.collect { states ->
                adapter.submitList(states)
            }
        }
    }

    private fun confirmAndToggle(preset: ECUDatabase.CodingPresetDef) {
        val message = preset.warningText
            ?: "Změna kódování ECU. Tato akce modifikuje nastavení řídící jednotky. Pokračovat?"
        AlertDialog.Builder(requireContext())
            .setTitle("Varování")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                viewModel.togglePreset(preset)
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── PresetAdapter ─────────────────────────────────────────────────────────

    private inner class PresetAdapter :
        ListAdapter<PresetState, PresetAdapter.PresetViewHolder>(
            object : DiffUtil.ItemCallback<PresetState>() {
                override fun areItemsTheSame(a: PresetState, b: PresetState) = a.preset.id == b.preset.id
                override fun areContentsTheSame(a: PresetState, b: PresetState) = a == b
            }
        ) {

        inner class PresetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvPresetName)
            val tvDesc: TextView = view.findViewById(R.id.tvPresetDesc)
            val tvCurrentStatus: TextView = view.findViewById(R.id.tvCurrentStatus)
            val switchToggle: Switch = view.findViewById(R.id.switchPreset)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_coding_preset, parent, false)
            return PresetViewHolder(view)
        }

        override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
            val state = getItem(position)
            holder.tvName.text = state.preset.nameCZ
            holder.tvDesc.text = state.preset.description
            holder.tvCurrentStatus.text = if (state.isEnabled) "ZAPNUTO" else "VYPNUTO"
            holder.tvCurrentStatus.setTextColor(
                if (state.isEnabled) android.graphics.Color.parseColor("#4CAF50")
                else android.graphics.Color.parseColor("#F44336")
            )
            // Set switch state without triggering listener
            holder.switchToggle.setOnCheckedChangeListener(null)
            holder.switchToggle.isChecked = state.isEnabled
            holder.switchToggle.setOnCheckedChangeListener { _, _ ->
                confirmAndToggle(state.preset)
            }
        }

    }
}
