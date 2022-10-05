package us.shiroyama.wireless.network.wifi;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import us.shiroyama.wireless.network.LengthSocketReader;
import us.shiroyama.wireless.network.Lifecycle;
import us.shiroyama.wireless.network.SocketReader;
import us.shiroyama.wireless.network.WirelessNetwork;

public class WiFiDirect implements WirelessNetwork<WifiP2pDevice>, Lifecycle, WiFiDirectActivity {
    private static final String TAG = WiFiDirect.class.getSimpleName();

    private static final int SOCKET_PORT = 8988;
    private static final int SOCKET_TIMEOUT = 500;

    @NonNull
    private final IntentFilter intentFilter = new IntentFilter() {{
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }};

    @NonNull
    private final Context context;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean isWifiP2pEnabled;
    private WifiP2pInfo wifiP2pInfo;
    private boolean isConnected;
    private boolean isHost;
    private ServerThread serverThread;
    private ClientThread clientThread;

    public WiFiDirect(@NonNull Context context) {
        this.context = context;
    }

    @Override
    public void onCreate(@NonNull Bundle savedInstanceState) {
        initP2p();
    }

    @Override
    public void onStart() {

    }

    @Override
    public void onResume() {
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        context.registerReceiver(receiver, intentFilter);
    }

    @Override
    public void onPause() {
        context.unregisterReceiver(receiver);
    }

    @Override
    public void onStop() {

    }

    @Override
    public void onDestroy() {

    }

