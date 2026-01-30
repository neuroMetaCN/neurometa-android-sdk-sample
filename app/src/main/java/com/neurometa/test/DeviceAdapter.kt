package com.neurometa.test

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.neurometa.sdk.model.Device
import com.neurometa.test.databinding.ItemDeviceBinding

/**
 * 设备列表适配器
 * 支持显示设备连接状态 (CONNECTED/AVAILABLE)
 */
class DeviceAdapter(
    private val onDeviceClick: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<Device>()
    private var connectedDeviceId: String? = null

    fun updateDevices(newDevices: List<Device>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    fun addDevice(device: Device) {
        val index = devices.indexOfFirst { it.id == device.id }
        if (index >= 0) {
            devices[index] = device
            notifyItemChanged(index)
        } else {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    fun clear() {
        devices.clear()
        notifyDataSetChanged()
    }

    fun getDeviceCount(): Int = devices.size

    /**
     * 设置已连接的设备 ID
     */
    fun setConnectedDevice(deviceId: String?) {
        connectedDeviceId = deviceId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            binding.tvDeviceName.text = device.name ?: "Unknown Device"
            binding.tvDeviceId.text = device.id
            
            // 显示信号强度
            device.rssi?.let { rssi ->
                binding.tvRssi.text = "$rssi dBm"
            } ?: run {
                binding.tvRssi.text = "-- dBm"
            }
            
            // 设置连接状态
            val isConnected = device.id == connectedDeviceId
            if (isConnected) {
                binding.tvStatus.text = "CONNECTED"
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.status_connected)
                )
            } else {
                binding.tvStatus.text = "AVAILABLE"
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.status_available)
                )
            }
            
            binding.root.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }
}
