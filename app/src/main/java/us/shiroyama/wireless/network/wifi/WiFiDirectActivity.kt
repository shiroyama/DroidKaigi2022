package us.shiroyama.wireless.network.wifi

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo

interface WiFiDirectActivity {
    fun setIsWifiP2pEnabled(isWifiP2pEnabled: Boolean)
    fun onDeviceListChange(deviceList: List<WifiP2pDevice>)
    fun onP2PConnected(wifiP2pInfo: WifiP2pInfo, wifiP2pGroup: WifiP2pGroup)
    fun resetData()
}