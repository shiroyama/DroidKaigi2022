package us.shiroyama.wireless.network;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class SimpleSocketReader implements SocketReader {
    private static final String TAG = SimpleSocketReader.class.getSimpleName();

    private static final int BUFFER_SIZE = 8192;
    private static final byte[] INCOMING_BUFF = new byte[BUFFER_SIZE];

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

    @Override
    public void setOnSuccessCallback(@NonNull OnSuccessCallback onSuccessCallback) {
        this.onSuccessCallback = onSuccessCallback;
    }

    @Override
    public void setOnFailureCallback(@NonNull OnFailureCallback onFailureCallback) {
        this.onFailureCallback = onFailureCallback;
    }

    @Override
    public void read() {
        Log.d(TAG, "read() start.");
        try {
            String filename = System.currentTimeMillis() + ".jpg";
            Log.d(TAG, "read() filename: " + filename);
            FileOutputStream fileOutputStream = context.openFileOutput(filename, Context.MODE_PRIVATE);
            Log.d(TAG, "read() fileOutputStream opened.");
            int length;
            Log.d(TAG, "read() fileOutputStream read buffer start.");
            while ((length = inputStream.read(INCOMING_BUFF)) != -1) {
                Log.d(TAG, "read() write to fileOutputStream.");
                fileOutputStream.write(INCOMING_BUFF, 0, length);
            }
            Log.d(TAG, "read() fileOutputStream read buffer end.");
            fileOutputStream.close();
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
        } finally {
            Log.d(TAG, "read() end.");
        }
    }
}
