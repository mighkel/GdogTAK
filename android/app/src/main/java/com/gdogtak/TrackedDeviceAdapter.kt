package com.gdogtak

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Tracked device info for the device list.
 */
data class TrackedDevice(
    val id: String,            // Unique device identifier
    val label: String,         // Display label (callsign or "Handheld")
    val isCollar: Boolean,     // true = collar, false = handheld
    val latitude: Double,
    val longitude: Double,
    val lastUpdateTime: Long,  // System.currentTimeMillis()
    val bearing: Double?,      // Bearing from handheld, null if this IS the handheld
    val distanceMeters: Double?, // Distance from handheld, null if this IS the handheld
    val deviceType: String = if (isCollar) "collar" else "handheld" // collar, handheld, contact
)

/**
 * RecyclerView adapter for the tracked device list.
 */
class TrackedDeviceAdapter(
    private val imperial: Boolean
) : ListAdapter<TrackedDevice, TrackedDeviceAdapter.ViewHolder>(DIFF_CALLBACK) {

    var useImperial: Boolean = imperial

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.textDeviceIcon)
        val label: TextView = view.findViewById(R.id.textDeviceLabel)
        val position: TextView = view.findViewById(R.id.textDevicePosition)
        val distance: TextView = view.findViewById(R.id.textDeviceDistance)
        val bearing: TextView = view.findViewById(R.id.textDeviceBearing)
        val age: TextView = view.findViewById(R.id.textDeviceAge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tracked_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)

        // Icon: dog for collar, radio for handheld, antenna for contact
        holder.icon.text = when (device.deviceType) {
            "collar" -> "\uD83D\uDC15"   // dog
            "contact" -> "\uD83D\uDCE1"  // satellite antenna
            else -> "\uD83D\uDCF1"       // phone (handheld)
        }

        holder.label.text = device.label
        holder.position.text = "%.4f, %.4f".format(device.latitude, device.longitude)

        if (device.distanceMeters != null && device.bearing != null) {
            holder.distance.text = GeoUtils.formatDistance(device.distanceMeters, useImperial)
            holder.bearing.text = GeoUtils.formatBearingFull(device.bearing)
            holder.distance.visibility = View.VISIBLE
            holder.bearing.visibility = View.VISIBLE
        } else {
            holder.distance.visibility = View.GONE
            holder.bearing.visibility = View.GONE
        }

        // Age
        val ageMs = System.currentTimeMillis() - device.lastUpdateTime
        val ageSec = ageMs / 1000
        holder.age.text = when {
            ageSec < 5 -> "now"
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            else -> "${ageSec / 3600}h ago"
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TrackedDevice>() {
            override fun areItemsTheSame(old: TrackedDevice, new: TrackedDevice) = old.id == new.id
            override fun areContentsTheSame(old: TrackedDevice, new: TrackedDevice) = old == new
        }
    }
}
