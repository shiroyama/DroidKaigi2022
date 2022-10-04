package us.shiroyama.wireless;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class DeviceViewHolder extends RecyclerView.ViewHolder {
    public final TextView textViewDevice;
    public final TextView textViewAddress;
    public final TextView textViewType;

    public DeviceViewHolder(@NonNull View itemView) {
        super(itemView);
        textViewDevice = itemView.findViewById(R.id.textViewDevice);
        textViewAddress = itemView.findViewById(R.id.textViewAddress);
        textViewType = itemView.findViewById(R.id.textViewType);
    }
}
