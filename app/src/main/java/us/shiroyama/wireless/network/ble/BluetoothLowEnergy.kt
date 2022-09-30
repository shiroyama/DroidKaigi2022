package us.shiroyama.wireless.network.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import us.shiroyama.wireless.network.Lifecycle
import us.shiroyama.wireless.network.WirelessNetwork
import us.shiroyama.wireless.network.ble.BluetoothLowEnergy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

class BluetoothLowEnergy(private val context: Context) : WirelessNetwork<BluetoothDevice>,
    Lifecycle {
    private var currentMTU = MIN_MTU
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var bluetoothGattServer: BluetoothGattServer
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothGattCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var isPeripheral = false
    private var remoteDevice: BluetoothDevice? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(context, "BLE is not supported on this device.", Toast.LENGTH_SHORT)
                .show()
            return
        }
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(
                context, "Bluetooth is not supported on this device.", Toast.LENGTH_SHORT
            ).show()
            return
        }
    }

    override fun onStart() {}
    override fun onResume() {}
    override fun onPause() {}
    override fun onStop() {}
    override fun onDestroy() {}

    /**
     * Peripheral側のコールバック
     */
    private val bluetoothGattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                device: BluetoothDevice, status: Int, newState: Int
            ) {
                super.onConnectionStateChange(device, status, newState)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onConnectionStateChange(device = (name: %s, address: %s), status = %d (code = %d), newState = %d (code = %d))",
                        device.name,
                        device.address,
                        status,
                        status,
                        newState,
                        newState
                    )
                )
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {

                        // Keep the remote BluetoothDevice info for later use.
                        remoteDevice = device
                        bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
                        isConnected = true
                        val broadcastIntent =
                            Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION)
                                .putExtra(
                                    WirelessNetwork.EXTRA_CONNECTION_STATUS,
                                    WirelessNetwork.EXTRA_CONNECTION_CONNECTED
                                )
                        context.sendBroadcast(broadcastIntent)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        isConnected = false
                        val broadcastIntent =
                            Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION)
                                .putExtra(
                                    WirelessNetwork.EXTRA_CONNECTION_STATUS,
                                    WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                                )
                        context.sendBroadcast(broadcastIntent)
                        remoteDevice = null
                    }
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                super.onServiceAdded(status, service)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onServiceAdded(status = %d, service = %s)",
                        status,
                        service.uuid
                    )
                )
            }

            // TODO Bugfix: この byteBuffer もはや意味不明なので直す…
            private val byteBuffer = ByteArray(MAX_MTU)

            @SuppressLint("MissingPermission")
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onCharacteristicReadRequest(device = (name: %s, address: %s), requestId = %d, offset = %d, characteristic = %s)",
                        device.name,
                        device.address,
                        requestId,
                        offset,
                        characteristic.uuid
                    )
                )
                if (offset > byteBuffer.size) {
                    bluetoothGattServer.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, offset, null
                    )
                } else {
                    val value = ByteArray(byteBuffer.size - offset)
                    System.arraycopy(byteBuffer, offset, value, 0, value.size)
                    bluetoothGattServer.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                    )
                }
            }

            /**
             * MTUを超えて受信し続ける情報を一時的にメモリに貯めるためのバッファ
             */
            private val byteArrayOutputStream = ByteArrayOutputStream()

            @SuppressLint("MissingPermission")
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                super.onCharacteristicWriteRequest(
                    device, requestId, characteristic, preparedWrite, responseNeeded, offset, value
                )
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onCharacteristicWriteRequest(device = (name: %s, address: %s), requestId = %d, characteristic = %s, preparedWrite = %b, responseNeeded = %b, offset = %d, value = %s)",
                        device.name,
                        device.address,
                        requestId,
                        characteristic.uuid,
                        preparedWrite,
                        responseNeeded,
                        offset,
                        String(value)
                    )
                )
                if (UUID_CHARACTERISTIC == characteristic.uuid) {
                    try {
                        if (!preparedWrite) {
                            Log.d(
                                TAG,
                                "onCharacteristicWriteRequest(): preparedWrite is false. This is one-shot message within MTU"
                            )
                            val setValueResult =
                                operationWithRetry { characteristic.setValue(value) }
                            if (!setValueResult) {
                                Log.e(
                                    TAG,
                                    "onCharacteristicWriteRequest(): characteristic.setValue() failed."
                                )
                                return
                            }
                            Log.d(
                                TAG,
                                "onCharacteristicWriteRequest(): characteristic.setValue() succeeded."
                            )
                            val message = characteristic.getStringValue(0)
                            Log.d(
                                TAG, String.format(
                                    "[Peripheral] onCharacteristicWriteRequest() message received: %s",
                                    message
                                )
                            )
                            handler.post {
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Log.d(
                                TAG,
                                "onCharacteristicWriteRequest(): preparedWrite is true. This is contiguous message without MTU"
                            )
                            try {
                                byteArrayOutputStream.write(value)
                            } catch (e: IOException) {
                                Log.e(TAG, e.message, e)
                            }
                        }
                    } finally {
                        if (responseNeeded) {
                            bluetoothGattServer.sendResponse(
                                device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                            )
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {
                super.onDescriptorReadRequest(device, requestId, offset, descriptor)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onDescriptorReadRequest(device = (name: %s, address: %s), requestId = %d, offset = %d, descriptor = %s)",
                        device.name,
                        device.address,
                        requestId,
                        offset,
                        descriptor.uuid
                    )
                )
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                super.onDescriptorWriteRequest(
                    device, requestId, descriptor, preparedWrite, responseNeeded, offset, value
                )
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onDescriptorWriteRequest(device = (name: %s, address: %s), requestId = %d, descriptor = %s, preparedWrite = %b, responseNeeded = %b, offset = %d, value = %s)",
                        device.name,
                        device.address,
                        requestId,
                        descriptor.uuid,
                        preparedWrite,
                        responseNeeded,
                        offset,
                        String(value)
                    )
                )
                if (UUID_DESCRIPTOR == descriptor.uuid) {
                    if (responseNeeded) {
                        bluetoothGattServer.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                        )
                    }
                }
            }

            // TODO Pixel 3a XL などの一部の端末「以外」ではほとんどこの onExecuteWrite を実行した瞬間 GATT_ERROR(133) で死ぬことを観測している。MTU を超えない送信方法を再度検討する。
            @SuppressLint("MissingPermission")
            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                super.onExecuteWrite(device, requestId, execute)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onExecuteWrite(device = (name: %s, address: %s), requestId = %d, execute = %b)",
                        device.name,
                        device.address,
                        requestId,
                        execute
                    )
                )
                val bytes = byteArrayOutputStream.toByteArray()
                byteArrayOutputStream.reset()
                val message = String(bytes, StandardCharsets.UTF_8)
                Log.d(
                    TAG,
                    String.format("[Peripheral] onExecuteWrite(): message received: %s", message)
                )
                handler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            }

            @SuppressLint("MissingPermission")
            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                super.onNotificationSent(device, status)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onNotificationSent(device = (name: %s, address: %s), status = %d)",
                        device.name,
                        device.address,
                        status
                    )
                )
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                super.onMtuChanged(device, mtu)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onMtuChanged(device = (name: %s, address: %s), mtu = %d)",
                        device.name,
                        device.address,
                        mtu
                    )
                )
                currentMTU = mtu
            }

            @SuppressLint("MissingPermission")
            override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
                super.onPhyUpdate(device, txPhy, rxPhy, status)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onPhyUpdate(device = (name: %s, address: %s), txPhy = %d, rxPhy = %d, status = %d)",
                        device.name,
                        device.address,
                        txPhy,
                        rxPhy,
                        status
                    )
                )
            }

            @SuppressLint("MissingPermission")
            override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
                super.onPhyRead(device, txPhy, rxPhy, status)
                Log.d(
                    TAG, String.format(
                        "[Peripheral] onPhyRead(device = (name: %s, address: %s), txPhy = %d, rxPhy = %d, status = %d)",
                        device.name,
                        device.address,
                        txPhy,
                        rxPhy,
                        status
                    )
                )
            }
        }

    @SuppressLint("MissingPermission")
    private fun openGATTServer(): Boolean {
        Log.d(TAG, "openGATTServer() started.")
        val bluetoothGattDescriptor = BluetoothGattDescriptor(
            UUID_DESCRIPTOR,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        val bluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        this.bluetoothGattCharacteristic = bluetoothGattCharacteristic
        bluetoothGattCharacteristic.addDescriptor(bluetoothGattDescriptor)
        val bluetoothGattService =
            BluetoothGattService(UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val addCharacteristicResult =
            operationWithRetry { bluetoothGattService.addCharacteristic(bluetoothGattCharacteristic) }
        if (!addCharacteristicResult) {
            Log.e(TAG, "openGATTServer(): addCharacteristic() failed.")
            return false
        }
        Log.d(TAG, "openGATTServer(): addCharacteristic() succeeded.")
        bluetoothGattServer =
            bluetoothManager.openGattServer(context, bluetoothGattServerCallback)
        val addServiceResult =
            operationWithRetry { bluetoothGattServer.addService(bluetoothGattService) }
        if (!addServiceResult) {
            Log.e(TAG, "openGATTServer(): addService() failed.")
            return false
        }
        Log.d(TAG, "openGATTServer(): addService() succeeded.")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun closeGATTServer() {
        bluetoothGattServer.clearServices()
        bluetoothGattServer.close()
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertising success: $settingsInEffect")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failure. errorCode: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        Log.d(TAG, "startAdvertising(): started.")
        val settingsBuilder = AdvertiseSettings.Builder()
        settingsBuilder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        settingsBuilder.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        settingsBuilder.setTimeout(0)
        settingsBuilder.setConnectable(true)
        val dataBuilder = AdvertiseData.Builder()
        dataBuilder.setIncludeTxPowerLevel(true)
        dataBuilder.addServiceUuid(ParcelUuid.fromString(UUID_SERVICE.toString()))
        val responseBuilder = AdvertiseData.Builder()
        responseBuilder.setIncludeDeviceName(true)
        bluetoothLeAdvertiser.startAdvertising(
            settingsBuilder.build(), dataBuilder.build(), responseBuilder.build(), advertiseCallback
        )
    }

    override fun advertise() {
        Log.d(TAG, "advertise(): started.")
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        val openGATTServerResult = operationWithRetry { openGATTServer() }
        if (!openGATTServerResult) {
            Log.e(TAG, "advertise(): openGATTServer() failed.")
            return
        }
        Log.d(TAG, "advertise(): openGATTServer() succeeded.")
        startAdvertising()
        Log.d(TAG, "advertise(): startAdvertising() called.")
        isPeripheral = true
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d(
                TAG,
                String.format("onScanResult(callbackType = %d, result = %s)", callbackType, result)
            )
            val bluetoothDevice = result.device
            if (bluetoothDevice.name == null) {
                Log.d(TAG, "onScanResult(): Device name is null. Skip notifying.")
                return
            }
            val broadcastIntent = Intent().setAction(WirelessNetwork.DEVICE_FOUND_ACTION).putExtra(
                WirelessNetwork.EXTRA_FOUND_DEVICE, bluetoothDevice
            )
            context.sendBroadcast(broadcastIntent)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults()")
            for (result in results) {
                Log.d(TAG, "onBatchScanResults(): result: $result")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, String.format("onScanFailed(errorCode = %d)", errorCode))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        Log.d(TAG, "startScan()")
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        bluetoothLeScanner.startScan(scanCallback)
    }

    override fun scan() {
        Log.d(TAG, "discover()")
        startScan()
        isPeripheral = false
    }

    /**
     * Central 側のコールバック
     */
    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            Log.d(
                TAG, String.format(
                    "[Central] onPhyUpdate(gatt = %s, txPhy = %d, rxPhy = %d, status = %d)",
                    gatt.toString(),
                    txPhy,
                    rxPhy,
                    status
                )
            )
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
            Log.d(
                TAG, String.format(
                    "[Central] onPhyRead(gatt = %s, txPhy = %d, rxPhy = %d, status = %d)",
                    gatt.toString(),
                    txPhy,
                    rxPhy,
                    status
                )
            )
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(
                TAG, String.format(
                    "[Central] onConnectionStateChange(gatt = %s, status = %d (code = %d), newState = %d (code = %d))",
                    gatt.toString(),
                    status,
                    status,
                    newState,
                    newState
                )
            )
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "[Central] Start service discovery.")
                    val discoverServicesResult = operationWithRetry { gatt.discoverServices() }
                    if (!discoverServicesResult) {
                        Log.e(
                            TAG, "[Central] onConnectionStateChange(): discoverServices() failed."
                        )
                        return
                    }
                    Log.d(TAG, "[Central] onConnectionStateChange(): discoverServices() succeeded.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val remoteDevice = gatt.device
                    @SuppressLint("MissingPermission") val remoteDeviceName = remoteDevice.name
                    Log.d(
                        TAG,
                        "[Central] Disconnected. Close current GATT. Remote device: $remoteDeviceName"
                    )
                    bluetoothGatt?.close()
                    isConnected = false
                    val broadcastIntent =
                        Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION)
                            .putExtra(
                                WirelessNetwork.EXTRA_CONNECTION_STATUS,
                                WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                            )
                    context.sendBroadcast(broadcastIntent)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(
                TAG, String.format(
                    "[Central] onServicesDiscovered(gatt = %s, status = %d)",
                    gatt.toString(),
                    status
                )
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, String.format("[Central] Status was not GATT_SUCCESS. Got %d", status))
                return
            }
            val service = gatt.getService(UUID_SERVICE)
            if (service == null) {
                Log.e(
                    TAG,
                    String.format("[Central] Service (%s) was not found.", UUID_SERVICE.toString())
                )
                return
            }
            val characteristic = service.getCharacteristic(UUID_CHARACTERISTIC)
            if (characteristic == null) {
                Log.e(
                    TAG, String.format(
                        "[Central] Characteristic (%s) was not found.",
                        UUID_CHARACTERISTIC.toString()
                    )
                )
                return
            }
            val descriptor = characteristic.getDescriptor(UUID_DESCRIPTOR)
            if (descriptor == null) {
                Log.e(
                    TAG, String.format(
                        "[Central] Descriptor (%s) was not found.", UUID_DESCRIPTOR.toString()
                    )
                )
                return
            }
            Log.d(TAG, "[Central] Service, Characteristic, and Descriptor were successfully found.")
            bluetoothGatt = gatt
            remoteDevice = gatt.device
            bluetoothGattCharacteristic = characteristic
            bluetoothLeScanner.stopScan(scanCallback)
            val setCharacteristicNotificationResult = operationWithRetry {
                bluetoothGatt!!.setCharacteristicNotification(
                    characteristic, true
                )
            }
            if (!setCharacteristicNotificationResult) {
                Log.e(
                    TAG, "[Central] onServicesDiscovered(): setCharacteristicNotification() failed."
                )
                return
            }
            Log.d(
                TAG, "[Central] onServicesDiscovered(): setCharacteristicNotification() succeeded."
            )
            val descriptorSetValueResult =
                operationWithRetry { descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) }
            if (!descriptorSetValueResult) {
                Log.e(TAG, "[Central] onServicesDiscovered(): descriptor.setValue() failed.")
                return
            }
            Log.d(TAG, "[Central] onServicesDiscovered(): descriptor.setValue() succeeded.")
            val writeDescriptorResult =
                operationWithRetry { bluetoothGatt!!.writeDescriptor(descriptor) }
            if (!writeDescriptorResult) {
                Log.e(
                    TAG,
                    "[Central] onServicesDiscovered(): writeDescriptor() failed. Failed enabling Notification for Characteristic: " + UUID_CHARACTERISTIC.toString()
                )
                return
            }
            Log.d(TAG, "[Central] onServicesDiscovered(): writeDescriptor() succeeded.")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            Log.d(
                TAG, String.format(
                    "[Central] onCharacteristicRead(gatt = %s, characteristic = %s, status = %d)",
                    gatt.toString(),
                    characteristic.uuid,
                    status
                )
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d(
                TAG, String.format(
                    "[Central] onCharacteristicWrite(gatt = %s, characteristic = %s, status = %d)",
                    gatt.toString(),
                    characteristic.uuid,
                    status
                )
            )
        }

        /**
         * MTUを超えて受信し続ける情報を一時的にメモリに貯めるためのバッファ
         */
        private val byteArrayOutputStream = ByteArrayOutputStream()
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            Log.d(
                TAG, String.format(
                    "[Central] onCharacteristicChanged(gatt = %s, characteristic = %s)",
                    gatt.toString(),
                    characteristic.uuid
                )
            )
            if (UUID_CHARACTERISTIC == characteristic.uuid) {
                val bytes = characteristic.value
                val totalChunkByte = bytes[0]
                val currentChunkByte = bytes[1]
                val headerSize = 2
                val byteSize = bytes.size
                val buffer = ByteArray(byteSize - headerSize)
                System.arraycopy(bytes, headerSize, buffer, 0, byteSize - headerSize)
                val totalChunk = java.lang.Byte.toUnsignedInt(totalChunkByte)
                val currentChunk = java.lang.Byte.toUnsignedInt(currentChunkByte)
                val messageChunk = String(buffer)
                Log.d(
                    TAG, String.format(
                        "[Central] totalChunk = %d, currentChunk = %d, message = %s",
                        totalChunk,
                        currentChunk,
                        messageChunk
                    )
                )
                try {
                    byteArrayOutputStream.write(buffer)
                } catch (e: IOException) {
                    Log.e(TAG, e.message, e)
                }
                if (totalChunk == currentChunk) {
                    val fullMessage = byteArrayOutputStream.toString()
                    byteArrayOutputStream.reset()
                    Log.d(
                        TAG,
                        String.format("[Central] finally full message received: %s", fullMessage)
                    )
                    handler.post { Toast.makeText(context, fullMessage, Toast.LENGTH_SHORT).show() }
                }
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            Log.d(
                TAG, String.format(
                    "[Central] onDescriptorRead(gatt = %s, descriptor = %s, status = %d)",
                    gatt.toString(),
                    descriptor.uuid,
                    status
                )
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.d(
                TAG, String.format(
                    "[Central] onDescriptorWrite(gatt = %s, descriptor = %s, status = %d)",
                    gatt.toString(),
                    descriptor.uuid,
                    status
                )
            )
            @SuppressLint("MissingPermission") val requestMtuResult = operationWithRetry {
                bluetoothGatt!!.requestMtu(
                    MAX_MTU
                )
            }
            Log.d(TAG, "requestMtuResult: $requestMtuResult")

            // Android BLE では、GATT に関わるすべての操作を「シーケンシャル」に行わないと失敗することが知られている。requestMtu -> onMtuChanged のペアも例外ではない。
            // しかし、Pixel 3a XL などの端末が Peripheral の場合に、Central からの requestMtu() は true が返るにも関わらず、onMtuChanged() が永久にコールバックされない事象を観測している。
            // したがって、ここを完全にシーケンシャルにすることは不可能だ。ここでは苦肉の策として「思いやりタイマー」で500ミリ秒待っている。その後、MTUの結果如何に関わらず Peripheral にデバイス名を通知する。
            // TODO この部分の待ち処理を、タイマー付き Queue のようなものを作ってもう少しエレガントに改良する。
            handler.postDelayed({

                // Centralはこの時点ですべての前処理を終えるので「接続完了」状態にする。
                isConnected = true
                val broadcastIntent =
                    Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION).putExtra(
                        WirelessNetwork.EXTRA_CONNECTION_STATUS,
                        WirelessNetwork.EXTRA_CONNECTION_CONNECTED
                    )
                context.sendBroadcast(broadcastIntent)
                Log.d(TAG, "[Central] Central is connected")
                Log.d(TAG, "[Central] onDescriptorWrite(): sendDeviceInfoToPeripheral() succeeded.")
            }, MTU_DELAY)
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
            Log.d(
                TAG, String.format(
                    "[Central] onReliableWriteCompleted(gatt = %s, status = %d)",
                    gatt.toString(),
                    status
                )
            )
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            Log.d(
                TAG, String.format(
                    "[Central] onReadRemoteRssi(gatt = %s, rssi = %d, status = %d)",
                    gatt.toString(),
                    rssi,
                    status
                )
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.d(
                TAG, String.format(
                    "[Central] onMtuChanged(gatt = %s, mtu = %d, status = %d)",
                    gatt.toString(),
                    mtu,
                    status
                )
            )
            currentMTU = mtu
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: BluetoothDevice) {
        Log.d(TAG, String.format("connect(device = %s)", device))
        bluetoothGatt = device.connectGatt(context, false, bluetoothGattCallback)
    }

    override fun write(message: String) {
        Log.d(TAG, "write() message: $message")
        if (!isConnected) {
            Log.d(TAG, "Device not connected. Skip writing.")
            return
        }
        if (isPeripheral) {
            Log.d(TAG, "[Peripheral]: Device is Peripheral.")
            if (bluetoothGattServer != null && remoteDevice != null && bluetoothGattCharacteristic != null) {
                Log.d(
                    TAG,
                    "BluetoothGattServer && connectedDevice && BluetoothGattCharacteristic are ready. Try to notify."
                )
                val sendNotificationResult = operationWithRetry { sendNotification(message) }
                if (!sendNotificationResult) {
                    Log.e(TAG, "write(): sendNotification() failed.")
                    return
                }
                Log.d(TAG, "write(): sendNotification() succeeded.")
            } else {
                Log.e(
                    TAG,
                    "BluetoothGattServer or remoteDevice or BluetoothGattCharacteristic are not ready!"
                )
            }
        } else {
            Log.d(TAG, "[Central]: Device is Central.")
            if (bluetoothGatt != null && bluetoothGattCharacteristic != null) {
                Log.d(TAG, "BluetoothGatt && BluetoothGattCharacteristic are ready. Try to write.")
                val setValueResult =
                    operationWithRetry { bluetoothGattCharacteristic!!.setValue(message) }
                if (!setValueResult) {
                    Log.e(TAG, "write(): setValue() failed.")
                    return
                }
                Log.d(TAG, "write(): setValue() succeeded.")
                if (bluetoothGatt == null) {
                    Log.e(TAG, "bluetoothGatt became null. (This seems to happen sometimes).")
                    return
                }
                @SuppressLint("MissingPermission") val writeCharacteristicResult =
                    operationWithRetry {
                        bluetoothGatt!!.writeCharacteristic(bluetoothGattCharacteristic)
                    }
                if (!writeCharacteristicResult) {
                    Log.e(TAG, "write(): writeCharacteristic() failed.")
                    return
                }
                Log.d(TAG, "write(): writeCharacteristic() succeeded.")
            } else {
                Log.e(TAG, "BluetoothGatt or BluetoothGattCharacteristic are not ready!")
            }
        }
    }

    /**
     * Notificationには分割送信機能はないので、自分でMTUを元に分割する。
     * requestMtu() が不安定なため、[.currentMTU] のデフォルトは [.MIN_MTU] で分割する。
     * 後から復号する必要があるため、
     * 1. 先頭1バイトを総チャンク数
     * 2. 次の1バイトを現在送信しているチャンク数
     * 3. 残りのバイトをペイロード本体
     * というルールにする。つまり、チャンク数 が 1 byte = 8 bits を超えるメッセージは必然的に送れない仕様とした。
     *
     * @param message
     * @return status
     */
    private fun sendNotification(message: String): Boolean {
        Log.d(TAG, "Start sendNotification.")
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        val byteSize = bytes.size
        val headerSize = 2
        val payloadSize = currentMTU - headerSize
        val chunks = (byteSize + payloadSize - 1) / payloadSize
        if (chunks > 1 shl 8) {
            Log.e(
                TAG, String.format(
                    "sendNotification(): Chunk size (%d) is too big. This is due to the size of message (%s)",
                    chunks,
                    message
                )
            )
            return false
        }
        for (i in 0 until chunks) {
            val srcPos = i * payloadSize
            val length = if (srcPos + payloadSize > byteSize) byteSize - srcPos else payloadSize
            val totalChunkByte = chunks.toByte()
            val currentChunkByte = (i + 1).toByte()
            val partialBytes = ByteArray(headerSize + length)
            partialBytes[0] = totalChunkByte
            partialBytes[1] = currentChunkByte
            System.arraycopy(bytes, srcPos, partialBytes, headerSize, length)
            val setValueResult =
                operationWithRetry { bluetoothGattCharacteristic!!.setValue(partialBytes) }
            if (!setValueResult) {
                Log.e(TAG, "sendNotification(): bluetoothGattCharacteristic.setValue() failed.")
                return false
            }
            Log.d(TAG, "sendNotification(): bluetoothGattCharacteristic.setValue() succeeded.")
            if (bluetoothGattServer == null) {
                Log.e(TAG, "sendNotification(): bluetoothGattServer is null.")
                return false
            }
            @SuppressLint("MissingPermission") val notificationResult = operationWithRetry {
                bluetoothGattServer!!.notifyCharacteristicChanged(
                    remoteDevice, bluetoothGattCharacteristic, true
                )
            }
            if (!notificationResult) {
                Log.e(TAG, "sendNotification(): notifyCharacteristicChanged failed.")
                return false
            }
            Log.d(TAG, "sendNotification(): notifyCharacteristicChanged succeeded.")
        }
        Log.d(TAG, "End sendNotification successfully.")
        return true
    }

    private fun operationWithRetry(operation: BooleanSupplier): Boolean {
        Log.d(TAG, "operationWithRetry(): start.")
        var operationResult: Boolean
        var retryCount = 0
        do {
            operationResult = operation.asBoolean
            if (operationResult) {
                Log.d(TAG, "operationWithRetry(): succeeded.")
                return true
            }
            try {
                Log.d(TAG, "operationWithRetry(): failed. Start retrying. retryCount: $retryCount")
                Thread.sleep(RETRY_INTERVAL)
            } catch (e: InterruptedException) {
                Log.e(TAG, e.message, e)
            }
        } while (retryCount++ < RETRY_LIMIT)
        Log.e(TAG, "operationWithRetry(): failed regardless of retries.")
        return false
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        Log.d(TAG, "close()")
        bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
        bluetoothLeScanner.stopScan(scanCallback)
        bluetoothGatt?.close()
        closeGATTServer()
        isConnected = false
        Log.d(TAG, "close(): send connection status.")
        val broadcastIntent =
            Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION).putExtra(
                WirelessNetwork.EXTRA_CONNECTION_STATUS,
                WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
            )
        context.sendBroadcast(broadcastIntent)
    }

    companion object {
        private val TAG = BluetoothLowEnergy::class.java.simpleName
        private const val MAX_MTU = 512
        private const val MIN_MTU = 20
        private val MTU_DELAY = TimeUnit.MILLISECONDS.toMillis(500)
        private const val RETRY_LIMIT = 5
        private val RETRY_INTERVAL = TimeUnit.MILLISECONDS.toMillis(500)
        private val UUID_SERVICE = UUID.fromString("e2d95cf0-72e4-da1a-cf5b-9370077dc9bb")
        private val UUID_CHARACTERISTIC = UUID.fromString("324861d6-75a0-edbe-2700-5bc419d4d6e0")
        private val UUID_DESCRIPTOR = UUID.fromString("33d6abb5-d6a5-7e99-f73e-10fe3e822c68")
    }
}