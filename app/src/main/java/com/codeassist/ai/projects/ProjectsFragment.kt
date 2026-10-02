package com.codeassist.ai.projects

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.R
import com.codeassist.ai.data.Project
import com.codeassist.ai.data.Store
import com.codeassist.ai.workspace.WorkspaceActivity

class ProjectsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: LinearLayout
    private lateinit var labelRecent: TextView
    private lateinit var adapter: ProjectsAdapter
    private var query = ""

    companion object {
        val ICONS = listOf(
            R.drawable.ic_cube, R.drawable.ic_code, R.drawable.ic_edit,
            R.drawable.ic_mobile, R.drawable.ic_database, R.drawable.ic_globe
        )
        val COLORS = listOf(
            0xFF6EC1FF.toInt(), 0xFFB49CFF.toInt(), 0xFF7BE3A8.toInt(),
            0xFFFFB37B.toInt(), 0xFFFF8FB1.toInt(), 0xFFFFD97B.toInt()
        )

        fun timeAgo(t: Long): String {
            val d = System.currentTimeMillis() - t
            val m = d / 60000
            return when {
                m < 1 -> "just now"
                m < 60 -> "${m}m ago"
                m < 1440 -> "${m / 60}h ago"
                else -> "${m / 1440}d ago"
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_projects, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Store.init(requireContext())
        recycler = view.findViewById(R.id.recyclerProjects)
        empty = view.findViewById(R.id.emptyState)
        labelRecent = view.findViewById(R.id.labelRecent)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ProjectsAdapter(
            onClick = { open(it) },
            onMore = { p, anchor -> showProjectMenu(p, anchor) }
        )
        recycler.adapter = adapter

        val searchRow = view.findViewById<LinearLayout>(R.id.searchRow)
        val editSearch = view.findViewById<EditText>(R.id.editSearch)
        view.findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            searchRow.visibility = if (searchRow.visibility == View.VISIBLE) {
                editSearch.setText(""); View.GONE
            } else {
                editSearch.requestFocus(); View.VISIBLE
            }
        }
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; refresh() }
        })

        view.findViewById<View>(R.id.cardNewProject).setOnClickListener { showCreate() }
        view.findViewById<View>(R.id.btnNewProject).setOnClickListener { showCreate() }
        view.findViewById<View>(R.id.btnEmptyNew).setOnClickListener { showCreate() }
        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? com.codeassist.ai.MainActivity)?.openDrawer()
        }
    }

    private fun showCreate() {
        CreateProjectDialog { refresh() }.show(parentFragmentManager, "create")
    }

    private fun open(p: Project) {
        Store.lastProjectId = p.id
        startActivity(Intent(requireContext(), WorkspaceActivity::class.java)
            .putExtra(WorkspaceActivity.EXTRA_PROJECT_ID, p.id))
    }

    private fun showProjectMenu(p: Project, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 0, 0, "Open")
        popup.menu.add(0, 1, 1, "Rename")
        popup.menu.add(0, 2, 2, "Delete")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> open(p)
                1 -> renameDialog(p)
                2 -> confirmDelete(p)
            }
            true
        }
        popup.show()
    }

    private fun renameDialog(p: Project) {
        val edit = EditText(requireContext()).apply {
            setText(p.name); setSingleLine(true)
            setTextColor(0xFFE8EEF5.toInt()); setHintTextColor(0xFF5D6B7C.toInt())
            setBackgroundResource(R.drawable.field_bg)
            setPadding(28, 24, 28, 24)
        }
        val wrap = FrameLayout(requireContext()).apply {
            setPadding(48, 24, 48, 0); addView(edit)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename project")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val n = edit.text.toString().trim()
                if (n.isNotEmpty()) { p.name = n; Store.updateProject(p); refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(p: Project) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete project")
            .setMessage("Delete \"${p.name}\"? Its chats and files will be removed.")
            .setPositiveButton("Delete") { _, _ -> Store.deleteProject(p.id); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        val all = Store.projects()
        val filtered = if (query.isBlank()) all else all.filter {
            it.name.contains(query, true) || it.desc.contains(query, true)
        }
        adapter.submit(filtered)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        labelRecent.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }
}

class ProjectsAdapter(
    val onClick: (Project) -> Unit,
    val onMore: (Project, View) -> Unit
) : RecyclerView.Adapter<ProjectsAdapter.VH>() {

    private val items = mutableListOf<Project>()

    fun submit(list: List<Project>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iconBox: FrameLayout = v.findViewById(R.id.iconBox)
        val icon: ImageView = v.findViewById(R.id.iconProject)
        val name: TextView = v.findViewById(R.id.textName)
        val meta: TextView = v.findViewById(R.id.textMeta)
        val time: TextView = v.findViewById(R.id.textTime)
        val more: ImageButton = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = items[pos]
        h.name.text = p.name
        h.meta.text = "${p.chats} chat${if (p.chats == 1) "" else "s"} · ${p.files.size} file${if (p.files.size == 1) "" else "s"}"
        h.time.text = ProjectsFragment.timeAgo(p.createdAt)
        h.icon.setImageResource(ProjectsFragment.ICONS[p.iconIdx.coerceIn(0, 5)])
        h.icon.setColorFilter(ProjectsFragment.COLORS[p.colorIdx.coerceIn(0, 5)])
        h.itemView.setOnClickListener { onClick(p) }
        h.more.setOnClickListener { onMore(p, it) }
    }

    override fun getItemCount() = items.size
}
