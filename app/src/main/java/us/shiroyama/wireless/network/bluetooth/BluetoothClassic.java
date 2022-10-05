package us.shiroyama.wireless.network.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import us.shiroyama.wireless.network.LengthSocketReader;
import us.shiroyama.wireless.network.Lifecycle;
import us.shiroyama.wireless.network.SocketReader;
import us.shiroyama.wireless.network.WirelessNetwork;

public class BluetoothClassic implements WirelessNetwork<BluetoothDevice>, Lifecycle {
    private static final String TAG = BluetoothClassic.class.getSimpleName();

    private static final String BT_NAME = "BTTEST1";
    private static final UUID BT_UUID = UUID.fromString("3550d416-dd4c-20a1-cdc7-67975a85b3f0");
    private static final int DISCOVERABLE_DURATION = 300;

    @NonNull
    private final Context context;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothBroadcastReceiver bluetoothBroadcastReceiver;
    private ScanModeBroadcastReceiver scanModeBroadcastReceiver;
    private BTServerThread btServerThread;
    private BTClientThread btClientThread;
    private SendReceive sendReceive;

    public BluetoothClassic(@NonNull Context context) {
        this.context = context;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        {
            scanModeBroadcastReceiver = new ScanModeBroadcastReceiver();
            IntentFilter scanModeIntentFilter = new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
            context.registerReceiver(scanModeBroadcastReceiver, scanModeIntentFilter);
        }

        {
            bluetoothBroadcastReceiver = new BluetoothBroadcastReceiver();
            IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            context.registerReceiver(bluetoothBroadcastReceiver, intentFilter);
        }

        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                Log.d(TAG, "This device doesn't support Bluetooth.");
            } else {
                Log.d(TAG, "This device supports Bluetooth.");
                for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                    Log.d(TAG, "name: " + device.getName());
                    Log.d(TAG, "address: " + device.getAddress());
                }
            }
        }
    }

    @Override
    public void onStart() {

    }

    @Override
    public void onResume() {

    }

    @Override
    public void onPause() {

    }

    @Override
    public void onStop() {

    }

    @Override
    public void onDestroy() {
        context.unregisterReceiver(scanModeBroadcastReceiver);
        context.unregisterReceiver(bluetoothBroadcastReceiver);

        if (btServerThread != null) {
            btServerThread.interrupt();
            btServerThread = null;
        }
        if (btClientThread != null) {
            btClientThread.interrupt();
            btClientThread = null;
        }
        if (bluetoothAdapter != null) {
            bluetoothAdapter = null;
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void advertise() {
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION);
        context.startActivity(intent);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void scan() {
        if (bluetoothAdapter != null) {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            boolean discoveryResult = bluetoothAdapter.startDiscovery();
            if (!discoveryResult) {
                Log.e(TAG, "discovery failed");
            }
        }
    }

    @Override
    public void connect(BluetoothDevice device) {
        btClientThread = new BTClientThread(device);
        btClientThread.start();
    }

    @Override
    public void write(@NonNull String message) {
        if (sendReceive == null) return;
        Log.d(TAG, "write: " + message);

        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        int length = messageBytes.length;
        byte[] lengthBytes = ByteBuffer.allocate(4).putInt(length).array();
        Log.d(TAG, "write(): length = " + length);
        // writing the length of the message
        sendReceive.write(lengthBytes);

        // writing the message delimiter
        byte[] delimiterBytes = ByteBuffer.allocate(2).putChar(':').array();
        sendReceive.write(delimiterBytes);

        // writing the message itself
        sendReceive.write(messageBytes);
    }

    @Override
    public void close() {

    }

    class ScanModeBroadcastReceiver extends BroadcastReceiver {
        private void start() {
            if (btServerThread == null) {
                Log.d(TAG, "BTServerThread is null. Starting.");
                btServerThread = new BTServerThread();
                btServerThread.start();
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)) {
                int modeValue = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR);
                switch (modeValue) {
                    case BluetoothAdapter.SCAN_MODE_CONNECTABLE: {
                        Log.d(TAG, "SCAN_MODE_CONNECTABLE");
                        start();
                        break;
                    }
                    case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE: {
                        Log.d(TAG, "SCAN_MODE_CONNECTABLE_DISCOVERABLE");
                        start();
                        break;
                    }
                    case BluetoothAdapter.SCAN_MODE_NONE: {
                        Log.d(TAG, "SCAN_MODE_NONE");
                        btServerThread = null;
                        break;
                    }
                    default: {
                        Log.e(TAG, "Should not reach here.");
                    }
                }
            }
        }
    }

    class BluetoothBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(BluetoothDevice.ACTION_FOUND)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                @SuppressLint("MissingPermission") String name = device.getName();
                String address = device.getAddress();
                @SuppressLint("MissingPermission") int type = device.getType();
                String typeString;
                switch (type) {
                    case BluetoothDevice.DEVICE_TYPE_CLASSIC: {
                        typeString = "Bluetooth Classic";
                        break;
                    }
                    case BluetoothDevice.DEVICE_TYPE_LE: {
                        typeString = "Bluetooth Low Energy";
                        break;
                    }
                    case BluetoothDevice.DEVICE_TYPE_DUAL: {
                        typeString = "Bluetooth Dual";
                        break;
                    }
                    case BluetoothDevice.DEVICE_TYPE_UNKNOWN: {
                        // fall through
                    }
                    default: {
                        typeString = "Bluetooth type unknown";
                    }
                }
                Log.d(TAG, String.format("device name = %s, address = %s, type = %s", name, address, typeString));

                // Notify only Bluetooth classic or Dual
                if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC || type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    Intent broadcastIntent = new Intent().setAction(DEVICE_FOUND_ACTION).putExtra(EXTRA_FOUND_DEVICE, device);
                    context.sendBroadcast(broadcastIntent);
                }
            }
        }
    }

    @NonNull
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EXTRA_CONNECTION_CONNECTED: {
                    Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_CONNECTED);
                    context.sendBroadcast(broadcastIntent);
                    break;
                }
                case EXTRA_CONNECTION_DISCONNECTED: {
                    Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_DISCONNECTED);
                    context.sendBroadcast(broadcastIntent);
                    break;
                }
                default: {
                    Log.e(TAG, "Unexpected message code arrived: " + msg.what);
                }
            }
        }
    };

    public class BTServerThread extends Thread {
        BluetoothServerSocket bluetoothServerSocket;
        BluetoothDevice bluetoothDevice;

        @SuppressLint("MissingPermission")
        public BTServerThread() {
            try {
                bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(BT_NAME, BT_UUID);
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        @SuppressLint("MissingPermission")
        public void run() {
            try {

                BluetoothSocket socket = bluetoothServerSocket.accept();
                bluetoothDevice = socket.getRemoteDevice();
                Message message = Message.obtain();
                message.what = EXTRA_CONNECTION_CONNECTED;
                message.obj = bluetoothDevice.getName();
                handler.sendMessage(message);

                sendReceive = new SendReceive(socket);
                sendReceive.start();
            } catch (IOException e) {
                Log.e(TAG, "Error while listening", e);
                if (bluetoothDevice == null) {
                    Log.e(TAG, "bluetoothDevice is null. There's nothing we can do at this moment.");
                    return;
                }
                Message message = Message.obtain();
                message.what = EXTRA_CONNECTION_DISCONNECTED;
                message.obj = bluetoothDevice.getName();
                handler.sendMessage(message);
            }
        }

        public void close() {
            try {
                if (bluetoothServerSocket != null) {
                    bluetoothServerSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }

    public class BTClientThread extends Thread {
        BluetoothDevice bluetoothDevice;
        BluetoothSocket bluetoothSocket;

        @SuppressLint("MissingPermission")
        public BTClientThread(BluetoothDevice bluetoothDevice) {
            this.bluetoothDevice = bluetoothDevice;

            try {
                bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(BT_UUID);
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        @SuppressLint("MissingPermission")
        public void run() {
            try {
                // cancel discovery before connecting
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                bluetoothSocket.connect();
                Message message = Message.obtain();
                message.what = EXTRA_CONNECTION_CONNECTED;
                message.obj = bluetoothDevice.getName();
                handler.sendMessage(message);

                sendReceive = new SendReceive(bluetoothSocket);
                sendReceive.start();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
                Message message = Message.obtain();
                message.what = EXTRA_CONNECTION_DISCONNECTED;
                message.obj = bluetoothDevice.getName();
                handler.sendMessage(message);
            }
        }

        public void close() {
            try {
                if (bluetoothSocket != null) {
                    bluetoothSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }

    private class SendReceive extends Thread {
        @NonNull
        private final Handler handler = new Handler(Looper.getMainLooper());

        private BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        private SendReceive(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tempIn = null;
            OutputStream tempOut = null;
            try {
                tempIn = socket.getInputStream();
                tempOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
            inputStream = tempIn;
            outputStream = tempOut;
        }

        @Override
        public void run() {
            SocketReader socketReader = new LengthSocketReader(inputStream);
            socketReader.setOnSuccessCallback((message, length) -> {
                String received = new String(message);
                Log.d(TAG, "read onSuccess. received message: " + received);
                handler.post(() -> Toast.makeText(context, received, Toast.LENGTH_SHORT).show());
            });
            socketReader.setOnFailureCallback(exception -> {
                Log.e(TAG, "read onFailure.", exception);
                handler.post(() -> Toast.makeText(context, exception.getMessage(), Toast.LENGTH_SHORT).show());
                BluetoothClassic.this.close();
            });

            while (socket != null && socket.isConnected()) {
                socketReader.read();
            }
        }

        public void write(byte[] bytes) {
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
                BluetoothClassic.this.close();
            }
        }

        public void close() {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }
}
