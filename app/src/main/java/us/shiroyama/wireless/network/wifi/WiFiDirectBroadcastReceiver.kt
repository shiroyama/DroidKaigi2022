package us.shiroyama.wireless.network.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener
import android.util.Log
import us.shiroyama.wireless.network.wifi.WiFiDirectBroadcastReceiver

class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: WiFiDirectActivity
) : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                Log.d(TAG, "WIFI_P2P_STATE_CHANGED_ACTION")
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                Log.d(TAG, "P2P state changed: $state")
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // Wifi Direct mode is enabled
                    activity.setIsWifiP2pEnabled(true)
                } else {
                    activity.setIsWifiP2pEnabled(false)
                    activity.resetData()
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION")
                manager.requestPeers(channel) { wifiP2pDeviceList: WifiP2pDeviceList ->
                    val deviceList = ArrayList(wifiP2pDeviceList.deviceList)
                    activity.onDeviceListChange(deviceList)
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION")
                val networkInfo =
                    intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                val wifiP2pGroup =
                    intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                if (networkInfo!!.isConnected) {
                    manager.requestConnectionInfo(
                        channel,
                        ConnectionInfoListener { wifiP2pInfo: WifiP2pInfo ->
                            wifiP2pGroup?.let {
                                Log.d(TAG, "wifiP2pInfo: $wifiP2pInfo")
                                activity.onP2PConnected(wifiP2pInfo, wifiP2pGroup)
                            }
                        })
                } else {
                    activity.resetData()
                }
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.d(TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION")
                val wifiP2pDevice =
                    intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                Log.d(
                    TAG,
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION + " wifiP2pDevice: " + wifiP2pDevice.toString()
                )
            }
        }
    }

    companion object {
        private val TAG = WiFiDirectBroadcastReceiver::class.java.simpleName
    }
}