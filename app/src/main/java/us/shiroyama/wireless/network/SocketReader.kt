package us.shiroyama.wireless.network

interface SocketReader {
    fun read()
    fun setOnSuccessCallback(onSuccessCallback: OnSuccessCallback)
    fun setOnFailureCallback(onFailureCallback: OnFailureCallback)
    interface OnSuccessCallback {
        fun onSuccess(message: ByteArray, length: Int)
    }

    interface OnFailureCallback {
        fun onFailure(exception: Exception)
    }
}