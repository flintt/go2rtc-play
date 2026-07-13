package com.example.go2rtcplay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.go2rtcplay.data.ServerAddress

class ServerAdapter(
    private val servers: List<ServerAddress>,
    private val onSelect: (ServerAddress) -> Unit,
    private val onDelete: (ServerAddress) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view as CardView
        val nameText: TextView = view.findViewById(R.id.serverName)
        val urlText: TextView = view.findViewById(R.id.serverUrl)
        val stateText: TextView = view.findViewById(R.id.serverState)
        val statusIcon: View = view.findViewById(R.id.statusIcon)
        val deleteBtn: ImageButton = view.findViewById(R.id.deleteBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        val context = holder.itemView.context
        holder.nameText.text = if (server.discovered) "${server.host}:${server.port}" else server.host
        holder.urlText.text = server.url
        holder.stateText.text = context.getString(if (server.enabled) R.string.server_active else R.string.server_saved)
        holder.stateText.setTextColor(context.getColor(if (server.enabled) R.color.accent_green else R.color.text_secondary))
        holder.statusIcon.setBackgroundResource(if (server.enabled) R.drawable.bg_status_active else R.drawable.bg_status_inactive)
        holder.card.setCardBackgroundColor(context.getColor(if (server.enabled) R.color.surface_card_selected else R.color.surface_subtle))
        holder.itemView.setOnClickListener { onSelect(server) }
        holder.deleteBtn.setOnClickListener { onDelete(server) }
    }

    override fun getItemCount() = servers.size
}
