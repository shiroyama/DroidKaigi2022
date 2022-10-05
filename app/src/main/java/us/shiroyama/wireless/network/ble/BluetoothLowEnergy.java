package us.shiroyama.wireless.network.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import us.shiroyama.wireless.network.Lifecycle;
import us.shiroyama.wireless.network.WirelessNetwork;

public class BluetoothLowEnergy implements WirelessNetwork<BluetoothDevice>, Lifecycle {
    private static final String TAG = BluetoothLowEnergy.class.getSimpleName();

    private static final int MAX_MTU = 512;
    private static final int MIN_MTU = 20;
    private int currentMTU = MIN_MTU;
    private static final long MTU_DELAY = TimeUnit.MILLISECONDS.toMillis(500);

    private static final int RETRY_LIMIT = 5;
    private static final long RETRY_INTERVAL = TimeUnit.MILLISECONDS.toMillis(500);

    private static final UUID UUID_SERVICE = UUID.fromString("e2d95cf0-72e4-da1a-cf5b-9370077dc9bb");
    private static final UUID UUID_CHARACTERISTIC = UUID.fromString("324861d6-75a0-edbe-2700-5bc419d4d6e0");
    private static final UUID UUID_DESCRIPTOR = UUID.fromString("33d6abb5-d6a5-7e99-f73e-10fe3e822c68");

    @NonNull
    private final Handler handler = new Handler(Looper.getMainLooper());
    @NonNull
    private final Context context;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private BluetoothGattServer bluetoothGattServer;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic bluetoothGattCharacteristic;
    private boolean isConnected;
    private boolean isPeripheral;

    @Nullable
    private BluetoothDevice remoteDevice;

    public BluetoothLowEnergy(@NonNull Context context) {
        this.context = context;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(context, "BLE is not supported on this device.", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(context, "Bluetooth is not supported on this device.", Toast.LENGTH_SHORT).show();
            return;
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

    }

    /**
     * Peripheral側のコールバック
     */
    @NonNull
    private final BluetoothGattServerCallback bluetoothGattServerCallback = new BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);

