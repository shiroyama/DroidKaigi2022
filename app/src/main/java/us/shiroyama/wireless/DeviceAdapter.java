package us.shiroyama.wireless;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public abstract class DeviceAdapter<T> extends RecyclerView.Adapter<DeviceViewHolder> {
    @NonNull
    protected final List<T> deviceList;

    @Nullable
    private OnClickListener onClickListener;

    public DeviceAdapter(@NonNull List<T> deviceList) {
        this.deviceList = deviceList;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.device_row, parent, false);
        DeviceViewHolder deviceViewHolder = new DeviceViewHolder(view);
        if (onClickListener != null) {
            view.setOnClickListener(v -> onClickListener.onClick(deviceViewHolder));
        }
        return deviceViewHolder;
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    public void setOnClickListener(@NonNull OnClickListener onClickListener) {
        this.onClickListener = onClickListener;
    }

    public interface OnClickListener {
        void onClick(DeviceViewHolder deviceViewHolder);
    }
}
