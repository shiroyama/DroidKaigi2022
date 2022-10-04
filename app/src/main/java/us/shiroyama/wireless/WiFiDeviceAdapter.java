package us.shiroyama.wireless;

import android.net.wifi.p2p.WifiP2pDevice;

import androidx.annotation.NonNull;

import java.util.List;

public class WiFiDeviceAdapter extends DeviceAdapter<WifiP2pDevice> {
    public WiFiDeviceAdapter(@NonNull List<WifiP2pDevice> deviceList) {
        super(deviceList);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        WifiP2pDevice device = deviceList.get(position);
        String name = device.deviceName == null ? "-------" : device.deviceName;
        String type = "Wi-Fi";
        holder.textViewDevice.setText(name);
        holder.textViewAddress.setText(device.deviceAddress);
        holder.textViewType.setText(type);
    }
}
