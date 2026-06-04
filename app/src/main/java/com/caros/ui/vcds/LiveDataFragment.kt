package com.caros.ui.vcds

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.caros.R
import com.caros.can.CANFrame
import com.caros.databinding.FragmentVcdsLiveDataBinding
import com.caros.ui.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LiveDataFragment : Fragment() {

    private var _binding: FragmentVcdsLiveDataBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()
    private val adapter = LiveDataAdapter()

    private data class LiveDataRow(val label: String, val value: String, val unit: String)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVcdsLiveDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.liveDataRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.liveDataRecyclerView.adapter = adapter

        startLiveUpdates()

        binding.toggleLiveStream.setOnClickListener {
            // Toggle handled via coroutine running; button is informational
        }
        binding.refreshLiveData.setOnClickListener {
            updateFromFrame(mainViewModel.canFrame.value)
        }
    }

    private fun startLiveUpdates() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                updateFromFrame(mainViewModel.canFrame.value)
                delay(500L)
            }
        }
    }

    private fun updateFromFrame(frame: CANFrame) {
        val rows = buildRows(frame)
        adapter.submitList(rows)
    }

    private fun buildRows(frame: CANFrame): List<LiveDataRow> = listOf(
        LiveDataRow("RPM", frame.engineRpm?.rpm?.toString() ?: "--", "rpm"),
        LiveDataRow("Rychlost", "%.0f".format(frame.vehicleSpeed?.kmh ?: 0f), "km/h"),
        LiveDataRow("Chladivo", "%.1f".format(frame.coolantTemp?.celsius ?: 0f), "°C"),
        LiveDataRow("MAF", frame.mafRate?.gramsPerSecond?.let { "%.1f".format(it) } ?: "--", "g/s"),
        LiveDataRow("Boost", "%.1f".format(frame.boostPressure?.kPa ?: 0f), "kPa"),
        LiveDataRow("Škrtící klapka", "%.1f".format(frame.throttlePosition?.percent ?: 0f), "%"),
        LiveDataRow("Napětí", "%.2f".format(frame.batteryVoltage?.volts ?: 0f), "V"),
        LiveDataRow("DPF zátěž", "%.0f".format(frame.dpfData?.loadPercent ?: 0f), "%"),
        LiveDataRow("Řazení", frame.dsgData?.gear ?: "--", ""),
        LiveDataRow("Trim paliva (K)", "%.2f".format(frame.fuelTrim?.shortTerm ?: 0f), "%"),
        LiveDataRow("Trim paliva (D)", "%.2f".format(frame.fuelTrim?.longTerm ?: 0f), "%"),
        LiveDataRow("Olej", frame.dsgData?.oilTemp?.let { "%.1f".format(it) } ?: "--", "°C")
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── LiveDataAdapter ───────────────────────────────────────────────────────

    private class LiveDataAdapter :
        RecyclerView.Adapter<LiveDataAdapter.ViewHolder>() {

        private var items: List<LiveDataRow> = emptyList()

        fun submitList(list: List<LiveDataRow>) {
            items = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLabel: TextView = view.findViewById(R.id.tvLiveLabel)
            val tvValue: TextView = view.findViewById(R.id.tvLiveValue)
            val tvUnit: TextView = view.findViewById(R.id.tvLiveUnit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_live_data_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = items[position]
            holder.tvLabel.text = row.label
            holder.tvValue.text = row.value
            holder.tvUnit.text = row.unit
        }

        override fun getItemCount(): Int = items.size
    }
}
