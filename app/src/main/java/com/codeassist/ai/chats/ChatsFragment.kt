package com.codeassist.ai.chats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.data.ChatMeta
import com.codeassist.ai.data.Store

class ChatsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val adapter = ChatsAdapter(
        onClick = { chat -> (activity as? MainActivity)?.openChat(chat.id) },
        onLongClick = { chat -> confirmDelete(chat) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_chats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.recyclerChats)
        empty = view.findViewById(R.id.emptyChats)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
    }

    private fun confirmDelete(chat: ChatMeta) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete chat")
            .setMessage("Delete \"${chat.title}\"?")
            .setPositiveButton("Delete") { _, _ ->
                Store.deleteChat(chat.id)
                onResume()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val list = Store.chats()
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}

class ChatsAdapter(
    private val onClick: (ChatMeta) -> Unit,
    private val onLongClick: (ChatMeta) -> Unit
) : RecyclerView.Adapter<ChatsAdapter.VH>() {

    private val items = mutableListOf<ChatMeta>()

    fun submit(list: List<ChatMeta>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.textTitle)
        val snippet: TextView = v.findViewById(R.id.textSnippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.title.text = c.title
        h.snippet.text = c.snippet.ifBlank { "No messages yet" }
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
    }

    override fun getItemCount() = items.size
}
