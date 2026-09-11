package com.taiba.aitextenhancer

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val items: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(val root: LinearLayout, val text: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false) as LinearLayout
        val text = view.findViewById<TextView>(R.id.tvBubbleText)
        return VH(view, text)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        holder.text.text = msg.text
        if (msg.role == "user") {
            holder.text.setBackgroundResource(R.drawable.bg_chat_user)
            holder.root.gravity = Gravity.END
        } else {
            holder.text.setBackgroundResource(R.drawable.bg_chat_assistant)
            holder.root.gravity = Gravity.START
        }
    }

    override fun getItemCount(): Int = items.size

    fun addMessage(msg: ChatMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun updateLast(text: String) {
        if (items.isEmpty()) return
        items[items.size - 1] = items[items.size - 1].copy(text = text)
        notifyItemChanged(items.size - 1)
    }
}
