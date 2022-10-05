package us.shiroyama.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

class BluetoothDeviceAdapter(deviceList: List<BluetoothDevice>) :
    DeviceAdapter<BluetoothDevice>(deviceList) {
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = deviceList[position]
        @SuppressLint("MissingPermission") val name =
            if (device.name == null) "-------" else device.name
        @SuppressLint("MissingPermission") val type = device.type
        var typeString = ""
        typeString = when (type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> {
                "Bluetooth Classic"
            }
            BluetoothDevice.DEVICE_TYPE_DUAL -> {
                "Bluetooth Dual"
            }
            BluetoothDevice.DEVICE_TYPE_LE -> {
                "Bluetooth Low Energy"
            }
            else -> {
                "Bluetooth Unknown"
            }
        }
        holder.textViewDevice.text = name
        holder.textViewAddress.text = device.address
        holder.textViewType.text = typeString
    }
}