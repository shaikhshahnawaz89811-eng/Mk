package com.codeassist.ai.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.R
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.MsgKind
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.engine.ActivityEvent
import com.codeassist.ai.engine.EngineStore
import com.codeassist.ai.engine.EventBus
import com.codeassist.ai.engine.EventListener
import com.codeassist.ai.engine.EventStatus
import com.codeassist.ai.engine.EventVisibility
import com.codeassist.ai.engine.InterruptionState
import com.codeassist.ai.ui.AttachmentHelper

/**
 * Message list: user bubbles, plain AI text, live activity timelines
 * (Spec §12) and inline interruption cards (Spec §13/§14).
 * Granular change notifications — no full-list flicker.
 */
class MessagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Message>()
    private var thinkingVisible = false

    var onMessageAction: ((Message, String) -> Unit)? = null       // edit/copy/retry/delete
    var onInterruptionDecision: ((runId: String, decision: String) -> Unit)? = null
    var onOpenArtifact: ((String) -> Unit)? = null

    /** Full reload — only used when opening a chat. */
    fun submit(list: List<Message>) {
        items.clear()
        items.addAll(list)
        thinkingVisible = false
        notifyDataSetChanged()
    }

    fun insert(m: Message) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    fun update(m: Message) {
        val i = items.indexOfFirst { it.id == m.id }
        if (i >= 0) { items[i] = m; notifyItemChanged(i) }
    }

    fun remove(m: Message) {
        val i = items.indexOfFirst { it.id == m.id }
        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
    }

    fun refreshRun(runId: String) {
        val i = items.indexOfFirst { it.runId == runId }
        if (i >= 0) notifyItemChanged(i)
    }

    fun showThinking(v: Boolean) {
        if (thinkingVisible == v) return
        thinkingVisible = v
        if (v) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
    }

    override fun getItemViewType(position: Int): Int {
        if (thinkingVisible && position == items.size) return 2
        val m = items[position]
        return when {
            m.role == Role.USER -> 0
            m.kind == MsgKind.ACTIVITY -> 3
            m.kind == MsgKind.APPROVAL -> 4
            else -> 1
        }
    }

    override fun getItemCount() = items.size + if (thinkingVisible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> UserVH(inf.inflate(R.layout.item_msg_user, parent, false))
            1 -> AiVH(inf.inflate(R.layout.item_msg_ai, parent, false))
            3 -> ActivityVH(inf.inflate(R.layout.item_msg_activity, parent, false))
            4 -> ApprovalVH(inf.inflate(R.layout.item_msg_approval, parent, false))
            else -> ThinkingVH(inf.inflate(R.layout.item_msg_thinking, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (h) {
            is UserVH -> h.bind(items[pos]) { action -> onMessageAction?.invoke(items[pos], action) }
            is AiVH -> h.bind(items[pos], { action -> onMessageAction?.invoke(items[pos], action) },
                { path -> onOpenArtifact?.invoke(path) })
            is ActivityVH -> h.bind(items[pos])
            is ApprovalVH -> h.bind(items[pos]) { runId, decision ->
                onInterruptionDecision?.invoke(runId, decision)
            }
        }
    }

    override fun onViewAttachedToWindow(h: RecyclerView.ViewHolder) {
        if (h is ThinkingVH) h.attach()
        if (h is ActivityVH) h.attach()
    }

    override fun onViewDetachedFromWindow(h: RecyclerView.ViewHolder) {
        if (h is ThinkingVH) h.detach()
        if (h is ActivityVH) h.detach()
    }

    companion object {
        fun applyTextSize(t: TextView) {
            t.textSize = when (Store.textSize) {
                "Small" -> 12.5f
                "Large" -> 16.5f
                else -> 14f
            }
        }
    }

    // ------------------------------------------------------------------
    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        private val text: TextView = v.findViewById(R.id.textMsg)
        private val attachBox: LinearLayout = v.findViewById(R.id.attachBox)

        fun bind(m: Message, onAction: (String) -> Unit) {
            text.visibility = if (m.text.isBlank()) View.GONE else View.VISIBLE
            text.text = m.text
            applyTextSize(text)
            itemView.setOnLongClickListener {
                val actions = arrayOf("Edit", "Copy", "Retry", "Delete")
                android.app.AlertDialog.Builder(itemView.context)
                    .setItems(actions) { _, i -> onAction(actions[i].lowercase()) }
                    .show()
                true
            }
            attachBox.removeAllViews()
            if (m.attachments.isEmpty()) {
                attachBox.visibility = View.GONE
            } else {
                attachBox.visibility = View.VISIBLE
                val ctx = attachBox.context
                for (a in m.attachments) {
                    if (a.kind == AttachKind.IMAGE) {
                        val root = LayoutInflater.from(ctx)
                            .inflate(R.layout.item_attachment_msg, attachBox, false)
                        val iv = root.findViewById<ImageView>(R.id.imageAttach)
                        try {
                            val imageUri = a.localPath?.takeIf { java.io.File(it).isFile }?.let {
                                Uri.fromFile(java.io.File(it))
                            } ?: Uri.parse(a.uri)
                            iv.setImageURI(imageUri)
                        } catch (_: Exception) {
                            iv.setImageResource(R.drawable.ic_image)
                        }
                        attachBox.addView(root)
                    } else {
                        val chip = LayoutInflater.from(ctx)
                            .inflate(R.layout.item_attachment, attachBox, false)
                        chip.findViewById<TextView>(R.id.textAttachName).text = a.name
                        chip.findViewById<TextView>(R.id.textAttachSize).text =
                            AttachmentHelper.formatSize(a.size)
                        chip.findViewById<ImageView>(R.id.iconAttach).setImageResource(
                            if (a.kind == AttachKind.ZIP) R.drawable.ic_zip else R.drawable.ic_file
                        )
                        chip.findViewById<View>(R.id.btnRemoveAttach).visibility = View.GONE
                        attachBox.addView(chip)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    class AiVH(v: View) : RecyclerView.ViewHolder(v) {
        private val text: TextView = v.findViewById(R.id.textMsg)
        private val fileCard: LinearLayout = v.findViewById(R.id.fileCard)
        private val fileName: TextView = v.findViewById(R.id.textFileName)
        private val fileType: TextView = v.findViewById(R.id.textFileType)

        fun bind(m: Message, onAction: (String) -> Unit, onOpen: (String) -> Unit) {
            text.text = markdownLite(m.text)
            applyTextSize(text)
            if (m.fileName != null) {
                fileCard.visibility = View.VISIBLE
                fileName.text = java.io.File(m.fileName!!).name
                fileType.text = m.fileType ?: "Artifact"
                fileCard.setOnClickListener { onOpen(m.fileName) }
            } else {
                fileCard.visibility = View.GONE
                fileCard.setOnClickListener(null)
            }
            itemView.setOnLongClickListener {
                val actions = arrayOf("Copy", "Retry", "Report problem")
                android.app.AlertDialog.Builder(itemView.context)
                    .setItems(actions) { _, i -> onAction(actions[i].lowercase()) }
                    .show()
                true
            }
        }

        /** minimal **bold** + `code` rendering without extra deps */
        private fun markdownLite(s: String): CharSequence {
            val sp = android.text.SpannableStringBuilder(s)
            Regex("\\*\\*([^*]+)\\*\\*").findAll(s).toList().asReversed().forEach { m ->
                sp.replace(m.range.first, m.range.last + 1, m.groupValues[1])
                sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    m.range.first, m.range.first + m.groupValues[1].length, 0)
            }
            return sp
        }
    }

    // ------------------------------------------------------------------
    // Live activity timeline — Spec §12
    // ------------------------------------------------------------------
    class ActivityVH(v: View) : RecyclerView.ViewHolder(v), EventListener {
        private val title: TextView = v.findViewById(R.id.textActivityTitle)
        private val stepsBox: LinearLayout = v.findViewById(R.id.stepsBox)
        private val detailBox: LinearLayout = v.findViewById(R.id.detailBox)
        private val textDetail: TextView = v.findViewById(R.id.textDetail)
        private val btnExpand: ImageView = v.findViewById(R.id.btnExpand)
        private var runId: String? = null
        private var expanded = false

        // --- live animation state (Spec §12: status line + active step shimmer) ---
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var dotPhase = 0
        private var headlineBase = ""
        private var runActive = false
        private val pulseAnims = mutableListOf<ObjectAnimator>()
        private val activeLabels = mutableListOf<Pair<TextView, String>>() // label -> base text
        private val ticker = object : Runnable {
            override fun run() {
                dotPhase = (dotPhase + 1) % 4
                val dots = ".".repeat(if (dotPhase == 0) 3 else dotPhase)
                if (runActive) title.text = headlineBase + dots
                for ((label, base) in activeLabels) label.text = base + dots
                if (runActive) handler.postDelayed(this, 450)
            }
        }

        fun bind(m: Message) {
            runId = m.runId
            btnExpand.setOnClickListener {
                expanded = !expanded
                detailBox.visibility = if (expanded) View.VISIBLE else View.GONE
                btnExpand.animate().rotation(if (expanded) 180f else 0f).setDuration(150).start()
            }
            render()
        }

        fun attach() {
            EventBus.register(this)
            if (runActive) startTicker()
        }
        fun detach() {
            EventBus.unregister(this)
            stopAnimations()
        }

        private fun startTicker() {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        private fun stopAnimations() {
            handler.removeCallbacks(ticker)
            pulseAnims.forEach { it.cancel() }
            pulseAnims.clear()
            activeLabels.clear()
        }

        override fun onEvent(event: ActivityEvent) {
            if (event.runId != runId) return
            itemView.post { render() }
        }

        private fun render() {
            val id = runId ?: return
            stopAnimations()
            val events = EngineStore.events(id).filter { it.visibility == EventVisibility.USER }
            if (events.isEmpty()) {
                headlineBase = "Working"; runActive = true; title.text = "Working…"
                startTicker(); return
            }

            // headline = simple human status line, Claude/ChatGPT style
            // (Spec §12: "show the work, not the machinery")
            val last = events.last()
            /* EventType shortcuts below use full path */
            runActive = last.type != com.codeassist.ai.engine.EventType.RUN_COMPLETED && last.type != com.codeassist.ai.engine.EventType.RUN_FAILED &&
                last.type != com.codeassist.ai.engine.EventType.RUN_PAUSED && last.type != com.codeassist.ai.engine.EventType.RUN_DISCARDED && last.type != com.codeassist.ai.engine.EventType.WAITING_FOR_USER
            headlineBase = when (last.type) {
                com.codeassist.ai.engine.EventType.RUN_COMPLETED -> "Done ✓"
                com.codeassist.ai.engine.EventType.RUN_FAILED -> "Ran into a problem"
                com.codeassist.ai.engine.EventType.RUN_PAUSED -> "Work paused"
                com.codeassist.ai.engine.EventType.RUN_DISCARDED -> "Run discarded"
                com.codeassist.ai.engine.EventType.WAITING_FOR_USER -> "Waiting for your input"
                com.codeassist.ai.engine.EventType.SEARCH_STARTED, com.codeassist.ai.engine.EventType.SEARCH_SOURCE_ADDED, com.codeassist.ai.engine.EventType.SOURCE_VERIFIED -> "Searching the web"
                com.codeassist.ai.engine.EventType.CONTEXT_LOADED -> "Reading project context"
                com.codeassist.ai.engine.EventType.FILE_CHANGED -> "Editing files"
                com.codeassist.ai.engine.EventType.TEST_PASSED, com.codeassist.ai.engine.EventType.TEST_FAILED -> "Checking the work"
                com.codeassist.ai.engine.EventType.REPAIR_RETRY, com.codeassist.ai.engine.EventType.RETRY_STARTED -> "Fixing an issue"
                com.codeassist.ai.engine.EventType.ARTIFACT_READY -> "Preparing your file"
                com.codeassist.ai.engine.EventType.FALLBACK -> "Switching approach"
                else -> last.title.removeSuffix("…")
            }
            title.text = if (runActive) headlineBase + "…" else headlineBase

            // step rows: step-level + file-level events (compact, human language)
            val steps = events.filter {
                it.type == com.codeassist.ai.engine.EventType.STEP_STARTED || it.type == com.codeassist.ai.engine.EventType.STEP_UPDATED ||
                    it.type == com.codeassist.ai.engine.EventType.SEARCH_STARTED || it.type == com.codeassist.ai.engine.EventType.SOURCE_VERIFIED ||
                    it.type == com.codeassist.ai.engine.EventType.CONTEXT_LOADED || it.type == com.codeassist.ai.engine.EventType.FILE_CHANGED ||
                    it.type == com.codeassist.ai.engine.EventType.TEST_FAILED || it.type == com.codeassist.ai.engine.EventType.TEST_PASSED ||
                    it.type == com.codeassist.ai.engine.EventType.REPAIR_RETRY || it.type == com.codeassist.ai.engine.EventType.FALLBACK ||
                    it.type == com.codeassist.ai.engine.EventType.ARTIFACT_READY
            }
            // collapse to latest status per row key
            val latestPerStep = LinkedHashMap<String, ActivityEvent>()
            for (e in steps) latestPerStep[rowKey(e)] = e

            stepsBox.removeAllViews()
            val ctx = stepsBox.context
            val density = ctx.resources.displayMetrics.density
            for ((_, e) in latestPerStep) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                }
                val icon = TextView(ctx).apply {
                    text = when (e.status) {
                        EventStatus.DONE -> "✓"
                        EventStatus.ACTIVE -> "●"
                        EventStatus.WARN -> "⚠"
                        EventStatus.ERROR -> "✗"
                        EventStatus.INFO -> "•"
                    }
                    textSize = 12f
                    setTextColor(when (e.status) {
                        EventStatus.DONE -> 0xFF6EE7A0.toInt()
                        EventStatus.ACTIVE -> 0xFF6EC1FF.toInt()
                        EventStatus.WARN -> 0xFFFFD166.toInt()
                        EventStatus.ERROR -> 0xFFFF7B72.toInt()
                        EventStatus.INFO -> 0xFF93A1B3.toInt()
                    })
                    setPadding(0, 2, 16, 2)
                }
                val base = rowLabel(e)
                val label = TextView(ctx).apply {
                    text = base
                    setTextColor(if (e.status == EventStatus.ACTIVE) 0xFFE6EBF2.toInt() else 0xFF93A1B3.toInt())
                    textSize = 12.5f
                }
                // active row = the work in progress: pulsing icon + animated dots
                if (e.status == EventStatus.ACTIVE) {
                    ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0.25f).apply {
                        duration = 650
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        start()
                        pulseAnims.add(this)
                    }
                    activeLabels.add(label to base)
                }
                row.addView(icon); row.addView(label)
                stepsBox.addView(row)
            }
            if (runActive) startTicker()

            // expandable detail: tool rows, files changed, sources, errors
            if (expanded) {
                val tools = events.filter {
                    it.type == com.codeassist.ai.engine.EventType.TOOL_COMPLETED
                }
                val files = events.flatMap { it.fileRefs }.distinct()
                val sources = events.flatMap { it.sourceRefs }.distinctBy { it.url }
                textDetail.text = buildString {
                    if (tools.isNotEmpty()) {
                        append("Tools\n")
                        tools.takeLast(10).forEach {
                            append("  ${it.status.name.lowercase()}  ${it.toolOrCapability} — ${it.title}\n")
                        }
                    }
                    if (files.isNotEmpty()) {
                        append("\nFiles\n")
                        files.take(12).forEach { append("  • $it\n") }
                    }
                    if (sources.isNotEmpty()) {
                        append("\nSources\n")
                        sources.take(8).forEach {
                            append("  • ${it.title} (${it.publisher})${if (it.verified) " ✓" else ""}\n")
                        }
                    }
                    events.lastOrNull { it.errorCode != null }?.let {
                        append("\nLast error: ${it.errorCode} — ${it.detail}")
                    }
                }.trim()
            }
        }

        /** Rows collapse per action; file edits collapse per file name. */
        private fun rowKey(e: ActivityEvent): String =
            if (e.type == com.codeassist.ai.engine.EventType.FILE_CHANGED) "file:" + e.title
            else e.title

        /** Human wording for a row — "Edited app.kt · 1.2 KB" style. */
        private fun rowLabel(e: ActivityEvent): String {
            /* EventType shortcuts below use full path */
            return when (e.type) {
                com.codeassist.ai.engine.EventType.FILE_CHANGED -> {
                    val verb = if (e.detail.contains("delete", true)) "Deleted"
                        else if (e.detail.contains("written", true) || e.detail.contains("×", true)
                            || e.detail.isNotBlank()) "Edited" else "Touched"
                    verb + " " + e.title + if (e.detail.isNotBlank()) "  ·  ${e.detail}" else ""
                }
                com.codeassist.ai.engine.EventType.ARTIFACT_READY -> "File ready: " + e.title +
                    if (e.detail.isNotBlank()) "  ·  ${e.detail}" else ""
                com.codeassist.ai.engine.EventType.STEP_UPDATED -> e.title + if (e.detail.isNotBlank()) "  ·  ${e.detail}" else ""
                else -> e.title
            }
        }
    }

    // ------------------------------------------------------------------
    // Inline interruption card — Spec §13 approval card, §14 four types
    // ------------------------------------------------------------------
    class ApprovalVH(v: View) : RecyclerView.ViewHolder(v) {
        private val titleT: TextView = v.findViewById(R.id.textApprovalTitle)
        private val bodyT: TextView = v.findViewById(R.id.textApprovalBody)
        private val b1: TextView = v.findViewById(R.id.btnOption1)
        private val b2: TextView = v.findViewById(R.id.btnOption2)
        private val b3: TextView = v.findViewById(R.id.btnOption3)
        private val decisionT: TextView = v.findViewById(R.id.textDecision)

        fun bind(m: Message, onDecision: (String, String) -> Unit) {
            val runId = m.runId ?: return
            val run = EngineStore.run(runId) ?: return
            val it = run.pendingInterruption
            if (it == null || it.state != InterruptionState.PENDING) {
                // resolved or gone — show the decision taken
                b1.visibility = View.GONE; b2.visibility = View.GONE; b3.visibility = View.GONE
                titleT.text = "Input"
                val resolved = run.finalAnswer.isBlank()
                bodyT.text = if (resolved) "Continuing…" else "Handled."
                decisionT.visibility = View.GONE
                return
            }
            titleT.text = it.title
            bodyT.text = it.explanation

            val opts = it.options
            fun wire(btn: TextView, label: String?) {
                if (label == null) { btn.visibility = View.GONE; return }
                btn.visibility = View.VISIBLE
                btn.text = label
                btn.setOnClickListener { _ -> onDecision(runId, label) }
            }
            wire(b1, opts.getOrNull(0))
            wire(b2, opts.getOrNull(1))
            wire(b3, opts.getOrNull(2))
        }
    }

    /** "Thinking..." row with a softly pulsing sparkle. */
    class ThinkingVH(v: View) : RecyclerView.ViewHolder(v) {
        private val dot: View = v.findViewById(R.id.thinkingDot)
        private var anim: ObjectAnimator? = null

        fun attach() {
            if (anim == null) {
                anim = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.3f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
            }
            anim?.start()
        }

        fun detach() {
            anim?.cancel()
            dot.alpha = 1f
        }
    }
}
