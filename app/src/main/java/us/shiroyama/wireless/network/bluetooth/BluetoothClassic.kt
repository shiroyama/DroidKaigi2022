package us.shiroyama.wireless.network.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import us.shiroyama.wireless.network.LengthSocketReader
import us.shiroyama.wireless.network.Lifecycle
import us.shiroyama.wireless.network.SocketReader
import us.shiroyama.wireless.network.SocketReader.OnFailureCallback
import us.shiroyama.wireless.network.SocketReader.OnSuccessCallback
import us.shiroyama.wireless.network.WirelessNetwork
import us.shiroyama.wireless.network.bluetooth.BluetoothClassic
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*

class BluetoothClassic(private val context: Context) : WirelessNetwork<BluetoothDevice>,
    Lifecycle {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothBroadcastReceiver: BluetoothBroadcastReceiver? = null
    private var scanModeBroadcastReceiver: ScanModeBroadcastReceiver? = null
    private var btServerThread: BTServerThread? = null
    private var btClientThread: BTClientThread? = null
    private var sendReceive: SendReceive? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        run {
            scanModeBroadcastReceiver = ScanModeBroadcastReceiver()
            val scanModeIntentFilter = IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            context.registerReceiver(scanModeBroadcastReceiver, scanModeIntentFilter)
        }
        run {
            bluetoothBroadcastReceiver = BluetoothBroadcastReceiver()
            val intentFilter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(bluetoothBroadcastReceiver, intentFilter)
        }
        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {
                Log.d(TAG, "This device doesn't support Bluetooth.")
            } else {
                Log.d(TAG, "This device supports Bluetooth.")
                for (device in bluetoothAdapter!!.bondedDevices) {
                    Log.d(TAG, "name: " + device.name)
                    Log.d(TAG, "address: " + device.address)
                }
            }
        }
    }

    override fun onStart() {}
    override fun onResume() {}
    override fun onPause() {}
    override fun onStop() {}
    override fun onDestroy() {
        context.unregisterReceiver(scanModeBroadcastReceiver)
        context.unregisterReceiver(bluetoothBroadcastReceiver)
        if (btServerThread != null) {
            btServerThread!!.interrupt()
            btServerThread = null
        }
        if (btClientThread != null) {
            btClientThread!!.interrupt()
            btClientThread = null
        }
        if (bluetoothAdapter != null) {
            bluetoothAdapter = null
        }
    }

    @SuppressLint("MissingPermission")
    override fun advertise() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION)
        context.startActivity(intent)
    }

    @SuppressLint("MissingPermission")
    override fun scan() {
        if (bluetoothAdapter != null) {
            if (bluetoothAdapter!!.isDiscovering) {
                bluetoothAdapter!!.cancelDiscovery()
            }
            val discoveryResult = bluetoothAdapter!!.startDiscovery()
            if (!discoveryResult) {
                Log.e(TAG, "discovery failed")
            }
        }
    }

    override fun connect(device: BluetoothDevice) {
        btClientThread = BTClientThread(device)
        btClientThread!!.start()
    }

    override fun write(message: String) {
        if (sendReceive == null) return
        Log.d(TAG, "write: $message")
        val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
        val length = messageBytes.size
        val lengthBytes = ByteBuffer.allocate(4).putInt(length).array()
        Log.d(TAG, "write(): length = $length")
        // writing the length of the message
        sendReceive!!.write(lengthBytes)

        // writing the message delimiter
        val delimiterBytes = ByteBuffer.allocate(2).putChar(':').array()
        sendReceive!!.write(delimiterBytes)

        // writing the message itself
        sendReceive!!.write(messageBytes)
    }

    override fun close() {}
    internal inner class ScanModeBroadcastReceiver : BroadcastReceiver() {
        private fun start() {
            if (btServerThread == null) {
                Log.d(TAG, "BTServerThread is null. Starting.")
                btServerThread = BTServerThread()
                btServerThread!!.start()
            }
        }

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_SCAN_MODE_CHANGED) {
                val modeValue =
                    intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR)
                when (modeValue) {
                    BluetoothAdapter.SCAN_MODE_CONNECTABLE -> {
                        Log.d(TAG, "SCAN_MODE_CONNECTABLE")
                        start()
                    }
                    BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> {
                        Log.d(TAG, "SCAN_MODE_CONNECTABLE_DISCOVERABLE")
                        start()
                    }
                    BluetoothAdapter.SCAN_MODE_NONE -> {
                        Log.d(TAG, "SCAN_MODE_NONE")
                        btServerThread = null
                    }
                    else -> {
                        Log.e(TAG, "Should not reach here.")
                    }
                }
            }
        }
    }

    internal inner class BluetoothBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                @SuppressLint("MissingPermission") val name = device!!.name
                val address = device.address
                @SuppressLint("MissingPermission") val type = device.type
                var typeString: String
                when (type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> {
                        typeString = "Bluetooth Classic"
                    }
                    BluetoothDevice.DEVICE_TYPE_LE -> {
                        typeString = "Bluetooth Low Energy"
                    }
                    BluetoothDevice.DEVICE_TYPE_DUAL -> {
                        typeString = "Bluetooth Dual"
                    }
                    BluetoothDevice.DEVICE_TYPE_UNKNOWN -> {
                        run {}
                        run { typeString = "Bluetooth type unknown" }
                    }
                    else -> {
                        typeString = "Bluetooth type unknown"
                    }
                }
                Log.d(
                    TAG,
                    String.format(
                        "device name = %s, address = %s, type = %s",
                        name,
                        address,
                        typeString
                    )
                )

                // Notify only Bluetooth classic or Dual
                if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC || type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    val broadcastIntent =
                        Intent().setAction(WirelessNetwork.DEVICE_FOUND_ACTION).putExtra(
                            WirelessNetwork.EXTRA_FOUND_DEVICE, device
                        )
                    context.sendBroadcast(broadcastIntent)
                }
            }
        }
    }

    private val handler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                WirelessNetwork.EXTRA_CONNECTION_CONNECTED -> {
                    val broadcastIntent =
                        Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION)
                            .putExtra(
                                WirelessNetwork.EXTRA_CONNECTION_STATUS,
                                WirelessNetwork.EXTRA_CONNECTION_CONNECTED
                            )
                    context.sendBroadcast(broadcastIntent)
                }
                WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED -> {
                    val broadcastIntent =
                        Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION)
                            .putExtra(
                                WirelessNetwork.EXTRA_CONNECTION_STATUS,
                                WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                            )
                    context.sendBroadcast(broadcastIntent)
                }
                else -> {
                    Log.e(TAG, "Unexpected message code arrived: " + msg.what)
                }
            }
        }
    }

    inner class BTServerThread @SuppressLint("MissingPermission") constructor() : Thread() {
        var bluetoothServerSocket: BluetoothServerSocket? = null
        var bluetoothDevice: BluetoothDevice? = null

        init {
            try {
                bluetoothServerSocket = bluetoothAdapter!!.listenUsingRfcommWithServiceRecord(
                    BT_NAME, BT_UUID
                )
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                val socket = bluetoothServerSocket!!.accept()
                bluetoothDevice = socket.remoteDevice
                val message = Message.obtain()
                message.what = WirelessNetwork.EXTRA_CONNECTION_CONNECTED
                message.obj = bluetoothDevice?.name
                handler.sendMessage(message)
                sendReceive = SendReceive(socket)
                sendReceive!!.start()
            } catch (e: IOException) {
                Log.e(TAG, "Error while listening", e)
                if (bluetoothDevice == null) {
                    Log.e(TAG, "bluetoothDevice is null. There's nothing we can do at this moment.")
                    return
                }
                val message = Message.obtain()
                message.what = WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                message.obj = bluetoothDevice?.name
                handler.sendMessage(message)
            }
        }

        fun close() {
            try {
                if (bluetoothServerSocket != null) {
                    bluetoothServerSocket!!.close()
                }
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }
    }

    inner class BTClientThread @SuppressLint("MissingPermission") constructor(var bluetoothDevice: BluetoothDevice) :
        Thread() {
        var bluetoothSocket: BluetoothSocket? = null

        init {
            try {
                bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(BT_UUID)
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                // cancel discovery before connecting
                if (bluetoothAdapter!!.isDiscovering) {
                    bluetoothAdapter!!.cancelDiscovery()
                }
                bluetoothSocket!!.connect()
                val message = Message.obtain()
                message.what = WirelessNetwork.EXTRA_CONNECTION_CONNECTED
                message.obj = bluetoothDevice.name
                handler.sendMessage(message)
                sendReceive = SendReceive(bluetoothSocket)
                sendReceive!!.start()
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
                val message = Message.obtain()
                message.what = WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                message.obj = bluetoothDevice.name
                handler.sendMessage(message)
            }
        }

        fun close() {
            try {
                if (bluetoothSocket != null) {
                    bluetoothSocket!!.close()
                }
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }
    }

    private inner class SendReceive(private val socket: BluetoothSocket?) : Thread() {
        private val handler = Handler(Looper.getMainLooper())
        private val inputStream: InputStream?
        private val outputStream: OutputStream?

        init {
            var tempIn: InputStream? = null
            var tempOut: OutputStream? = null
            try {
                tempIn = socket!!.inputStream
                tempOut = socket.outputStream
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
            inputStream = tempIn
            outputStream = tempOut
        }

        override fun run() {
            val socketReader: SocketReader = LengthSocketReader(inputStream!!)
            val success = object : OnSuccessCallback {
                override fun onSuccess(message: ByteArray, length: Int) {
                    val received = String(
                        message!!
                    )
                    Log.d(TAG, "read onSuccess. received message: $received")
                    handler.post { Toast.makeText(context, received, Toast.LENGTH_SHORT).show() }
                }
            }
            socketReader.setOnSuccessCallback(success)
            val failure = object : OnFailureCallback {
                override fun onFailure(exception: Exception) {
                    Log.e(TAG, "read onFailure.", exception)
                    handler.post {
                        Toast.makeText(context, exception.message, Toast.LENGTH_SHORT).show()
                    }
                    this@BluetoothClassic.close()
                }
            }
            socketReader.setOnFailureCallback(failure)
            while (socket != null && socket.isConnected) {
                socketReader.read()
            }
        }

        fun write(bytes: ByteArray?) {
            try {
                outputStream!!.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
                this@BluetoothClassic.close()
            }
        }

        fun close() {
            try {
                inputStream?.close()
                outputStream?.close()
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }
    }

    companion object {
        private val TAG = BluetoothClassic::class.java.simpleName
        private const val BT_NAME = "BTTEST1"
        private val BT_UUID = UUID.fromString("3550d416-dd4c-20a1-cdc7-67975a85b3f0")
        private const val DISCOVERABLE_DURATION = 300
    }
}