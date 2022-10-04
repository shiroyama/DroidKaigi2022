package us.shiroyama.wireless.network;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

public class SocketMessageReader {
    private static final String TAG = SocketMessageReader.class.getSimpleName();

    private static final int BUFFER_SIZE = 1024;
    private static final byte[] INCOMING_BUFF = new byte[BUFFER_SIZE];

    // 10MB
    private static final int LENGTH_LIMIT = 1024 * 1024 * 10;
    private static final int RETRY_LIMIT = 5;

    @NonNull
    private final InputStream inputStream;

    @Nullable
    private OnSuccessCallback onSuccessCallback;

    @Nullable
    private OnFailureCallback onFailureCallback;

    public SocketMessageReader(@NonNull InputStream inputStream) {
        this.inputStream = inputStream;
    }

    public void setOnSuccessCallback(@NonNull OnSuccessCallback onSuccessCallback) {
        this.onSuccessCallback = onSuccessCallback;
    }

    public void setOnFailureCallback(@NonNull OnFailureCallback onFailureCallback) {
        this.onFailureCallback = onFailureCallback;
    }

    public void receiveMessage() {
        try {
            byte[] lengthBuffer = new byte[4];
            inputStream.read(lengthBuffer);
            long start = System.currentTimeMillis();
            int length = ByteBuffer.wrap(lengthBuffer).getInt();
            Log.d(TAG, "read(): length = " + length);

            if (length > LENGTH_LIMIT) {
                String message = String.format(Locale.US, "Specified length (%d) is too big! The size limit is %d!", length, LENGTH_LIMIT);
                Log.e(TAG, message);
                return;
            }

            byte[] delimiterBuffer = new byte[2];
            inputStream.read(delimiterBuffer);
            char delimiter = ByteBuffer.wrap(delimiterBuffer).getChar();
            if (':' != delimiter) {
                Log.e(TAG, "Wrong delimiter! Corrupted message!");
                return;
            }

            int pageSize = (length + BUFFER_SIZE - 1) / BUFFER_SIZE;
            Log.d(TAG, "read(): pageSize = " + pageSize);
            int totalBytes = 0;
            byte[] messageBuffer = new byte[length];
            int retryCount = 0;
            for (int i = 0; i < pageSize; i++) {
                Log.d(TAG, "read(): totalBytes before reading = " + totalBytes);
                int limit = (i == pageSize - 1) ? length - totalBytes : BUFFER_SIZE;
                Log.d(TAG, "read(): limit = " + limit);
                int incomingBytes = inputStream.read(INCOMING_BUFF, 0, limit);
                Log.d(TAG, "read(): incomingBytes = " + incomingBytes);

                boolean isValid = limit == incomingBytes;
                while (!isValid && retryCount++ <= RETRY_LIMIT) {
                    Log.e(TAG, "limit and incomingBytes are different! Try to recover. retryCount: " + retryCount);
                    int newBytes = limit - incomingBytes;
                    int newIncomingBytes = inputStream.read(INCOMING_BUFF, incomingBytes, newBytes);
                    incomingBytes += newIncomingBytes;
                    isValid = newBytes == newIncomingBytes;
                    if (isValid) {
                        Log.d(TAG, "Yay! Recovery succeeded!");
                    }
                }

                System.arraycopy(INCOMING_BUFF, 0, messageBuffer, totalBytes, incomingBytes);

                totalBytes += incomingBytes;
                Log.d(TAG, "read(): totalBytes after reading = " + totalBytes);
            }

            boolean isValidMessage = length == totalBytes;
            Log.d(TAG, "read(): isValidMessage: " + isValidMessage);
            if (!isValidMessage) {
                Log.e(TAG, "Length header and actual received bytes didn't match! Corrupted message!");
                return;
            }

            long end = System.currentTimeMillis();
            long diff = end - start;
            Log.d(TAG, "diff: " + diff);
            long bytesPerMilli = length / diff;
            Log.d(TAG, "bytesPerMilli: " + bytesPerMilli);

            if (onSuccessCallback != null) {
                onSuccessCallback.onSuccess(messageBuffer, length);
            }
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
            if (onFailureCallback != null) {
                onFailureCallback.onFailure(e);
            }
        }
    }

    public interface OnSuccessCallback {
        void onSuccess(@NonNull byte[] message, int length);
    }

    public interface OnFailureCallback {
        void onFailure(@NonNull Exception exception);
    }
}
