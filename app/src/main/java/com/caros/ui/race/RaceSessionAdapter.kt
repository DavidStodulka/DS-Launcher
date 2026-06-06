package com.caros.ui.race

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.caros.databinding.ItemRaceSessionBinding
import com.caros.db.RaceSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RaceSessionAdapter : ListAdapter<RaceSessionEntity, RaceSessionAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemRaceSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRaceSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val fmt = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
        holder.binding.tvType.text = item.measurementType
        holder.binding.tvResult.text = "%.3f s".format(item.resultSeconds)
        holder.binding.tvDate.text = fmt.format(Date(item.date))
        holder.binding.tvMaxSpeed.text = item.maxSpeedKmh?.let { "%.0f km/h".format(it) } ?: "--"
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RaceSessionEntity>() {
            override fun areItemsTheSame(a: RaceSessionEntity, b: RaceSessionEntity) = a.id == b.id
            override fun areContentsTheSame(a: RaceSessionEntity, b: RaceSessionEntity) = a == b
        }
    }
}
