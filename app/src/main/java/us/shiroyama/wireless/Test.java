package us.shiroyama.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;

import us.shiroyama.wireless.network.WirelessNetwork;

public class Test {
    @NonNull
    private final IntentFilter intentFilter = new IntentFilter() {{
        addAction(WirelessNetwork.DEVICE_FOUND_ACTION);
        addAction(WirelessNetwork.CONNECTION_STATUS_CHANGED_ACTION);
    }};

    @NonNull
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

        }
    };
}
