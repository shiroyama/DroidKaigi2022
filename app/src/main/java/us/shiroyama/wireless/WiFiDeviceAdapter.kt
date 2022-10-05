package us.shiroyama.wireless

import android.net.wifi.p2p.WifiP2pDevice

class WiFiDeviceAdapter(deviceList: List<WifiP2pDevice>) :
    DeviceAdapter<WifiP2pDevice>(deviceList) {
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = deviceList[position]
        val name = if (device.deviceName == null) "-------" else device.deviceName
        val type = "Wi-Fi"
        holder.textViewDevice.text = name
        holder.textViewAddress.text = device.deviceAddress
        holder.textViewType.text = type
    }
}