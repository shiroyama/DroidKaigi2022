package us.shiroyama.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import us.shiroyama.wireless.BLEActivity
import us.shiroyama.wireless.network.Lifecycle
import us.shiroyama.wireless.network.WirelessNetwork
import us.shiroyama.wireless.network.ble.BluetoothLowEnergy
import java.util.*

class BLEActivity : WirelessActivity() {
    private val TAG = BLEActivity::class.java.simpleName
    private val intentFilter: IntentFilter = object : IntentFilter() {
        init {
            addAction(WirelessNetwork.DEVICE_FOUND_ACTION)
            addAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION)
        }
    }
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WirelessNetwork.DEVICE_FOUND_ACTION -> {
                    run {
                        val device =
                            intent.getParcelableExtra<BluetoothDevice>(WirelessNetwork.EXTRA_FOUND_DEVICE)
                        Log.d(TAG, "DEVICE_FOUND_ACTION: $device")
                        device?.let {
                            if (deviceList.contains(device)) return
                            deviceList.add(it)
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                    run {
                        val connectionStatus = intent.getIntExtra(
                            WirelessNetwork.EXTRA_CONNECTION_STATUS,
                            WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                        )
                        Log.d(TAG, "EXTRA_CONNECTION_STATUS: $connectionStatus")
                        if (connectionStatus == WirelessNetwork.EXTRA_CONNECTION_CONNECTED) {
                            Toast.makeText(applicationContext, "CONNECTED", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            Toast.makeText(applicationContext, "DISCONNECTED", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
                WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION -> {
                    val connectionStatus = intent.getIntExtra(
                        WirelessNetwork.EXTRA_CONNECTION_STATUS,
                        WirelessNetwork.EXTRA_CONNECTION_DISCONNECTED
                    )
                    Log.d(TAG, "EXTRA_CONNECTION_STATUS: $connectionStatus")
                    if (connectionStatus == WirelessNetwork.EXTRA_CONNECTION_CONNECTED) {
                        Toast.makeText(applicationContext, "CONNECTED", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "DISCONNECTED", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }
    private val ble = BluetoothLowEnergy(this)
    private val deviceList: MutableList<BluetoothDevice> = ArrayList()
    private val deviceAdapter = BluetoothDeviceAdapter(deviceList)
    override val contentView: Int
        get() = R.layout.activity_message
    override val lifecycleObject: Lifecycle
        get() = ble
    override val name: String
        get() = "Bluetooth Low Energy"

    private fun checkLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkLocationPermission()
        val recyclerViewDevice = findViewById<RecyclerView>(R.id.recyclerViewDevice)
        val listener = object : DeviceAdapter.OnClickListener {
            @SuppressLint("MissingPermission")
            override fun onClick(viewHolder: DeviceViewHolder) {
                val position = viewHolder.absoluteAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    Log.e(TAG, "getAbsoluteAdapterPosition() returned NO_POSITION")
                    return
                }
                val device = deviceList[position]
                val log = String.format(
                    Locale.US,
                    "onClick: position=%d, device=%s, address=%s",
                    position,
                    device.name,
                    device.address
                )
                Log.d(TAG, log)
                ble.connect(device)
            }
        }
        deviceAdapter.setOnClickListener(listener)
        val layoutManagerDevice: RecyclerView.LayoutManager = LinearLayoutManager(this)
        recyclerViewDevice.adapter = deviceAdapter
        recyclerViewDevice.layoutManager = layoutManagerDevice
        recyclerViewDevice.setHasFixedSize(true)
        val buttonAdvertise = findViewById<Button>(R.id.buttonAdvertise)
        buttonAdvertise.setOnClickListener { view: View? -> ble.advertise() }
        val buttonScan = findViewById<Button>(R.id.buttonScan)
        buttonScan.setOnClickListener { view: View? -> ble.scan() }
        val buttonClose = findViewById<Button>(R.id.buttonClose)
        buttonClose.setOnClickListener { view: View? ->
            ble.close()
            deviceList.clear()
            deviceAdapter.notifyDataSetChanged()
        }
        val buttonSend = findViewById<Button>(R.id.buttonSend)
        buttonSend.setOnClickListener { view: View? ->
            val editTextMessage = findViewById<EditText>(R.id.editTextMessage)
            val message = editTextMessage.text.toString()
            Log.d(TAG, "message: $message")
            ble.write(message)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(broadcastReceiver, intentFilter)
    }

    override fun onPause() {
        unregisterReceiver(broadcastReceiver)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION -> if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Fine location permission is not granted!")
                finish()
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001
    }
}