package com.codeassist.ai.workspace

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Project
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.projects.ProjectsFragment
import com.codeassist.ai.ui.AttachSheet
import com.codeassist.ai.ui.AttachmentHelper
import com.codeassist.ai.ui.ComposerController

class WorkspaceActivity : AppCompatActivity() {

    companion object { const val EXTRA_PROJECT_ID = "project_id" }

    private lateinit var project: Project
    private lateinit var helper: AttachmentHelper
    private lateinit var recycler: RecyclerView
    private lateinit var welcome: LinearLayout
    private lateinit var tabs: List<TextView>
    private var tab = 0
    private val listAdapter = SimpleAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_workspace)

        val id = intent.getStringExtra(EXTRA_PROJECT_ID)
        project = Store.project(id) ?: run { finish(); return }
        Store.lastProjectId = project.id

        findViewById<TextView>(R.id.textName).text = project.name
        findViewById<ImageView>(R.id.iconProject).apply {
            setImageResource(ProjectsFragment.ICONS[project.iconIdx.coerceIn(0, 5)])
            setColorFilter(ProjectsFragment.COLORS[project.colorIdx.coerceIn(0, 5)])
        }

        recycler = findViewById(R.id.recyclerTab)
        welcome = findViewById(R.id.welcomeState)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = listAdapter

        tabs = listOf(
            findViewById(R.id.tabChats), findViewById(R.id.tabFiles),
            findViewById(R.id.tabInstructions), findViewById(R.id.tabCode)
        )
        tabs.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }

        helper = AttachmentHelper(this)
        helper.onChanged = {
            // files picked via Upload File land in the project's Files tab
            val names = helper.current.map { it.name }
            if (names.isNotEmpty()) {
                project.files.addAll(names)
                Store.updateProject(project)
                helper.current.clear()
                selectTab(1)
            }
        }

        val composer = ComposerController(findViewById(R.id.composer), AttachmentHelper(this)) { text, atts ->
            val chat = Store.newChat(text.take(42).ifBlank { "Project chat" }, project.id)
            Store.saveMessages(chat.id, mutableListOf(Message(role = Role.USER, text = text, attachments = atts)))
            chat.snippet = text; Store.updateChat(chat)
            openChatInPlace(chat.id)
        }
        composer.hint = "Message..."
        composer.setModelLabel(Store.model)
        composer.onPlusClick = { AttachSheet(helper).show(supportFragmentManager, "attach") }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMore).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 0, 0, "Delete project")
            popup.menu.add(0, 1, 1, "Close")
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> confirmDelete()
                    1 -> finish()
                }
                true
            }
            popup.show()
        }

        findViewById<View>(R.id.btnNewChat).setOnClickListener {
            val chat = Store.newChat("${project.name} chat", project.id)
            openChatInPlace(chat.id)
        }
        findViewById<View>(R.id.btnUploadFile).setOnClickListener {
            AttachSheet(helper).show(supportFragmentManager, "attach")
        }
        findViewById<View>(R.id.btnAddInstruction).setOnClickListener { instructionDialog() }

        selectTab(0)
    }

    private fun instructionDialog() {
        val edit = EditText(this).apply {
            hint = "e.g. Always use Kotlin and Material 3"
            setHintTextColor(0xFF5D6B7C.toInt())
            setTextColor(0xFFE8EEF5.toInt())
            setBackgroundResource(R.drawable.field_bg)
            setPadding(28, 24, 28, 24)
            minLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
        }
        val wrap = FrameLayout(this).apply { setPadding(48, 24, 48, 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Add instruction")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) {
                    project.instructions.add(t)
                    Store.updateProject(project)
                    selectTab(2)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectTab(i: Int) {
        tab = i
        tabs.forEachIndexed { idx, tv ->
            if (idx == i) {
                tv.setBackgroundResource(R.drawable.tab_selected)
                tv.setTextColor(0xFFE8EEF5.toInt())
            } else {
                tv.background = null
                tv.setTextColor(0xFF5D6B7C.toInt())
            }
        }
        refreshList()
    }

    private fun refreshList() {
        val rows = mutableListOf<Row>()
        when (tab) {
            0 -> Store.chats().filter { it.projectId == project.id }.forEach {
                rows.add(Row(it.title, it.snippet.ifBlank { "No messages yet" }, R.drawable.ic_chat, 0xFF6EC1FF.toInt()) {
                    openChatInPlace(it.id)
                })
            }
            1 -> {
                // real files from the project workspace (engine-generated too)
                val root = com.codeassist.ai.engine.ProjectSystem.rootFor(project.id)
                val real = root.walkTopDown().filter { it.isFile }
                    .sortedByDescending { it.lastModified() }.toList()
                real.forEach { f ->
                    rows.add(Row(
                        com.codeassist.ai.engine.Sandbox.displayPath(f),
                        com.codeassist.ai.ui.AttachmentHelper.formatSize(f.length()),
                        R.drawable.ic_file, 0xFFB49CFF.toInt()) {
                        previewFile(f)
                    })
                }
                project.files.forEach { f ->
                    if (real.none { it.name == f })
                        rows.add(Row(f, "Attached file", R.drawable.ic_file, 0xFFB49CFF.toInt()) {})
                }
            }
            2 -> project.instructions.forEach { ins ->
                rows.add(Row(ins, "Instruction", R.drawable.ic_edit, 0xFF7BE3A8.toInt()) {})
            }
            3 -> {
                val root = com.codeassist.ai.engine.ProjectSystem.rootFor(project.id)
                root.walkTopDown().filter {
                    it.isFile && it.extension in listOf("kt", "java", "xml", "html", "css",
                        "js", "json", "gradle", "md", "sql", "swift")
                }.sortedByDescending { it.lastModified() }.forEach { f ->
                    rows.add(Row(com.codeassist.ai.engine.Sandbox.displayPath(f),
                        "Tap to preview", R.drawable.ic_code, 0xFFFFB37B.toInt()) {
                        previewFile(f)
                    })
                }
            }
        }
        listAdapter.submit(rows)
        val isEmpty = rows.isEmpty()
        welcome.visibility = if (isEmpty && tab != 3) View.VISIBLE else View.GONE
        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        project = Store.project(project.id) ?: project
        refreshList()
    }

    private fun previewFile(f: java.io.File) {
        val content = try {
            if (f.length() > 40 * 1024) f.readText().take(40 * 1024) + "\n…(truncated)"
            else f.readText()
        } catch (_: Exception) { "(binary file)" }
        val tv = android.widget.TextView(this).apply {
            text = content
            setTextColor(0xFFE6EBF2.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Bring the main screen forward with this chat open — no separate chat screen. */
    private fun openChatInPlace(chatId: String) {
        Store.activeChatId = chatId
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete project")
            .setMessage("Delete \"${project.name}\"? Its chats and files will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                Store.deleteProject(project.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    data class Row(val title: String, val sub: String, val icon: Int, val tint: Int, val onClick: () -> Unit)

    class SimpleAdapter : RecyclerView.Adapter<SimpleAdapter.VH>() {
        private val items = mutableListOf<Row>()

        fun submit(list: List<Row>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iconProject)
            val name: TextView = v.findViewById(R.id.textName)
            val meta: TextView = v.findViewById(R.id.textMeta)
            val time: TextView = v.findViewById(R.id.textTime)
            val more: View = v.findViewById(R.id.btnMore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = items[pos]
            h.icon.setImageResource(r.icon)
            h.icon.setColorFilter(r.tint)
            h.name.text = r.title
            h.meta.text = r.sub
            h.time.text = ""
            h.more.visibility = View.GONE
            h.itemView.setOnClickListener { r.onClick() }
        }

        override fun getItemCount() = items.size
    }
}
