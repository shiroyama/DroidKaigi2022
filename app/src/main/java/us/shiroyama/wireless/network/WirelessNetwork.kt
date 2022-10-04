package us.shiroyama.wireless.network

interface WirelessNetwork<T> {
    companion object {
        const val DEVICE_FOUND_ACTION = "us.shiroyama.wireless.network.DEVICE_FOUND_ACTION"
        const val EXTRA_FOUND_DEVICE = "us.shiroyama.wireless.network.EXTRA_FOUND_DEVICE"

        const val CONNECTION_STATUS_CHANGED_ACTION =
            "us.shiroyama.wireless.network.CONNECTION_STATUS_CHANGED_ACTION"
        const val EXTRA_CONNECTION_STATUS = "us.shiroyama.wireless.network.EXTRA_CONNECTION_STATUS"
        const val EXTRA_CONNECTION_DISCONNECTED = 0
        const val EXTRA_CONNECTION_CONNECTED = 1
    }

    fun advertise()
    fun scan()
    fun connect(device: T)
    fun write(message: String)
    fun close()
}