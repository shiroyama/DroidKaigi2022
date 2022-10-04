package us.shiroyama.wireless.network;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class SimpleSocketReader {
    private static final String TAG = SimpleSocketReader.class.getSimpleName();

    // private static final int BUFFER_SIZE = 1024;
    private static final int BUFFER_SIZE = 8192;
    private static final byte[] INCOMING_BUFF = new byte[BUFFER_SIZE];

    // 10MB
    private static final int LENGTH_LIMIT = 1024 * 1024 * 10;
    private static final int RETRY_LIMIT = 5;

    @NonNull
    private final InputStream inputStream;

    @NonNull
    private final Context context;

    @Nullable
    private OnSuccessCallback onSuccessCallback;

    @Nullable
    private OnFailureCallback onFailureCallback;

    public SimpleSocketReader(@NonNull InputStream inputStream, @NonNull Context context) {
        this.inputStream = inputStream;
        this.context = context;
    }

    public void setOnSuccessCallback(@NonNull OnSuccessCallback onSuccessCallback) {
        this.onSuccessCallback = onSuccessCallback;
    }

    public void setOnFailureCallback(@NonNull OnFailureCallback onFailureCallback) {
        this.onFailureCallback = onFailureCallback;
    }

    public void receiveMessage() {
        byte[] buffer = new byte[8192];
        try {
            String filename = System.currentTimeMillis() + ".jpg";
            FileOutputStream fileOutputStream = context.openFileOutput(filename, Context.MODE_PRIVATE);
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, length);
            }
            fileOutputStream.close();
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    public interface OnSuccessCallback {
        void onSuccess(@NonNull byte[] message, int length);
    }

    public interface OnFailureCallback {
        void onFailure(@NonNull Exception exception);
    }
}
