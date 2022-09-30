package us.shiroyama.wireless.network.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import us.shiroyama.wireless.network.LengthSocketReader
import us.shiroyama.wireless.network.Lifecycle
import us.shiroyama.wireless.network.SocketReader
import us.shiroyama.wireless.network.SocketReader.OnFailureCallback
import us.shiroyama.wireless.network.SocketReader.OnSuccessCallback
import us.shiroyama.wireless.network.WirelessNetwork
import us.shiroyama.wireless.network.wifi.WiFiDirect
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class WiFiDirect(private val context: Context) : WirelessNetwork<WifiP2pDevice>, Lifecycle,
    WiFiDirectActivity {
    private val intentFilter: IntentFilter = object : IntentFilter() {
        init {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isWifiP2pEnabled = false
    private var wifiP2pInfo: WifiP2pInfo? = null
    private var isConnected = false
    private var isHost = false
    private var serverThread: ServerThread? = null
    private var clientThread: ClientThread? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        initP2p()
    }

    override fun onStart() {}
    override fun onResume() {
        receiver = WiFiDirectBroadcastReceiver(manager!!, channel!!, this)
        context.registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        context.unregisterReceiver(receiver)
    }

    override fun onStop() {}
    override fun onDestroy() {}

    @SuppressLint("MissingPermission")
    override fun advertise() {
        manager!!.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers onSuccess")
            }

            override fun onFailure(i: Int) {
                Log.e(TAG, "discoverPeers onFailure: $i")
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun scan() {
        manager!!.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers onSuccess")
            }

            override fun onFailure(i: Int) {
                Log.e(TAG, "discoverPeers onFailure: $i")
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress
        config.wps.setup = WpsInfo.PBC
        manager!!.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect onSuccess")
            }

            override fun onFailure(i: Int) {
                Log.e(TAG, "connect onFailure: $i")
            }
        })
    }

    override fun write(message: String) {
        if (serverThread == null && clientThread == null) return
        Log.d(TAG, "message: $message")
        val executorService = Executors.newSingleThreadExecutor()
        executorService.submit {
            if (isHost) {
                if (serverThread == null) {
                    Log.d(TAG, "serverThread is null!")
                    return@submit
                }
                serverThread!!.writeString(message)
            } else {
                if (clientThread == null) {
                    Log.d(TAG, "clientThread is null!")
                    return@submit
                }
                clientThread!!.writeString(message)
            }
        }
    }

    override fun close() {
        isConnected = false
        manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "disconnect onSuccess")
            }

            override fun onFailure(i: Int) {
                Log.e(TAG, "disconnect onFailure: $i")
            }
        })
        if (clientThread != null) {
            clientThread!!.close()
            clientThread = null
        }
        if (serverThread != null) {
            serverThread!!.close()
            serverThread = null
        }
        val broadcastIntent =
            Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION).putExtra(
                WirelessNetwork.EXTRA_CONNECTION_STATUS, WirelessNetwork.EXTRA_CONNECTION_CONNECTED
            )
        context.sendBroadcast(broadcastIntent)
    }

    override fun setIsWifiP2pEnabled(isWifiP2pEnabled: Boolean) {
        Log.d(TAG, "setIsWifiP2pEnabled: $isWifiP2pEnabled")
        this.isWifiP2pEnabled = isWifiP2pEnabled
    }

    override fun onDeviceListChange(deviceList: List<WifiP2pDevice>) {
        for (wifiP2pDevice in deviceList) {
            val broadcastIntent = Intent().setAction(WirelessNetwork.DEVICE_FOUND_ACTION).putExtra(
                WirelessNetwork.EXTRA_FOUND_DEVICE, wifiP2pDevice
            )
            context.sendBroadcast(broadcastIntent)
        }
    }

    override fun onP2PConnected(wifiP2pInfo: WifiP2pInfo, wifiP2pGroup: WifiP2pGroup) {
        Log.d(TAG, "wifiP2pInfo: $wifiP2pInfo")
        this.wifiP2pInfo = wifiP2pInfo
        Log.d(TAG, "wifiP2pGroup.getClientList().size() " + wifiP2pGroup.clientList.size)
        for (wifiP2pDevice in wifiP2pGroup.clientList) {
            Log.d(TAG, "wifiP2pDevice: $wifiP2pDevice")
        }
        if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
            Log.d(TAG, "You are server!")
            isConnected = true
            val size = wifiP2pGroup.clientList.size
            if (size == 0) {
                Log.e(TAG, "You are server, but no client associated. This is a race condition.")
                return
            }
            val broadcastIntent =
                Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION).putExtra(
                    WirelessNetwork.EXTRA_CONNECTION_STATUS,
                    WirelessNetwork.EXTRA_CONNECTION_CONNECTED
                )
            context.sendBroadcast(broadcastIntent)
            isHost = true
            if (serverThread != null) {
                Log.d(TAG, "serverThread already initialized! skipping.")
                return
            }
            serverThread = ServerThread()
            serverThread!!.start()
        } else if (wifiP2pInfo.groupFormed) {
            Log.d(TAG, "You are client!")
            isConnected = true
            val owner = wifiP2pGroup.owner
            if (owner == null) {
                Log.e(TAG, "You are client, but no owner associated. This is a race condition.")
                return
            }
            val broadcastIntent =
                Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION).putExtra(
                    WirelessNetwork.EXTRA_CONNECTION_STATUS,
                    WirelessNetwork.EXTRA_CONNECTION_CONNECTED
                )
            context.sendBroadcast(broadcastIntent)
            isHost = false
            if (clientThread != null) {
                Log.d(TAG, "clientThread already initialized! skipping.")
                return
            }
            clientThread = ClientThread()
            clientThread!!.start()
        } else {
            Log.e(TAG, "Something is wrong!")
            isConnected = false
            val broadcastIntent =
                Intent().setAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION).putExtra(
                    WirelessNetwork.EXTRA_CONNECTION_STATUS,
                    WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                )
            context.sendBroadcast(broadcastIntent)
        }
    }

    override fun resetData() {
        Log.e(TAG, "resetData() is called. This is either WiFi is disabled or disconnected.")
        isConnected = false
    }

    private fun initP2p(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            Log.e(TAG, "Wi-Fi Direct is not supported by this device.")
            return false
        }
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager == null) {
            Log.e(TAG, "Cannot get Wi-Fi system service.")
            return false
        }
        if (!wifiManager.isP2pSupported) {
            Log.e(TAG, "Wi-Fi Direct is not supported by the hardware or Wi-Fi is off.")
            return false
        }
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "Cannot get Wi-Fi Direct system service.")
            return false
        }
        channel = manager!!.initialize(context.applicationContext, context.mainLooper) {
            Log.d(
                TAG, "onChannelDisconnected"
            )
        }
        if (channel == null) {
            Log.e(TAG, "Cannot initialize Wi-Fi Direct.")
            return false
        }
        return true
    }

    private abstract inner class MessageThread : Thread() {
        private val handler = Handler(Looper.getMainLooper())
        private var socket: Socket? = null
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null

        @Throws(IOException::class)
        abstract fun initializeSocket(): Socket?
        override fun run() {
            try {
                socket = initializeSocket()
                inputStream = socket!!.getInputStream()
                outputStream = socket!!.getOutputStream()
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
                isConnected = false
            }
            read()
        }

        private fun read() {
            while (socket != null && !socket!!.isClosed) {
                Log.d(TAG, "read(): loop starts.")
                val socketReader: SocketReader = LengthSocketReader(inputStream!!)
                val success = object : OnSuccessCallback {
                    override fun onSuccess(message: ByteArray, length: Int) {
                        val received = String(
                            message
                        )
                        Log.d(TAG, "read onSuccess. received message: $received")
                        handler.post {
                            Toast.makeText(context, received, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                socketReader.setOnSuccessCallback(success)
                val failure = object : OnFailureCallback {
                    override fun onFailure(exception: Exception) {
                        Log.e(TAG, "read onFailure.", exception)
                        handler.post {
                            Toast.makeText(context, exception.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                socketReader.setOnFailureCallback(failure)
                Log.d(TAG, "socketReader.read(): starts.")
                socketReader.read()
            }
        }

        fun writeString(message: String) {
            Log.d(TAG, "write(): message = $message")
            val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
            val length = messageBytes.size
            val lengthBytes = ByteBuffer.allocate(4).putInt(length).array()
            Log.d(TAG, "write(): length = $length")
            // writing the length of the message
            write(lengthBytes)
            Log.d(TAG, "write(): delimiter bytes")
            // writing the message delimiter
            val delimiterBytes = ByteBuffer.allocate(2).putChar(':').array()
            write(delimiterBytes)

            // writing the message itself
            Log.d(TAG, "write(): message bytes")
            write(messageBytes)
            Log.d(TAG, "write(): end")
        }

        fun write(bytes: ByteArray?) {
            try {
                outputStream!!.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
                this@WiFiDirect.close()
            }
        }

        open fun close() {
            try {
                if (outputStream != null) {
                    outputStream!!.close()
                }
                if (inputStream != null) {
                    inputStream!!.close()
                }
                if (socket != null) {
                    socket!!.close()
                }
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }
    }

    private inner class ClientThread : MessageThread() {
        @Throws(IOException::class)
        override fun initializeSocket(): Socket? {
            val socket = Socket()
            socket.connect(
                InetSocketAddress(wifiP2pInfo!!.groupOwnerAddress, SOCKET_PORT), SOCKET_TIMEOUT
            )
            return socket
        }
    }

    private inner class ServerThread : MessageThread() {
        private var serverSocket: ServerSocket? = null

        @Throws(IOException::class)
        override fun initializeSocket(): Socket? {
            serverSocket = ServerSocket(SOCKET_PORT)
            return serverSocket!!.accept()
        }

        override fun close() {
            super.close()
            try {
                if (serverSocket != null) {
                    serverSocket!!.close()
                }
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }
    }

    companion object {
        private val TAG = WiFiDirect::class.java.simpleName
        private const val SOCKET_PORT = 8988
        private const val SOCKET_TIMEOUT = 500
    }
}