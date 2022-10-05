package us.shiroyama.wireless;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;

import java.util.List;

public class BluetoothDeviceAdapter extends DeviceAdapter<BluetoothDevice> {
    public BluetoothDeviceAdapter(@NonNull List<BluetoothDevice> deviceList) {
        super(deviceList);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        BluetoothDevice device = deviceList.get(position);
        @SuppressLint("MissingPermission")
        String name = device.getName() == null ? "-------" : device.getName();
        @SuppressLint("MissingPermission")
        int type = device.getType();
        String typeString = "";
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: {
                typeString = "Bluetooth Classic";
                break;
            }
            case BluetoothDevice.DEVICE_TYPE_DUAL: {
                typeString = "Bluetooth Dual";
                break;
            }
            case BluetoothDevice.DEVICE_TYPE_LE: {
                typeString = "Bluetooth Low Energy";
                break;
            }
            default: {
                typeString = "Bluetooth Unknown";
                break;
            }
        }
        holder.textViewDevice.setText(name);
        holder.textViewAddress.setText(device.getAddress());
        holder.textViewType.setText(typeString);
    }
}