            Log.d(TAG, String.format("[Peripheral] onConnectionStateChange(device = (name: %s, address: %s), status = %d (code = %d), newState = %d (code = %d))", device.getName(), device.getAddress(), status, status, newState, newState));

            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED: {
                    // Keep the remote BluetoothDevice info for later use.
                    remoteDevice = device;
                    bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
                    isConnected = true;
                    Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_CONNECTED);
                    context.sendBroadcast(broadcastIntent);
                    break;
                }
                case BluetoothProfile.STATE_DISCONNECTED: {
                    isConnected = false;
                    Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_DISCONNECTED);
                    context.sendBroadcast(broadcastIntent);
                    remoteDevice = null;
                    break;
                }
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            super.onServiceAdded(status, service);
            Log.d(TAG, String.format("[Peripheral] onServiceAdded(status = %d, service = %s)", status, service.getUuid()));
        }

        // TODO Bugfix: この byteBuffer もはや意味不明なので直す…
        private final byte[] byteBuffer = new byte[MAX_MTU];

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.d(TAG, String.format("[Peripheral] onCharacteristicReadRequest(device = (name: %s, address: %s), requestId = %d, offset = %d, characteristic = %s)", device.getName(), device.getAddress(), requestId, offset, characteristic.getUuid()));

            if (offset > byteBuffer.length) {
                bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
            } else {
                byte[] value = new byte[byteBuffer.length - offset];
                System.arraycopy(byteBuffer, offset, value, 0, value.length);
                bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        /**
         * MTUを超えて受信し続ける情報を一時的にメモリに貯めるためのバッファ
         */
        @NonNull
        private final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            Log.d(TAG, String.format("[Peripheral] onCharacteristicWriteRequest(device = (name: %s, address: %s), requestId = %d, characteristic = %s, preparedWrite = %b, responseNeeded = %b, offset = %d, value = %s)", device.getName(), device.getAddress(), requestId, characteristic.getUuid(), preparedWrite, responseNeeded, offset, new String(value)));

            if (UUID_CHARACTERISTIC.equals(characteristic.getUuid())) {
                try {
                    if (!preparedWrite) {
                        Log.d(TAG, "onCharacteristicWriteRequest(): preparedWrite is false. This is one-shot message within MTU");
                        boolean setValueResult = operationWithRetry(() -> characteristic.setValue(value));
                        if (!setValueResult) {
                            Log.e(TAG, "onCharacteristicWriteRequest(): characteristic.setValue() failed.");
                            return;
                        }
                        Log.d(TAG, "onCharacteristicWriteRequest(): characteristic.setValue() succeeded.");

                        String message = characteristic.getStringValue(0);
                        Log.d(TAG, String.format("[Peripheral] onCharacteristicWriteRequest() message received: %s", message));
                        handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
                    } else {
                        Log.d(TAG, "onCharacteristicWriteRequest(): preparedWrite is true. This is contiguous message without MTU");
                        try {
                            byteArrayOutputStream.write(value);
                        } catch (IOException e) {
                            Log.e(TAG, e.getMessage(), e);
                        }
                    }
                } finally {
                    if (responseNeeded) {
                        bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            Log.d(TAG, String.format("[Peripheral] onDescriptorReadRequest(device = (name: %s, address: %s), requestId = %d, offset = %d, descriptor = %s)", device.getName(), device.getAddress(), requestId, offset, descriptor.getUuid()));
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            Log.d(TAG, String.format("[Peripheral] onDescriptorWriteRequest(device = (name: %s, address: %s), requestId = %d, descriptor = %s, preparedWrite = %b, responseNeeded = %b, offset = %d, value = %s)", device.getName(), device.getAddress(), requestId, descriptor.getUuid(), preparedWrite, responseNeeded, offset, new String(value)));

            if (UUID_DESCRIPTOR.equals(descriptor.getUuid())) {
                if (responseNeeded) {
                    bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                }
            }
        }

        // TODO Pixel 3a XL などの一部の端末「以外」ではほとんどこの onExecuteWrite を実行した瞬間 GATT_ERROR(133) で死ぬことを観測している。MTU を超えない送信方法を再度検討する。
        @SuppressLint("MissingPermission")
        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            super.onExecuteWrite(device, requestId, execute);
            Log.d(TAG, String.format("[Peripheral] onExecuteWrite(device = (name: %s, address: %s), requestId = %d, execute = %b)", device.getName(), device.getAddress(), requestId, execute));

            byte[] bytes = byteArrayOutputStream.toByteArray();
            byteArrayOutputStream.reset();
            String message = new String(bytes, StandardCharsets.UTF_8);
            Log.d(TAG, String.format("[Peripheral] onExecuteWrite(): message received: %s", message));
            handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
            Log.d(TAG, String.format("[Peripheral] onNotificationSent(device = (name: %s, address: %s), status = %d)", device.getName(), device.getAddress(), status));
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            super.onMtuChanged(device, mtu);
            Log.d(TAG, String.format("[Peripheral] onMtuChanged(device = (name: %s, address: %s), mtu = %d)", device.getName(), device.getAddress(), mtu));
            currentMTU = mtu;
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onPhyUpdate(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            super.onPhyUpdate(device, txPhy, rxPhy, status);
            Log.d(TAG, String.format("[Peripheral] onPhyUpdate(device = (name: %s, address: %s), txPhy = %d, rxPhy = %d, status = %d)", device.getName(), device.getAddress(), txPhy, rxPhy, status));
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onPhyRead(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            super.onPhyRead(device, txPhy, rxPhy, status);
            Log.d(TAG, String.format("[Peripheral] onPhyRead(device = (name: %s, address: %s), txPhy = %d, rxPhy = %d, status = %d)", device.getName(), device.getAddress(), txPhy, rxPhy, status));
        }
    };

    @SuppressLint("MissingPermission")
    private boolean openGATTServer() {
        Log.d(TAG, "openGATTServer() started.");
        BluetoothGattDescriptor bluetoothGattDescriptor = new BluetoothGattDescriptor(UUID_DESCRIPTOR,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        BluetoothGattCharacteristic bluetoothGattCharacteristic = new BluetoothGattCharacteristic(
                UUID_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ
        );
        this.bluetoothGattCharacteristic = bluetoothGattCharacteristic;
        bluetoothGattCharacteristic.addDescriptor(bluetoothGattDescriptor);
        BluetoothGattService bluetoothGattService = new BluetoothGattService(UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        boolean addCharacteristicResult = operationWithRetry(() -> bluetoothGattService.addCharacteristic(bluetoothGattCharacteristic));
        if (!addCharacteristicResult) {
            Log.e(TAG, "openGATTServer(): addCharacteristic() failed.");
            return false;
        }
        Log.d(TAG, "openGATTServer(): addCharacteristic() succeeded.");

        bluetoothGattServer = bluetoothManager.openGattServer(context, bluetoothGattServerCallback);
        boolean addServiceResult = operationWithRetry(() -> bluetoothGattServer.addService(bluetoothGattService));
        if (!addServiceResult) {
            Log.e(TAG, "openGATTServer(): addService() failed.");
            return false;
        }
        Log.d(TAG, "openGATTServer(): addService() succeeded.");
        return true;
    }

    @SuppressLint("MissingPermission")
    private void closeGATTServer() {
        if (bluetoothGattServer != null) {
            bluetoothGattServer.clearServices();
            bluetoothGattServer.close();
        }
    }

    @NonNull
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "BLE advertising success: " + settingsInEffect);
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "BLE advertising failure. errorCode: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        Log.d(TAG, "startAdvertising(): started.");
        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder();
        settingsBuilder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY);
        settingsBuilder.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH);
        settingsBuilder.setTimeout(0);
        settingsBuilder.setConnectable(true);

        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
        dataBuilder.setIncludeTxPowerLevel(true);
        dataBuilder.addServiceUuid(ParcelUuid.fromString(UUID_SERVICE.toString()));

        AdvertiseData.Builder responseBuilder = new AdvertiseData.Builder();
        responseBuilder.setIncludeDeviceName(true);

        bluetoothLeAdvertiser.startAdvertising(settingsBuilder.build(), dataBuilder.build(), responseBuilder.build(), advertiseCallback);
    }

    @Override
    public void advertise() {
        Log.d(TAG, "advertise(): started.");
        bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (bluetoothLeAdvertiser == null) {
            String error = "This device does not support BLE Peripheral mode.";
            Log.e(TAG, "advertise(): " + error);
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean openGATTServerResult = operationWithRetry(this::openGATTServer);
        if (!openGATTServerResult) {
            Log.e(TAG, "advertise(): openGATTServer() failed.");
            return;
        }
        Log.d(TAG, "advertise(): openGATTServer() succeeded.");

        startAdvertising();
        Log.d(TAG, "advertise(): startAdvertising() called.");

        isPeripheral = true;
    }

    @NonNull
    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            Log.d(TAG, String.format("onScanResult(callbackType = %d, result = %s)", callbackType, result));

            BluetoothDevice bluetoothDevice = result.getDevice();
            if (bluetoothDevice.getName() == null) {
                Log.d(TAG, "onScanResult(): Device name is null. Skip notifying.");
                return;
            }
            Intent broadcastIntent = new Intent().setAction(DEVICE_FOUND_ACTION).putExtra(EXTRA_FOUND_DEVICE, bluetoothDevice);
            context.sendBroadcast(broadcastIntent);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            Log.d(TAG, "onBatchScanResults()");
            for (ScanResult result : results) {
                Log.d(TAG, "onBatchScanResults(): result: " + result.toString());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, String.format("onScanFailed(errorCode = %d)", errorCode));
        }
    };

    @SuppressLint("MissingPermission")
    private void startScan() {
        Log.d(TAG, "startScan()");
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bluetoothLeScanner.startScan(scanCallback);
    }

    @Override
    public void scan() {
        Log.d(TAG, "discover()");
        startScan();
        isPeripheral = false;
    }

    /**
     * Central 側のコールバック
     */
    @NonNull
    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status);
            Log.d(TAG, String.format("[Central] onPhyUpdate(gatt = %s, txPhy = %d, rxPhy = %d, status = %d)", gatt.toString(), txPhy, rxPhy, status));
        }

        @Override
        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyRead(gatt, txPhy, rxPhy, status);
            Log.d(TAG, String.format("[Central] onPhyRead(gatt = %s, txPhy = %d, rxPhy = %d, status = %d)", gatt.toString(), txPhy, rxPhy, status));
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            Log.d(TAG, String.format("[Central] onConnectionStateChange(gatt = %s, status = %d (code = %d), newState = %d (code = %d))", gatt.toString(), status, status, newState, newState));

            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED: {
                    Log.d(TAG, "[Central] Start service discovery.");
                    boolean discoverServicesResult = operationWithRetry(gatt::discoverServices);
                    if (!discoverServicesResult) {
                        Log.e(TAG, "[Central] onConnectionStateChange(): discoverServices() failed.");
                        return;
                    }
                    Log.d(TAG, "[Central] onConnectionStateChange(): discoverServices() succeeded.");
                    break;
                }
                case BluetoothProfile.STATE_DISCONNECTED: {
                    BluetoothDevice remoteDevice = gatt.getDevice();
                    @SuppressLint("MissingPermission") String remoteDeviceName = remoteDevice.getName();

                    Log.d(TAG, "[Central] Disconnected. Close current GATT. Remote device: " + remoteDeviceName);
                    if (bluetoothGatt != null) {
                        bluetoothGatt.close();
                        bluetoothGatt = null;
                    }
                    isConnected = false;
                    Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_DISCONNECTED);
                    context.sendBroadcast(broadcastIntent);
                    break;
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Log.d(TAG, String.format("[Central] onServicesDiscovered(gatt = %s, status = %d)", gatt.toString(), status));

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, String.format("[Central] Status was not GATT_SUCCESS. Got %d", status));
                return;
            }
            BluetoothGattService service = gatt.getService(UUID_SERVICE);
            if (service == null) {
                Log.e(TAG, String.format("[Central] Service (%s) was not found.", UUID_SERVICE.toString()));
                return;
            }
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID_CHARACTERISTIC);
            if (characteristic == null) {
                Log.e(TAG, String.format("[Central] Characteristic (%s) was not found.", UUID_CHARACTERISTIC.toString()));
                return;
            }
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID_DESCRIPTOR);
            if (descriptor == null) {
                Log.e(TAG, String.format("[Central] Descriptor (%s) was not found.", UUID_DESCRIPTOR.toString()));
                return;
            }
            Log.d(TAG, "[Central] Service, Characteristic, and Descriptor were successfully found.");
            bluetoothGatt = gatt;
            remoteDevice = gatt.getDevice();
            bluetoothGattCharacteristic = characteristic;
            bluetoothLeScanner.stopScan(scanCallback);

            boolean setCharacteristicNotificationResult = operationWithRetry(() -> bluetoothGatt.setCharacteristicNotification(characteristic, true));
            if (!setCharacteristicNotificationResult) {
                Log.e(TAG, "[Central] onServicesDiscovered(): setCharacteristicNotification() failed.");
                return;
            }
            Log.d(TAG, "[Central] onServicesDiscovered(): setCharacteristicNotification() succeeded.");

            boolean descriptorSetValueResult = operationWithRetry(() -> descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE));
            if (!descriptorSetValueResult) {
                Log.e(TAG, "[Central] onServicesDiscovered(): descriptor.setValue() failed.");
                return;
            }
            Log.d(TAG, "[Central] onServicesDiscovered(): descriptor.setValue() succeeded.");

            boolean writeDescriptorResult = operationWithRetry(() -> bluetoothGatt.writeDescriptor(descriptor));
            if (!writeDescriptorResult) {
                Log.e(TAG, "[Central] onServicesDiscovered(): writeDescriptor() failed. Failed enabling Notification for Characteristic: " + UUID_CHARACTERISTIC.toString());
                return;
            }
            Log.d(TAG, "[Central] onServicesDiscovered(): writeDescriptor() succeeded.");
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            Log.d(TAG, String.format("[Central] onCharacteristicRead(gatt = %s, characteristic = %s, status = %d)", gatt.toString(), characteristic.getUuid(), status));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.d(TAG, String.format("[Central] onCharacteristicWrite(gatt = %s, characteristic = %s, status = %d)", gatt.toString(), characteristic.getUuid(), status));
        }

        /**
         * MTUを超えて受信し続ける情報を一時的にメモリに貯めるためのバッファ
         */
        @NonNull
        private final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            Log.d(TAG, String.format("[Central] onCharacteristicChanged(gatt = %s, characteristic = %s)", gatt.toString(), characteristic.getUuid()));

            if (UUID_CHARACTERISTIC.equals(characteristic.getUuid())) {
                byte[] bytes = characteristic.getValue();
                byte totalChunkByte = bytes[0];
                byte currentChunkByte = bytes[1];
                int headerSize = 2;
                int byteSize = bytes.length;
                byte[] buffer = new byte[byteSize - headerSize];
                System.arraycopy(bytes, headerSize, buffer, 0, byteSize - headerSize);
                int totalChunk = Byte.toUnsignedInt(totalChunkByte);
                int currentChunk = Byte.toUnsignedInt(currentChunkByte);
                String messageChunk = new String(buffer);
                Log.d(TAG, String.format("[Central] totalChunk = %d, currentChunk = %d, message = %s", totalChunk, currentChunk, messageChunk));

                try {
                    byteArrayOutputStream.write(buffer);
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }

                if (totalChunk == currentChunk) {
                    String fullMessage = byteArrayOutputStream.toString();
                    byteArrayOutputStream.reset();
                    Log.d(TAG, String.format("[Central] finally full message received: %s", fullMessage));
                    handler.post(() -> Toast.makeText(context, fullMessage, Toast.LENGTH_SHORT).show());
                }
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            Log.d(TAG, String.format("[Central] onDescriptorRead(gatt = %s, descriptor = %s, status = %d)", gatt.toString(), descriptor.getUuid(), status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.d(TAG, String.format("[Central] onDescriptorWrite(gatt = %s, descriptor = %s, status = %d)", gatt.toString(), descriptor.getUuid(), status));

            @SuppressLint("MissingPermission")
            boolean requestMtuResult = operationWithRetry(() -> bluetoothGatt.requestMtu(MAX_MTU));
            Log.d(TAG, "requestMtuResult: " + requestMtuResult);

            // Android BLE では、GATT に関わるすべての操作を「シーケンシャル」に行わないと失敗することが知られている。requestMtu -> onMtuChanged のペアも例外ではない。
            // しかし、Pixel 3a XL などの端末が Peripheral の場合に、Central からの requestMtu() は true が返るにも関わらず、onMtuChanged() が永久にコールバックされない事象を観測している。
            // したがって、ここを完全にシーケンシャルにすることは不可能だ。ここでは苦肉の策として「思いやりタイマー」で500ミリ秒待っている。その後、MTUの結果如何に関わらず Peripheral にデバイス名を通知する。
            // TODO この部分の待ち処理を、タイマー付き Queue のようなものを作ってもう少しエレガントに改良する。
            handler.postDelayed(() -> {
                // Centralはこの時点ですべての前処理を終えるので「接続完了」状態にする。
                isConnected = true;
                Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_CONNECTED);
                context.sendBroadcast(broadcastIntent);
                Log.d(TAG, "[Central] Central is connected");

                Log.d(TAG, "[Central] onDescriptorWrite(): sendDeviceInfoToPeripheral() succeeded.");
            }, MTU_DELAY);
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
            Log.d(TAG, String.format("[Central] onReliableWriteCompleted(gatt = %s, status = %d)", gatt.toString(), status));
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
            Log.d(TAG, String.format("[Central] onReadRemoteRssi(gatt = %s, rssi = %d, status = %d)", gatt.toString(), rssi, status));
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            Log.d(TAG, String.format("[Central] onMtuChanged(gatt = %s, mtu = %d, status = %d)", gatt.toString(), mtu, status));
            currentMTU = mtu;
        }
    };


    @SuppressLint("MissingPermission")
    @Override
    public void connect(BluetoothDevice device) {
        Log.d(TAG, String.format("connect(device = %s)", device));
        bluetoothGatt = device.connectGatt(context, false, bluetoothGattCallback);
    }

    @Override
    public void write(@NonNull String message) {
        Log.d(TAG, "write() message: " + message);

        if (!isConnected) {
            Log.d(TAG, "Device not connected. Skip writing.");
            return;
        }

        if (isPeripheral) {
            Log.d(TAG, "[Peripheral]: Device is Peripheral.");
            if (bluetoothGattServer != null && remoteDevice != null && bluetoothGattCharacteristic != null) {
                Log.d(TAG, "BluetoothGattServer && connectedDevice && BluetoothGattCharacteristic are ready. Try to notify.");
                boolean sendNotificationResult = operationWithRetry(() -> sendNotification(message));
                if (!sendNotificationResult) {
                    Log.e(TAG, "write(): sendNotification() failed.");
                    return;
                }
                Log.d(TAG, "write(): sendNotification() succeeded.");
            } else {
                Log.e(TAG, "BluetoothGattServer or remoteDevice or BluetoothGattCharacteristic are not ready!");
            }
        } else {
            Log.d(TAG, "[Central]: Device is Central.");
            if (bluetoothGatt != null && bluetoothGattCharacteristic != null) {
                Log.d(TAG, "BluetoothGatt && BluetoothGattCharacteristic are ready. Try to write.");
                boolean setValueResult = operationWithRetry(() -> bluetoothGattCharacteristic.setValue(message));
                if (!setValueResult) {
                    Log.e(TAG, "write(): setValue() failed.");
                    return;
                }
                Log.d(TAG, "write(): setValue() succeeded.");
                if (bluetoothGatt == null) {
                    Log.e(TAG, "bluetoothGatt became null. (This seems to happen sometimes).");
                    return;
                }
                @SuppressLint("MissingPermission") boolean writeCharacteristicResult = operationWithRetry(() -> bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic));
                if (!writeCharacteristicResult) {
                    Log.e(TAG, "write(): writeCharacteristic() failed.");
                    return;
                }
                Log.d(TAG, "write(): writeCharacteristic() succeeded.");
            } else {
                Log.e(TAG, "BluetoothGatt or BluetoothGattCharacteristic are not ready!");
            }
        }
    }

    /**
     * Notificationには分割送信機能はないので、自分でMTUを元に分割する。
     * requestMtu() が不安定なため、{@link #currentMTU} のデフォルトは {@link #MIN_MTU} で分割する。
     * 後から復号する必要があるため、
     * 1. 先頭1バイトを総チャンク数
     * 2. 次の1バイトを現在送信しているチャンク数
     * 3. 残りのバイトをペイロード本体
     * というルールにする。つまり、チャンク数 が 1 byte = 8 bits を超えるメッセージは必然的に送れない仕様とした。
     *
     * @param message
     * @return status
     */
    private boolean sendNotification(@NonNull String message) {
        Log.d(TAG, "Start sendNotification.");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        int byteSize = bytes.length;
        int headerSize = 2;
        int payloadSize = currentMTU - headerSize;
        int chunks = (byteSize + payloadSize - 1) / payloadSize;
        if (chunks > (1 << 8)) {
            Log.e(TAG, String.format("sendNotification(): Chunk size (%d) is too big. This is due to the size of message (%s)", chunks, message));
            return false;
        }
        for (int i = 0; i < chunks; i++) {
            int srcPos = i * payloadSize;
            int length = (srcPos + payloadSize) > byteSize ? byteSize - srcPos : payloadSize;
            byte totalChunkByte = (byte) chunks;
            byte currentChunkByte = (byte) (i + 1);
            byte[] partialBytes = new byte[headerSize + length];
            partialBytes[0] = totalChunkByte;
            partialBytes[1] = currentChunkByte;
            System.arraycopy(bytes, srcPos, partialBytes, headerSize, length);
            boolean setValueResult = operationWithRetry(() -> bluetoothGattCharacteristic.setValue(partialBytes));
            if (!setValueResult) {
                Log.e(TAG, "sendNotification(): bluetoothGattCharacteristic.setValue() failed.");
                return false;
            }
            Log.d(TAG, "sendNotification(): bluetoothGattCharacteristic.setValue() succeeded.");
            if (bluetoothGattServer == null) {
                Log.e(TAG, "sendNotification(): bluetoothGattServer is null.");
                return false;
            }
            @SuppressLint("MissingPermission")
            boolean notificationResult = operationWithRetry(() -> bluetoothGattServer.notifyCharacteristicChanged(remoteDevice, bluetoothGattCharacteristic, true));
            if (!notificationResult) {
                Log.e(TAG, "sendNotification(): notifyCharacteristicChanged failed.");
                return false;
            }
            Log.d(TAG, "sendNotification(): notifyCharacteristicChanged succeeded.");
        }
        Log.d(TAG, "End sendNotification successfully.");
        return true;
    }

    private boolean operationWithRetry(@NonNull BooleanSupplier operation) {
        Log.d(TAG, "operationWithRetry(): start.");
        boolean operationResult;
        int retryCount = 0;
        do {
            operationResult = operation.getAsBoolean();
            if (operationResult) {
                Log.d(TAG, "operationWithRetry(): succeeded.");
                return true;
            }
            try {
                Log.d(TAG, "operationWithRetry(): failed. Start retrying. retryCount: " + retryCount);
                Thread.sleep(RETRY_INTERVAL);
            } catch (InterruptedException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        } while (retryCount++ < RETRY_LIMIT);
        Log.e(TAG, "operationWithRetry(): failed regardless of retries.");
        return false;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void close() {
        Log.d(TAG, "close()");
        if (bluetoothLeAdvertiser != null) {
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
            bluetoothLeAdvertiser = null;
        }
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            bluetoothLeScanner = null;
        }
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        if (bluetoothGattServer != null) {
            closeGATTServer();
            bluetoothGattServer = null;
        }
        isConnected = false;

        Log.d(TAG, "close(): send connection status.");
        Intent broadcastIntent = new Intent().setAction(CONNECTION_STATUS_CHANGED_ACTION).putExtra(EXTRA_CONNECTION_STATUS, EXTRA_CONNECTION_DISCONNECTED);
        context.sendBroadcast(broadcastIntent);
    }
}
