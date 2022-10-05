package us.shiroyama.wireless.network;

import androidx.annotation.NonNull;

public interface SocketReader {
    void read();

    void setOnSuccessCallback(@NonNull OnSuccessCallback onSuccessCallback);

    void setOnFailureCallback(@NonNull OnFailureCallback onFailureCallback);

    interface OnSuccessCallback {
        void onSuccess(@NonNull byte[] message, int length);
    }

    interface OnFailureCallback {
        void onFailure(@NonNull Exception exception);
    }
}
