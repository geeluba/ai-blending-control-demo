package com.example.remotecontrolprojector.pairing

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.remotecontrolprojector.databinding.ItemScanResultBinding

class ScannedDeviceAdapter(
    private val onItemClick: (ScanResult) -> Unit,
) : ListAdapter<ScanResult, ScannedDeviceAdapter.ViewHolder>(DiffCallback) {

    // Tracks which MAC address is assigned to which role
    var leftDeviceAddress: String? = null
    var rightDeviceAddress: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScanResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemScanResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("MissingPermission")
        fun bind(result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: result.device.name
                ?: "Unknown Device"
            val address = result.device.address
            val rssi = result.rssi

            binding.deviceAddress.text = address

            // Visual feedback logic
            when (address) {
                leftDeviceAddress -> {
                    binding.deviceName.text = "$name (LEFT)"
                    binding.deviceName.setTextColor(Color.BLUE)
                    binding.roleIndicator.text = "L"
                    binding.roleIndicator.isVisible = true
                    binding.roleIndicator.setBackgroundColor(Color.BLUE)
                }

                rightDeviceAddress -> {
                    binding.deviceName.text = "$name (RIGHT)"
                    binding.deviceName.setTextColor(Color.RED)
                    binding.roleIndicator.text = "R"
                    binding.roleIndicator.isVisible = true
                    binding.roleIndicator.setBackgroundColor(Color.RED)
                }

                else -> {
                    binding.deviceName.text = name
                    binding.deviceName.setTextColor(Color.BLACK)
                    binding.roleIndicator.isVisible = false
                }
            }

            binding.root.setOnClickListener {
                onItemClick(result)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ScanResult>() {
        override fun areItemsTheSame(oldItem: ScanResult, newItem: ScanResult): Boolean {
            return oldItem.device.address == newItem.device.address
        }

        @SuppressLint("MissingPermission")
        override fun areContentsTheSame(oldItem: ScanResult, newItem: ScanResult): Boolean {
            return oldItem.device.address == newItem.device.address &&
                    oldItem.device.name == newItem.device.name
        }
    }
}