package com.example.encoder

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val onClick: (FoundDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val items = mutableListOf<FoundDevice>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvAddress: TextView = view.findViewById(R.id.tvAddress)
        val tvRssi: TextView = view.findViewById(R.id.tvRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvAddress.text = item.address
        holder.tvRssi.text = "${item.rssi} dBm"
        holder.tvRssi.setTextColor(rssiColor(item.rssi))
        holder.itemView.setOnClickListener { onClick(item) }
    }

    /** Ближе к нулю — сильнее сигнал. */
    private fun rssiColor(rssi: Int): Int = when {
        rssi >= -60 -> Color.parseColor("#4CAF50")   // отличный
        rssi >= -75 -> Color.parseColor("#FFC107")   // средний
        else -> Color.parseColor("#F44336")          // слабый
    }

    override fun getItemCount() = items.size

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    /**
     * Добавляет новое устройство или обновляет уже найденное,
     * после чего пересортировывает список по убыванию силы сигнала.
     */
    fun addOrUpdate(device: FoundDevice) {
        val index = items.indexOfFirst { it.address == device.address }
        if (index >= 0) {
            items[index] = device
        } else {
            items.add(device)
        }
        // RSSI отрицательный: -50 сильнее, чем -90, поэтому сортируем по убыванию
        items.sortByDescending { it.rssi }
        notifyDataSetChanged()
    }
}