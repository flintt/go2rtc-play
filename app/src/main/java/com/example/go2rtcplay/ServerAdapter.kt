package com.example.go2rtcplay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.go2rtcplay.data.ServerAddress

class ServerAdapter(
    private val servers: List<ServerAddress>,
    private val onSelect: (ServerAddress) -> Unit,
    private val onDelete: (ServerAddress) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.serverName)
        val urlText: TextView = view.findViewById(R.id.serverUrl)
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
        holder.nameText.text = if (server.discovered) "${server.host}:${server.port}" else server.host
        holder.urlText.text = server.url
        holder.statusIcon.setBackgroundResource(
            if (server.enabled) android.R.drawable.presence_online
            else android.R.drawable.presence_offline
        )
        holder.itemView.setOnClickListener { onSelect(server) }
        holder.deleteBtn.setOnClickListener { onDelete(server) }
    }

    override fun getItemCount() = servers.size
}
