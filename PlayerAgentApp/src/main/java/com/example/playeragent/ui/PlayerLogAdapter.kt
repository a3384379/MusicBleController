package com.example.playeragent.ui

import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlayerLogAdapter : RecyclerView.Adapter<PlayerLogAdapter.LogViewHolder>() {
    private val items = ArrayList<String>(MAX_ROWS)

    fun replace(values: List<String>) {
        items.clear()
        items.addAll(values.takeLast(MAX_ROWS))
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val density = parent.resources.displayMetrics.density
        return LogViewHolder(TextView(parent.context).apply {
            textSize = 11f
            setTextColor(Color.rgb(215, 218, 224))
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            setTextIsSelectable(true)
        })
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.textView.text = items[position]
    }

    override fun getItemCount(): Int = items.size

    class LogViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    companion object {
        const val MAX_ROWS = 300
    }
}
