package com.example.controllerapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

class DeviceAdapter(
    private val context: Context,
    private val devices: List<BluetoothDevice>
) : BaseAdapter() {

    override fun getCount(): Int = devices.size

    override fun getItem(position: Int): BluetoothDevice = devices[position]

    override fun getItemId(position: Int): Long = position.toLong()

    @SuppressLint("MissingPermission")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder: ViewHolder
        val view: LinearLayout

        if (convertView == null) {
            view = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
            }

            val nameTextView = TextView(context).apply {
                textSize = 16f
            }

            val addressTextView = TextView(context).apply {
                textSize = 13f
            }

            view.addView(nameTextView)
            view.addView(addressTextView)
            holder = ViewHolder(nameTextView, addressTextView)
            view.tag = holder
        } else {
            view = convertView as LinearLayout
            holder = view.tag as ViewHolder
        }

        val device = getItem(position)
        holder.nameTextView.text = device.name ?: "Unknown device"
        holder.addressTextView.text = device.address ?: "Unknown address"
        return view
    }

    private data class ViewHolder(
        val nameTextView: TextView,
        val addressTextView: TextView
    )
}
