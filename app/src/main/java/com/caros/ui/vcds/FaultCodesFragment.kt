package com.caros.ui.vcds

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.caros.can.DTCCode
import com.caros.databinding.FragmentVcdsFaultCodesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class FaultCodesFragment : Fragment() {

    private var _binding: FragmentVcdsFaultCodesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: VCDSViewModel by activityViewModels()
    private val adapter = DTCAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVcdsFaultCodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
        setupButtons()
    }

    private fun setupRecyclerView() {
        binding.dtcRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.dtcRecyclerView.adapter = adapter
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanResults.collect { results ->
                val allDtcs = results.flatMap { scanResult ->
                    scanResult.dtcs.map { dtc -> dtc to scanResult.ecuName }
                }
                adapter.submitList(allDtcs)
                val total = results.sumOf { it.dtcs.size }
                binding.dtcTotalCount.text = "DTCs: $total"
                binding.lastScanTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isScanning.collect { scanning ->
                binding.scanProgressBar.visibility = if (scanning) View.VISIBLE else View.GONE
                binding.btnScanAllEcus.isEnabled = !scanning
                binding.scanStatusText.text = if (scanning) "Skenování..." else "Připraven"
            }
        }
    }

    private fun setupButtons() {
        binding.btnScanAllEcus.setOnClickListener {
            viewModel.scanAll()
        }

        binding.btnClearAllDtcs.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Smazat všechny DTCs")
                .setMessage("Opravdu chcete smazat všechny chybové kódy ze všech ECU?")
                .setPositiveButton("Smazat") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        com.caros.vcds.ECUDatabase.LEON_5F_ECUS.forEach { ecu ->
                            viewModel.clearAll(ecu.address)
                        }
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }

    }

    private fun showFreezeFrameDialog(dtc: com.caros.can.DTCCode, ecuAddress: Int) {
        viewModel.loadFreezeFrame(ecuAddress, dtc)
        val loadingDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Freeze Frame: ${dtc.code}")
            .setMessage("Načítám data...")
            .setCancelable(true)
            .create()
        loadingDialog.show()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.freezeFrame.collect { ff ->
                if (ff != null) {
                    loadingDialog.dismiss()
                    val msg = if (ff.parameters.isEmpty()) "Žádná data" else
                        ff.parameters.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Freeze Frame: ${dtc.code}")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                    cancel()
                }
            }
        }
    }

    private fun exportDtcReport() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val dir = File("/sdcard/CarOS/dtc_reports")
                    dir.mkdirs()
                    val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(dir, "dtc_$date.txt")
                    val sb = StringBuilder()
                    sb.appendLine("CarOS DTC Report — $date")
                    sb.appendLine("=".repeat(40))
                    viewModel.scanResults.value.forEach { result ->
                        sb.appendLine("\nECU: ${result.ecuName} (0x%02X)".format(result.ecuAddress))
                        if (result.error != null) {
                            sb.appendLine("  Error: ${result.error}")
                        } else if (result.dtcs.isEmpty()) {
                            sb.appendLine("  No faults")
                        } else {
                            result.dtcs.forEach { dtc ->
                                sb.appendLine("  [${dtc.status}] ${dtc.code} — ${dtc.description}")
                            }
                        }
                    }
                    file.writeText(sb.toString())
                    Timber.i("FaultCodesFragment: DTC report written to ${file.absolutePath}")
                } catch (e: Exception) {
                    Timber.e(e, "FaultCodesFragment: export failed")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── DTCAdapter ────────────────────────────────────────────────────────────

    private inner class DTCAdapter :
        ListAdapter<Pair<DTCCode, String>, DTCAdapter.DTCViewHolder>(
            object : DiffUtil.ItemCallback<Pair<DTCCode, String>>() {
                override fun areItemsTheSame(a: Pair<DTCCode, String>, b: Pair<DTCCode, String>) = a.first.code == b.first.code
                override fun areContentsTheSame(a: Pair<DTCCode, String>, b: Pair<DTCCode, String>) = a == b
            }
        ) {

        inner class DTCViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvCode: TextView = view.findViewById(R.id.tvDTCCode)
            val tvDesc: TextView = view.findViewById(R.id.tvDTCDescription)
            val tvStatus: TextView = view.findViewById(R.id.tvDTCStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DTCViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dtc, parent, false)
            return DTCViewHolder(view)
        }

        override fun onBindViewHolder(holder: DTCViewHolder, position: Int) {
            val (dtc, ecuName) = getItem(position)
            holder.tvCode.text = dtc.code
            holder.tvDesc.text = "${dtc.description} [$ecuName]"
            holder.tvStatus.text = dtc.status
            val color = when (dtc.status.uppercase()) {
                "ACTIVE"  -> android.graphics.Color.parseColor("#F44336")
                "PENDING" -> android.graphics.Color.parseColor("#FF9800")
                else      -> android.graphics.Color.parseColor("#757575")
            }
            holder.tvStatus.setBackgroundColor(color)
            holder.itemView.setOnClickListener {
                val (dtc, ecuName) = getItem(holder.adapterPosition)
                // Find ECU address from name
                val ecuAddress = com.caros.vcds.ECUDatabase.LEON_5F_ECUS
                    .firstOrNull { it.name == ecuName }?.address ?: 0x01
                showFreezeFrameDialog(dtc, ecuAddress)
            }
        }

    }
}
