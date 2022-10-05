package us.shiroyama.wireless;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import us.shiroyama.wireless.network.Lifecycle;
import us.shiroyama.wireless.network.WirelessNetwork;
import us.shiroyama.wireless.network.wifi.WiFiDirect;

public class WiFiActivity extends WirelessActivity {
    private final String TAG = WiFiActivity.class.getSimpleName();

    private static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;

    @NonNull
    private final IntentFilter intentFilter = new IntentFilter() {{
        addAction(WirelessNetwork.DEVICE_FOUND_ACTION);
        addAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION);
    }};

    @NonNull
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case WirelessNetwork.DEVICE_FOUND_ACTION: {
                    WifiP2pDevice device = intent.getParcelableExtra(WirelessNetwork.EXTRA_FOUND_DEVICE);
                    Log.d(TAG, "DEVICE_FOUND_ACTION: " + device);

                    if (deviceList.contains(device)) return;
                    deviceList.add(device);
                    deviceAdapter.notifyDataSetChanged();
                }
                case WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION: {
                    int connectionStatus = intent.getIntExtra(WirelessNetwork.EXTRA_CONNECTION_STATUS, WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED);
                    Log.d(TAG, "EXTRA_CONNECTION_STATUS: " + connectionStatus);
                    if (connectionStatus == WirelessNetwork.EXTRA_CONNECTION_CONNECTED) {
                        Toast.makeText(getApplicationContext(), "CONNECTED", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getApplicationContext(), "DISCONNECTED", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    @NonNull
    private final WiFiDirect wifiDirect = new WiFiDirect(this);

    @NonNull
    private final List<WifiP2pDevice> deviceList = new ArrayList<>();

    @NonNull
    private final WiFiDeviceAdapter deviceAdapter = new WiFiDeviceAdapter(deviceList);

    @Override
    public int getContentView() {
        return R.layout.activity_message;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycleObject() {
        return wifiDirect;
    }

    @NonNull
    @Override
    public String getName() {
        return "Wi-Fi Direct";
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
        }

        RecyclerView recyclerViewDevice = findViewById(R.id.recyclerViewDevice);
        deviceAdapter.setOnClickListener(viewHolder -> {
            int position = viewHolder.getAbsoluteAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                Log.e(TAG, "getAbsoluteAdapterPosition() returned NO_POSITION");
                return;
            }
            WifiP2pDevice device = deviceList.get(position);
            String log = String.format(Locale.US, "onClick: position=%d, device=%s, address=%s", position, device.deviceName, device.deviceAddress);
            Log.d(TAG, log);
            wifiDirect.connect(device);
        });
        RecyclerView.LayoutManager layoutManagerDevice = new LinearLayoutManager(this);
        recyclerViewDevice.setAdapter(deviceAdapter);
        recyclerViewDevice.setLayoutManager(layoutManagerDevice);
        recyclerViewDevice.setHasFixedSize(true);

        Button buttonAdvertise = findViewById(R.id.buttonAdvertise);
        buttonAdvertise.setOnClickListener(view -> wifiDirect.advertise());

        Button buttonScan = findViewById(R.id.buttonScan);
        buttonScan.setOnClickListener(view -> wifiDirect.scan());

        Button buttonClose = findViewById(R.id.buttonClose);
        buttonClose.setOnClickListener(view -> {
            wifiDirect.close();
            deviceList.clear();
            deviceAdapter.notifyDataSetChanged();
        });

        Button buttonSend = findViewById(R.id.buttonSend);
        buttonSend.setOnClickListener(view -> {
            EditText editTextMessage = findViewById(R.id.editTextMessage);
            String message = editTextMessage.getText().toString();
            Log.d(TAG, "message: " + message);
            wifiDirect.write(message);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION:
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Fine location permission is not granted!");
                    finish();
                }
                break;
        }
    }
}
