package com.codeassist.ai.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.chat.MessagesAdapter
import com.codeassist.ai.data.Attachment
import com.codeassist.ai.data.ChatMeta
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.MsgKind
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.engine.ApprovalMode
import com.codeassist.ai.engine.CredentialStore
import com.codeassist.ai.engine.Engine
import com.codeassist.ai.engine.EngineStore
import com.codeassist.ai.engine.InterruptionType
import com.codeassist.ai.engine.ModelLifecycle
import com.codeassist.ai.engine.Orchestrator
import com.codeassist.ai.engine.RiskEngine
import com.codeassist.ai.engine.RunGuards
import com.codeassist.ai.engine.RunRecord
import com.codeassist.ai.engine.RunState
import com.codeassist.ai.ui.AttachSheet
import com.codeassist.ai.ui.AttachmentHelper
import com.codeassist.ai.ui.AttachmentStore
import com.codeassist.ai.ui.ComposerController

/**
 * Home = the chat itself (ChatGPT / Claude mobile pattern), wired to the
 * orchestration engine (Spec §5, §11, §12, §14, §15):
 *  - one assistant surface — internal modules never appear in chat
 *  - live activity timeline per run, expandable detail
 *  - inline interruption cards (approval / clarification / credential / evidence)
 *  - Send / Stop composer states; paused runs offer Continue / Discard
 *  - editing an earlier user message branches a new run (old branch kept)
 */
class HomeFragment : Fragment(), Orchestrator.RunListener {

    private lateinit var helper: AttachmentHelper
    private lateinit var composer: ComposerController
    private lateinit var recycler: RecyclerView
    private lateinit var greetingBox: View
    private lateinit var btnJump: View
    private lateinit var textTitle: TextView
    private lateinit var textModel: TextView
    private lateinit var adapter: MessagesAdapter
    private val handler = Handler(Looper.getMainLooper())

    private var chat: ChatMeta? = null
    private var messages = mutableListOf<Message>()
    private var atBottom = true
    private var activeRunId: String? = null
    private var evidenceTargetRun: String? = null

