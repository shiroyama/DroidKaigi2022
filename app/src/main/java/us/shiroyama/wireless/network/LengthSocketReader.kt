package us.shiroyama.wireless.network

import android.util.Log
import us.shiroyama.wireless.network.LengthSocketReader
import us.shiroyama.wireless.network.SocketReader.OnFailureCallback
import us.shiroyama.wireless.network.SocketReader.OnSuccessCallback
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*

class LengthSocketReader(private val inputStream: InputStream) : SocketReader {
    private var onSuccessCallback: OnSuccessCallback? = null
    private var onFailureCallback: OnFailureCallback? = null
    override fun setOnSuccessCallback(onSuccessCallback: OnSuccessCallback) {
        this.onSuccessCallback = onSuccessCallback
    }

    override fun setOnFailureCallback(onFailureCallback: OnFailureCallback) {
        this.onFailureCallback = onFailureCallback
    }

    override fun read() {
        Log.d(TAG, "read() start.")
        try {
            val lengthBuffer = ByteArray(4)
            inputStream.read(lengthBuffer)
            val length = ByteBuffer.wrap(lengthBuffer).int
            Log.d(TAG, "read(): length = $length")
            if (length > LENGTH_LIMIT) {
                val message = String.format(
                    Locale.US,
                    "Specified length (%d) is too big! The size limit is %d!",
                    length,
                    LENGTH_LIMIT
                )
                Log.e(TAG, message)
                return
            }
            val delimiterBuffer = ByteArray(2)
            inputStream.read(delimiterBuffer)
            val delimiter = ByteBuffer.wrap(delimiterBuffer).char
            if (':' != delimiter) {
                Log.e(TAG, "Wrong delimiter! Corrupted message!")
                return
            }
            val pageSize = (length + BUFFER_SIZE - 1) / BUFFER_SIZE
            Log.d(TAG, "read(): pageSize = $pageSize")
            var totalBytes = 0
            val messageBuffer = ByteArray(length)
            var retryCount = 0
            for (i in 0 until pageSize) {
                Log.d(TAG, "read(): totalBytes before reading = $totalBytes")
                val limit = if (i == pageSize - 1) length - totalBytes else BUFFER_SIZE
                Log.d(TAG, "read(): limit = $limit")
                var incomingBytes = inputStream.read(INCOMING_BUFF, 0, limit)
                Log.d(TAG, "read(): incomingBytes = $incomingBytes")
                var isValid = limit == incomingBytes
                while (!isValid && retryCount++ <= RETRY_LIMIT) {
                    Log.e(
                        TAG,
                        "limit and incomingBytes are different! Try to recover. retryCount: $retryCount"
                    )
                    val newBytes = limit - incomingBytes
                    val newIncomingBytes = inputStream.read(INCOMING_BUFF, incomingBytes, newBytes)
                    incomingBytes += newIncomingBytes
                    isValid = newBytes == newIncomingBytes
                    if (isValid) {
                        Log.d(TAG, "Yay! Recovery succeeded!")
                    }
                }
                System.arraycopy(INCOMING_BUFF, 0, messageBuffer, totalBytes, incomingBytes)
                totalBytes += incomingBytes
                Log.d(TAG, "read(): totalBytes after reading = $totalBytes")
            }
            val isValidMessage = length == totalBytes
            Log.d(TAG, "read(): isValidMessage: $isValidMessage")
            if (!isValidMessage) {
                Log.e(
                    TAG, "Length header and actual received bytes didn't match! Corrupted message!"
                )
                return
            }
            onSuccessCallback?.onSuccess(messageBuffer, length)
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
            onFailureCallback?.onFailure(e)
        } finally {
            Log.d(TAG, "read() end.")
        }
    }

    companion object {
        private val TAG = LengthSocketReader::class.java.simpleName
        private const val BUFFER_SIZE = 1024
        private val INCOMING_BUFF = ByteArray(BUFFER_SIZE)

        // 10MB
        private const val LENGTH_LIMIT = 1024 * 1024 * 10
        private const val RETRY_LIMIT = 5
    }
}