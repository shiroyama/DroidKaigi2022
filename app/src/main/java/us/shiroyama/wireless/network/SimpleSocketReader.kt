package us.shiroyama.wireless.network

import android.content.Context
import android.util.Log
import us.shiroyama.wireless.network.SimpleSocketReader
import us.shiroyama.wireless.network.SocketReader.OnFailureCallback
import us.shiroyama.wireless.network.SocketReader.OnSuccessCallback
import java.io.IOException
import java.io.InputStream

class SimpleSocketReader(private val inputStream: InputStream, private val context: Context) :
    SocketReader {
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
            val filename = System.currentTimeMillis().toString() + ".jpg"
            Log.d(TAG, "read() filename: $filename")
            val fileOutputStream = context.openFileOutput(filename, Context.MODE_PRIVATE)
            Log.d(TAG, "read() fileOutputStream opened.")
            var length: Int
            Log.d(TAG, "read() fileOutputStream read buffer start.")
            while (inputStream.read(INCOMING_BUFF).also { length = it } != -1) {
                Log.d(TAG, "read() write to fileOutputStream.")
                fileOutputStream.write(INCOMING_BUFF, 0, length)
            }
            Log.d(TAG, "read() fileOutputStream read buffer end.")
            fileOutputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
        } finally {
            Log.d(TAG, "read() end.")
        }
    }

    companion object {
        private val TAG = SimpleSocketReader::class.java.simpleName
        private const val BUFFER_SIZE = 8192
        private val INCOMING_BUFF = ByteArray(BUFFER_SIZE)
    }
}