    /** evidence picker for EVIDENCE_REQUIRED interruptions */
    private val pickEvidence = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        val runId = evidenceTargetRun ?: return@registerForActivityResult
        evidenceTargetRun = null
        if (uri == null) return@registerForActivityResult
        val name = runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else "evidence"
            } ?: "evidence"
        }.getOrDefault("evidence")
        // copy on a background thread — evidence files can be tens of MB (Spec §26)
        val appCtx = context?.applicationContext ?: return@registerForActivityResult
        Thread {
            val path = AttachmentStore.copyToInbox(appCtx, uri.toString(), name)
            handler.post {
                if (path != null) {
                    Orchestrator.resumeRun(runId, "Attach file", mapOf("evidence_path" to path))
                } else toast("Couldn't read that file")
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helper = AttachmentHelper(this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Store.init(requireContext())
        Engine.init(requireContext())
        RiskEngine.approvalMode = when (Store.permissionMode) {
            "Auto" -> ApprovalMode.AUTO
            "Skip approvals" -> ApprovalMode.SKIP_APPROVALS
            else -> ApprovalMode.ASK_WHEN_NEEDED
        }

        textTitle = view.findViewById(R.id.textTitle)
        textModel = view.findViewById(R.id.textModel)
        greetingBox = view.findViewById(R.id.greetingBox)
        btnJump = view.findViewById(R.id.btnJumpDown)
        recycler = view.findViewById(R.id.recyclerMessages)

        adapter = MessagesAdapter()
        adapter.onMessageAction = ::onMessageAction
        adapter.onInterruptionDecision = ::onInterruptionDecision
        adapter.onOpenArtifact = ::openArtifact
        recycler.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = false
        }
        recycler.adapter = adapter
        (recycler.itemAnimator as? DefaultItemAnimator)?.apply {
            addDuration = 160
            removeDuration = 120
            changeDuration = 0
            moveDuration = 0
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateAtBottom()
        })
        recycler.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom && atBottom) scrollToEnd(smooth = false)
        }
        btnJump.setOnClickListener { scrollToEnd(smooth = true) }

        composer = ComposerController(view.findViewById(R.id.composer), helper) { text, atts ->
            val branch = pendingBranchOf
            pendingBranchOf = null
            send(text, atts, branch)
        }
        composer.setModelLabel(currentModelLabel())
        composer.onStopClick = { activeRunId?.let { Orchestrator.stopRun(it) } }
        composer.chipModel.setOnClickListener { showModelPicker() }
        composer.chipTools.setOnClickListener { showToolsMenu() }
        composer.onTuneClick = { showTuneMenu() }
        composer.onPlusClick = { showAttachSheet() }

        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
        view.findViewById<View>(R.id.btnNewChat).setOnClickListener { startNewChat() }

        Orchestrator.register(this)

        val activeId = Store.activeChatId
        val existing = activeId?.let { id -> Store.chats().firstOrNull { it.id == id } }
        if (existing != null) loadChat(existing.id, animate = false) else showGreeting(animate = false)
    }

    private fun currentModelLabel(): String {
        val g = ModelLifecycle.gemma()
        return when (g.state) {
            com.codeassist.ai.engine.ModelState.LOADED, com.codeassist.ai.engine.ModelState.BUSY ->
                if (com.codeassist.ai.engine.Llm.available()) "Gemma 4 E4B" else "Gemma loaded, inference off"
            else -> "No model loaded"
        }
    }

    // ---------- Chat state ----------

    fun loadChat(id: String, animate: Boolean = true) {
        val c = Store.chats().firstOrNull { it.id == id } ?: return
        chat = c
        Store.activeChatId = c.id
        messages = Store.messages(c.id)
        adapter.submit(messages)
        // a run may still be active/waiting for this chat — reflect it
        activeRunId = EngineStore.runs().firstOrNull {
            it.conversationId == id && !RunGuards.isTerminal(it.state)
        }?.runId
        syncComposerMode()
        enterChatMode(animate)
        recycler.post { scrollToEnd(smooth = false) }
    }

    fun startNewChat() {
        if (chat == null) return
        chat = null
        Store.activeChatId = null
        activeRunId = null
        syncComposerMode()
        messages = mutableListOf()
        adapter.submit(messages)
        showGreeting(animate = true)
    }

    fun closeChat(): Boolean {
        if (chat == null) return false
        startNewChat()
        return true
    }

    // ---------- Send + orchestrated reply ----------

    private fun send(text: String, atts: List<Attachment>, branchOf: String? = null) {
        var c = chat
        if (c == null) {
            val title = if (text.isNotBlank()) text.take(42) else (atts.firstOrNull()?.name ?: "New chat")
            c = Store.newChat(title)
            chat = c
            Store.activeChatId = c.id
            messages = Store.messages(c.id)
            adapter.submit(messages)
            enterChatMode(animate = true)
        }
        val msg = Message(role = Role.USER, text = text, attachments = atts, branchOf = branchOf)
        messages.add(msg)
        c.snippet = text.ifBlank { atts.firstOrNull()?.name ?: "" }
        c.time = System.currentTimeMillis()
        Store.updateChat(c)
        Store.saveMessages(c.id, messages)
        adapter.insert(msg)
        updateTopBar()
        maybeScrollEnd()
        helper.clear()
        startRun(text, atts, c, branchOf)
    }

    private fun startRun(text: String, atts: List<Attachment>, c: ChatMeta, branchOf: String?) {
        // Attachments can be tens of MB — never copy them on the UI thread
        // (Spec §26 responsiveness). Copy in the sandbox inbox on a worker
        // thread, then create the run and reveal the live activity card.
        val appCtx = context?.applicationContext ?: return
        val chatId = c.id
        Thread {
            val paths = atts.mapNotNull {
                runCatching { AttachmentStore.copyToInbox(appCtx, it.uri, it.name) }.getOrNull()
            }
            val run = Orchestrator.createRun(
                message = text,
                attachments = paths,
                projectId = c.projectId ?: Store.lastProjectId,
                conversationId = chatId,
                parentRunId = branchOf
            )
            handler.post {
                if (view == null || chat?.id != chatId) return@post
                activeRunId = run.runId
                syncComposerMode()
                val activityMsg = Message(role = Role.AI, text = "", kind = MsgKind.ACTIVITY, runId = run.runId)
                messages.add(activityMsg)
                Store.saveMessages(chatId, messages)
                adapter.insert(activityMsg)
                maybeScrollEnd()
            }
        }.start()
    }

    /** Run updates arrive here (engine thread) — post to UI. */
    override fun onRunUpdated(run: RunRecord) {
        val c = chat ?: return
        if (run.conversationId != c.id) return
        handler.post {
            if (view == null) return@post
            adapter.refreshRun(run.runId)
            when {
                run.state == RunState.WAITING && run.pendingInterruption != null -> {
                    ensureApprovalCard(run, c)
                    composer.setMode(ComposerController.Mode.SEND)
                }
                run.state == RunState.PAUSED -> {
                    ensureApprovalCard(run, c)
                    composer.setMode(ComposerController.Mode.SEND)
                }
                RunGuards.isTerminal(run.state) -> {
                    composer.setMode(ComposerController.Mode.SEND)
                    if (run.state == RunState.COMPLETED && run.finalAnswer.isNotBlank()) {
                        appendFinal(run, c)
                    } else if (run.state == RunState.FAILED) {
                        appendFinal(run.copy(finalAnswer =
                            "I ran into a problem I couldn't recover from: ${run.error}\n\n" +
                                "The run is saved — say **retry** to attempt it again."), c)
                    }
                    if (activeRunId == run.runId) activeRunId = null
                }
                RunGuards.isActive(run.state) || run.state == RunState.RESUMED ->
                    composer.setMode(ComposerController.Mode.STOP)
            }
            maybeScrollEnd()
        }
    }

    private var cardedInterruptions = mutableSetOf<String>()

    private fun ensureApprovalCard(run: RunRecord, c: ChatMeta) {
        val it = run.pendingInterruption ?: return
        if (!cardedInterruptions.add(it.id + run.state)) return
        val card = Message(role = Role.AI, text = "", kind = MsgKind.APPROVAL, runId = run.runId)
        messages.add(card)
        Store.saveMessages(c.id, messages)
        adapter.insert(card)
    }

    private fun appendFinal(run: RunRecord, c: ChatMeta) {
        // avoid duplicates when re-entering the chat
        if (messages.any { it.runId == run.runId + ":final" }) return
        val artifact = run.artifactRefs.lastOrNull()
        val msg = Message(
            role = Role.AI, text = run.finalAnswer,
            kind = MsgKind.TEXT, runId = run.runId + ":final",
            fileName = artifact, fileType = artifact?.substringAfterLast('.', "")?.uppercase()
        )
        messages.add(msg)
        c.snippet = run.finalAnswer.take(60)
        c.time = System.currentTimeMillis()
        Store.updateChat(c)
        Store.saveMessages(c.id, messages)
        adapter.insert(msg)
    }

    // ---------- Interruption decisions (Spec §13, §14, §16) ----------

    private fun onInterruptionDecision(runId: String, decision: String) {
        val run = EngineStore.run(runId) ?: return
        val it = run.pendingInterruption
        when {
            decision == "Continue" || decision == "Discard" ->
                Orchestrator.resolvePause(runId, decision == "Continue")

            it?.type == InterruptionType.CREDENTIAL && decision == "Connect" -> {
                val provider = it.payload["provider"] ?: "provider"
                promptSecret(provider) { ok ->
                    Orchestrator.resumeRun(runId,
                        if (ok) "connected" else "Continue without $provider",
                        mapOf("credential_id" to "${provider}_main"))
                }
            }

            it?.type == InterruptionType.EVIDENCE_REQUIRED && decision == "Attach file" -> {
                evidenceTargetRun = runId
                pickEvidence.launch(arrayOf("*/*"))
            }

            it?.type == InterruptionType.EVIDENCE_REQUIRED && decision == "Paste source" -> {
                promptPaste { path ->
                    if (path != null) Orchestrator.resumeRun(runId, "Paste source",
                        mapOf("evidence_path" to path))
                    else Orchestrator.resumeRun(runId, "Skip")
                }
            }

            it?.type == InterruptionType.CLARIFICATION && (it.options.size > 3) -> {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(it.title)
                    .setItems(it.options.toTypedArray()) { _, which ->
                        Orchestrator.resumeRun(runId, it.options[which])
                    }.show()
            }

            else -> Orchestrator.resumeRun(runId, decision)
        }
        // refresh the card
        handler.postDelayed({ adapter.refreshRun(runId) }, 300)
    }

    /** Secure credential entry — value goes straight to the encrypted store. */
    private fun promptSecret(provider: String, done: (Boolean) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = "Paste API key / token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Connect $provider")
            .setMessage("Stored encrypted on this device. The assistant only sees a credential_id — never this value.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val v = input.text.toString().trim()
                if (v.length >= 8) {
                    CredentialStore.connect(provider, v)
                    toast("$provider connected (${provider}_main)")
                    done(true)
                } else { toast("That doesn't look like a valid key"); done(false) }
            }
            .setNegativeButton("Cancel") { _, _ -> done(false) }
            .show()
    }

    private fun promptPaste(done: (String?) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = "Paste the source text here"
            minLines = 5
            gravity = android.view.Gravity.TOP
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Paste source")
            .setView(input)
            .setPositiveButton("Use") { _, _ ->
                val t = input.text.toString()
                if (t.isBlank()) return@setPositiveButton done(null)
                val f = java.io.File(AttachmentStore.inbox(requireContext()),
                    "pasted_${System.currentTimeMillis()}.txt")
                f.writeText(t)
                done(f.absolutePath)
            }
            .setNegativeButton("Cancel") { _, _ -> done(null) }
            .show()
    }

    private fun openArtifact(path: String) {
        val f = java.io.File(path)
        if (!f.exists()) { toast("File not found anymore"); return }
        AlertDialog.Builder(requireContext())
            .setTitle(f.name)
            .setMessage("Saved at:\n${f.parent}\n\nSize: ${f.length() / 1024} KB")
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Message actions (Spec §11) ----------

    private fun onMessageAction(m: Message, action: String) {
        when (action) {
            "copy" -> {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("message", m.text))
                toast("Copied")
            }
            "edit" -> {
                // Editing an earlier user message creates a NEW BRANCH (old preserved, Spec §11)
                composer.edit.setText(m.text)
                composer.edit.setSelection(m.text.length)
                pendingBranchOf = m.runId ?: m.id
                toast("Edit and send — a new branch keeps the old one")
            }
            "retry" -> {
                val c = chat ?: return
                val source = if (m.role == Role.USER) m.text
                else messages.lastOrNull { it.role == Role.USER }?.text ?: return
                startRun(source, emptyList(), c, m.runId)
            }
            "delete" -> {
                messages.remove(m)
                adapter.remove(m)
                Store.saveMessages(chat?.id ?: return, messages)
            }
            "report problem" -> {
                com.codeassist.ai.engine.Telemetry.log("feedback", "User reported a problem",
                    m.text.take(80), "warn")
                toast("Logged in diagnostics — thanks")
            }
        }
    }

    private var pendingBranchOf: String? = null

    // ---------- Scroll ----------

    private fun updateAtBottom() {
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        val lastPos = layoutManager.findLastVisibleItemPosition()
        var at = lastPos >= adapter.itemCount - 1
        if (at && lastPos >= 0) {
            val child = layoutManager.findViewByPosition(lastPos)
            if (child != null) {
                val visibleBottom = recycler.height - recycler.paddingBottom
                at = child.bottom <= visibleBottom + dp(64)
            }
        }
        atBottom = at
        val showJump = !at && adapter.itemCount > 0
        if (showJump && btnJump.visibility != View.VISIBLE) {
            btnJump.visibility = View.VISIBLE
            btnJump.alpha = 0f
            btnJump.animate().alpha(1f).setDuration(150).start()
        } else if (!showJump && btnJump.visibility == View.VISIBLE) {
            btnJump.animate().alpha(0f).setDuration(150).withEndAction {
                btnJump.visibility = View.GONE
            }.start()
        }
    }

    private fun maybeScrollEnd() {
        if (atBottom) scrollToEnd(smooth = true) else updateAtBottom()
    }

    private fun scrollToEnd(smooth: Boolean) {
        val count = adapter.itemCount
        if (count == 0) return
        recycler.post {
            if (smooth) recycler.smoothScrollToPosition(count - 1)
            else recycler.scrollToPosition(count - 1)
        }
    }

    private fun syncComposerMode() {
        val run = activeRunId?.let { EngineStore.run(it) }
        composer.setMode(
            if (run != null && (RunGuards.isActive(run.state) || run.state == RunState.RESUMED))
                ComposerController.Mode.STOP else ComposerController.Mode.SEND
        )
    }

    // ---------- Mode switching ----------

    private fun enterChatMode(animate: Boolean) {
        updateTopBar()
        if (!animate) {
            greetingBox.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.alpha = 1f
            return
        }
        recycler.alpha = 0f
        recycler.visibility = View.VISIBLE
        greetingBox.animate().cancel()
        greetingBox.animate().alpha(0f).translationY(-dp(20).toFloat()).setDuration(170)
            .withEndAction {
                greetingBox.visibility = View.GONE
                greetingBox.alpha = 1f
                greetingBox.translationY = 0f
            }.start()
        recycler.animate().cancel()
        recycler.animate().alpha(1f).setStartDelay(70).setDuration(200).start()
    }

    private fun showGreeting(animate: Boolean) {
        updateTopBar()
        if (!animate) {
            recycler.visibility = View.GONE
            greetingBox.visibility = View.VISIBLE
            greetingBox.alpha = 1f
            return
        }
        greetingBox.alpha = 0f
        greetingBox.translationY = dp(14).toFloat()
        greetingBox.visibility = View.VISIBLE
        recycler.animate().cancel()
        recycler.animate().alpha(0f).setDuration(150).withEndAction {
            recycler.visibility = View.GONE
        }.start()
        greetingBox.animate().cancel()
        greetingBox.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(200).start()
    }

    private fun updateTopBar() {
        val c = chat
        // Fixed app title — never replaced by the chat's first message.
        textTitle.text = "CodeAssistAI"
        textModel.visibility = View.VISIBLE
        textModel.text = currentModelLabel()
    }

    // ---------- Menus ----------

    private fun showAttachSheet() {
        AttachSheet(helper).show(parentFragmentManager, "attach")
    }

    private fun showModelPicker() {
        // One assistant surface: picking the model manages Gemma lifecycle
        val g = ModelLifecycle.gemma()
        val options = mutableListOf<String>()
        options += "No model loaded"
        if (g.state == com.codeassist.ai.engine.ModelState.IMPORTED_UNLOADED) options += "Load Gemma 4 E4B IT"
        if (g.state == com.codeassist.ai.engine.ModelState.LOADED) options += "Gemma 4 E4B (loaded)"
        val popup = PopupMenu(requireContext(), composer.chipModel)
        options.forEachIndexed { i, m -> popup.menu.add(0, i, i, m) }
        popup.setOnMenuItemClickListener {
            when (options[it.itemId]) {
                "Load Gemma 4 E4B IT" -> ModelLifecycle.load(ModelLifecycle.GEMMA_ID, {}) { _, m ->
                    toast(m); composer.setModelLabel(currentModelLabel()); updateTopBar()
                }
            }
            true
        }
        popup.show()
    }

    private fun showToolsMenu() {
        val tools = com.codeassist.ai.engine.ToolRegistry.all()
        val names = tools.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Available tools (${tools.size})")
            .setItems(names) { _, i ->
                val t = tools[i]
                AlertDialog.Builder(requireContext())
                    .setTitle(t.name)
                    .setMessage("${t.description}\n\nRisk tier: ${t.baseRisk}\n" +
                        "Args: ${t.argSpec.keys.joinToString()}")
                    .setPositiveButton("OK", null).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTuneMenu() {
        AlertDialog.Builder(requireContext())
            .setTitle("Composer limits")
            .setMessage(
                "• Input grows to 5 lines, then scrolls\n" +
                "• ${ComposerController.MAX_CHARS} characters per message\n" +
                "• Images up to 10 MB each\n" +
                "• ZIP / files up to 25 MB each\n" +
                "• Max 5 attachments (50 MB total)"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        Orchestrator.unregister(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
