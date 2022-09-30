package us.shiroyama.wireless

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

abstract class DeviceAdapter<T>(protected val deviceList: List<T>) :
    RecyclerView.Adapter<DeviceViewHolder>() {
    private var onClickListener: OnClickListener? = null
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.device_row, parent, false)
        val deviceViewHolder = DeviceViewHolder(view)
        if (onClickListener != null) {
            view.setOnClickListener { v: View? -> onClickListener?.onClick(deviceViewHolder) }
        }
        return deviceViewHolder
    }

    override fun getItemCount(): Int {
        return deviceList.size
    }

    fun setOnClickListener(onClickListener: OnClickListener) {
        this.onClickListener = onClickListener
    }

    interface OnClickListener {
        fun onClick(deviceViewHolder: DeviceViewHolder)
    }
}