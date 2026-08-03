package com.example.encoder

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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = "${item.name}  (${item.rssi} dBm)"
        holder.tvAddress.text = item.address
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun addOrUpdate(device: FoundDevice) {
        val index = items.indexOfFirst { it.address == device.address }
        if (index >= 0) {
            items[index] = device
            notifyItemChanged(index)
        } else {
            items.add(device)
            notifyItemInserted(items.size - 1)
        }
    }
}