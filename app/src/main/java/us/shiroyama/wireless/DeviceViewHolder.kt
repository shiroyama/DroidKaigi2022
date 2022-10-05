package us.shiroyama.wireless

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    @JvmField
    val textViewDevice: TextView

    @JvmField
    val textViewAddress: TextView

    @JvmField
    val textViewType: TextView

    init {
        textViewDevice = itemView.findViewById(R.id.textViewDevice)
        textViewAddress = itemView.findViewById(R.id.textViewAddress)
        textViewType = itemView.findViewById(R.id.textViewType)
    }
}