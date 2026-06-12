package com.caros.ui.service

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.caros.databinding.FragmentServiceAdvisorBinding
import com.caros.databinding.ItemServiceBinding
import com.caros.service.ServiceItem
import com.caros.service.ServiceUrgency
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ServiceAdvisorFragment : Fragment() {
    private var _binding: FragmentServiceAdvisorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ServiceAdvisorViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentServiceAdvisorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = ServiceItemAdapter()
        binding.rvServiceItems.layoutManager = LinearLayoutManager(context)
        binding.rvServiceItems.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.serviceItems.collectLatest { items ->
                    adapter.submitList(items)
                    val okCount = items.count { it.status == ServiceUrgency.OK }
                    val healthPct = ((okCount.toFloat() / items.size.coerceAtLeast(1)) * 100).toInt()
                    binding.tvHealthScore.text = "$healthPct%"
                    binding.tvHealthScore.setTextColor(
                        when { healthPct >= 80 -> 0xFF2E7D32.toInt(); healthPct >= 50 -> 0xFFF9A825.toInt(); else -> 0xFFC62828.toInt() }
                    )
                }
            }
        }
        viewModel.loadItems(currentKm = 0)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class ServiceItemAdapter : ListAdapter<ServiceItem, ServiceItemAdapter.VH>(DIFF) {
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ServiceItem>() {
            override fun areItemsTheSame(oldItem: ServiceItem, newItem: ServiceItem) =
                oldItem.type == newItem.type
            override fun areContentsTheSame(oldItem: ServiceItem, newItem: ServiceItem) =
                oldItem == newItem
        }
    }

    inner class VH(val binding: ItemServiceBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(ItemServiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, pos: Int) {
        val item = getItem(pos)
        holder.binding.tvServiceName.text = item.type.name.replace('_', ' ')
        holder.binding.tvServiceStatus.text = item.currentValueStr
        holder.binding.tvDueKm.text = item.dueInKm?.let { "${it} km" } ?: ""
        holder.binding.tvDueDays.text = item.dueInDays?.let { "${it} dní" } ?: ""
        val color = when (item.status) {
            ServiceUrgency.OK -> 0xFF2E7D32.toInt()
            ServiceUrgency.INFO -> 0xFF1565C0.toInt()
            ServiceUrgency.WARNING -> 0xFFF9A825.toInt()
            ServiceUrgency.URGENT -> 0xFFC62828.toInt()
        }
        holder.binding.viewColorStrip.setBackgroundColor(color)
        holder.binding.root.setOnClickListener {
            Toast.makeText(holder.itemView.context, item.recommendation, Toast.LENGTH_LONG).show()
        }
    }
}