    @SuppressLint("MissingPermission")
    @Override
    public void advertise() {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "discoverPeers onSuccess");
            }

            @Override
            public void onFailure(int i) {
                Log.e(TAG, "discoverPeers onFailure: " + i);
            }
        });
    }

    @SuppressLint("MissingPermission")
    @Override
    public void scan() {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "discoverPeers onSuccess");
            }

            @Override
            public void onFailure(int i) {
                Log.e(TAG, "discoverPeers onFailure: " + i);
            }
        });
    }

    @SuppressLint("MissingPermission")
    @Override
    public void connect(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "connect onSuccess");
            }

            @Override
            public void onFailure(int i) {
                Log.e(TAG, "connect onFailure: " + i);
            }
        });
    }

    @Override
    public void write(@NonNull String message) {
        if (serverThread == null && clientThread == null) return;

        Log.d(TAG, "message: " + message);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(() -> {
            if (isHost) {
                if (serverThread == null) {
                    Log.d(TAG, "serverThread is null!");
                    return;
                }
                serverThread.writeString(message);
            } else {
                if (clientThread == null) {
                    Log.d(TAG, "clientThread is null!");
                    return;
                }
                clientThread.writeString(message);
            }
        });
    }

    @Override
    public void close() {
        isConnected = false;
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "disconnect onSuccess");
            }

            @Override
            public void onFailure(int i) {
                Log.e(TAG, "disconnect onFailure: " + i);
            }
        });
        if (clientThread != null) {
            clientThread.close();
            clientThread = null;
        }
        if (serverThread != null) {
            serverThread.close();
            serverThread = null;
        }
        Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_CONNECTED);
        context.sendBroadcast(broadcastIntent);
    }

    @Override
    public void setIsWifiP2pEnabled(boolean isWifiP2pEnabled) {
        Log.d(TAG, "setIsWifiP2pEnabled: " + isWifiP2pEnabled);
        this.isWifiP2pEnabled = isWifiP2pEnabled;
    }

    @Override
    public void onDeviceListChange(@NonNull List<? extends WifiP2pDevice> deviceList) {
        for (WifiP2pDevice wifiP2pDevice : deviceList) {
            Intent broadcastIntent = new Intent().setAction(DEVICE_FOUND_ACTION).putExtra(EXTRA_FOUND_DEVICE, wifiP2pDevice);
            context.sendBroadcast(broadcastIntent);
        }
    }

    @Override
    public void onP2PConnected(@NonNull WifiP2pInfo wifiP2pInfo, @NonNull WifiP2pGroup wifiP2pGroup) {
        Log.d(TAG, "wifiP2pInfo: " + wifiP2pInfo);
        this.wifiP2pInfo = wifiP2pInfo;

        Log.d(TAG, "wifiP2pGroup.getClientList().size() " + wifiP2pGroup.getClientList().size());
        for (WifiP2pDevice wifiP2pDevice : wifiP2pGroup.getClientList()) {
            Log.d(TAG, "wifiP2pDevice: " + wifiP2pDevice);
        }

        if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
            Log.d(TAG, "You are server!");
            isConnected = true;

            int size = wifiP2pGroup.getClientList().size();
            if (size == 0) {
                Log.e(TAG, "You are server, but no client associated. This is a race condition.");
                return;
            }

            Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_CONNECTED);
            context.sendBroadcast(broadcastIntent);
            isHost = true;

            if (serverThread != null) {
                Log.d(TAG, "serverThread already initialized! skipping.");
                return;
            }
            serverThread = new ServerThread();
            serverThread.start();
        } else if (wifiP2pInfo.groupFormed) {
            Log.d(TAG, "You are client!");
            isConnected = true;

            WifiP2pDevice owner = wifiP2pGroup.getOwner();
            if (owner == null) {
                Log.e(TAG, "You are client, but no owner associated. This is a race condition.");
                return;
            }

            Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_CONNECTED);
            context.sendBroadcast(broadcastIntent);
            isHost = false;

            if (clientThread != null) {
                Log.d(TAG, "clientThread already initialized! skipping.");
                return;
            }
            clientThread = new ClientThread();
            clientThread.start();
        } else {
            Log.e(TAG, "Something is wrong!");
            isConnected = false;

            Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_DISCONNECTED);
            context.sendBroadcast(broadcastIntent);
        }
    }

    @Override
    public void resetData() {
        Log.e(TAG, "resetData() is called. This is either WiFi is disabled or disconnected.");
        isConnected = false;
    }

    private boolean initP2p() {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            Log.e(TAG, "Wi-Fi Direct is not supported by this device.");
            return false;
        }

        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            Log.e(TAG, "Cannot get Wi-Fi system service.");
            return false;
        }
        if (!wifiManager.isP2pSupported()) {
            Log.e(TAG, "Wi-Fi Direct is not supported by the hardware or Wi-Fi is off.");
            return false;
        }
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.e(TAG, "Cannot get Wi-Fi Direct system service.");
            return false;
        }
        channel = manager.initialize(context.getApplicationContext(), context.getMainLooper(), () -> Log.d(TAG, "onChannelDisconnected"));
        if (channel == null) {
            Log.e(TAG, "Cannot initialize Wi-Fi Direct.");
            return false;
        }

        return true;
    }

    private abstract class MessageThread extends Thread {
        @NonNull
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Socket socket;
        private InputStream inputStream;
        private OutputStream outputStream;

        abstract Socket initializeSocket() throws IOException;

        @Override
        public void run() {
            try {
                socket = initializeSocket();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
                isConnected = false;
            }
            read();
        }

        private void read() {
            while (socket != null && !socket.isClosed()) {
                Log.d(TAG, "read(): loop starts.");
                SocketReader socketReader = new LengthSocketReader(inputStream);
                socketReader.setOnSuccessCallback((message, length) -> {
                    String received = new String(message);
                    Log.d(TAG, "read onSuccess. received message: " + received);
                    handler.post(() -> Toast.makeText(context, received, Toast.LENGTH_SHORT).show());
                });
                socketReader.setOnFailureCallback(exception -> {
                    Log.e(TAG, "read onFailure.", exception);
                    handler.post(() -> Toast.makeText(context, exception.getMessage(), Toast.LENGTH_SHORT).show());
                });
                Log.d(TAG, "socketReader.read(): starts.");
                socketReader.read();
            }
        }

        public void writeString(String message) {
            Log.d(TAG, "write(): message = " + message);

            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            int length = messageBytes.length;
            byte[] lengthBytes = ByteBuffer.allocate(4).putInt(length).array();
            Log.d(TAG, "write(): length = " + length);
            // writing the length of the message
            write(lengthBytes);

            Log.d(TAG, "write(): delimiter bytes");
            // writing the message delimiter
            byte[] delimiterBytes = ByteBuffer.allocate(2).putChar(':').array();
            write(delimiterBytes);

            // writing the message itself
            Log.d(TAG, "write(): message bytes");
            write(messageBytes);
            Log.d(TAG, "write(): end");
        }

        public void write(byte[] bytes) {
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
                WiFiDirect.this.close();
            }
        }

        public void close() {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }

    private class ClientThread extends MessageThread {
        @Override
        protected Socket initializeSocket() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(wifiP2pInfo.groupOwnerAddress, SOCKET_PORT), SOCKET_TIMEOUT);
            return socket;
        }
    }

    private class ServerThread extends MessageThread {
        private ServerSocket serverSocket;

        @Override
        protected Socket initializeSocket() throws IOException {
            serverSocket = new ServerSocket(SOCKET_PORT);
            return serverSocket.accept();
        }

        @Override
        public void close() {
            super.close();
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }
}
