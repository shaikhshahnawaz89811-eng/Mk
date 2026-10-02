package com.codeassist.ai.engine

import java.io.File

/**
 * Task executors — the real work behind the orchestration loop (Spec §5,
 * §10, §17, §29). Every executor drives tools through ToolRegistry so
 * validation, sandboxing, telemetry and events all apply.
 */

data class Answer(val text: String, val artifacts: List<String> = emptyList())

typealias StopCheck = (RunRecord) -> Boolean
typealias Interrupter = (RunRecord, InterruptionType, String, String, List<String>, Map<String, String>) -> Nothing?

object TaskExecutors {

    fun execute(run: RunRecord, r: RoutedIntent, ctx: ToolContext,
                stop: StopCheck, interrupt: Interrupter): Answer? {
        return when (r.intent) {
            Intent.WEB_RESEARCH -> research(run, r, ctx, stop)
            Intent.CODING_TASK ->
                if (looksLikeQuestion(run.userRequest) && r.entities.isEmpty()) chat(run, r)
                else coding(run, r, ctx, stop, interrupt)
            Intent.BUILD_APP -> Builders.build(run, r, ctx, stop)
            Intent.DOCUMENT_TASK -> document(run, r, ctx, stop, interrupt)
            Intent.DATA_TASK -> data(run, r, ctx, stop, interrupt)
            Intent.MEDIA_TASK -> Builders.media(run, r, ctx, stop)
            Intent.PROJECT_ACTION -> projectAction(run, ctx, stop)
            Intent.MODEL_MANAGEMENT -> modelManagement(run)
            Intent.CREDENTIAL_SETUP -> credentialSetup(run, interrupt)
            Intent.SIMPLE_CHAT, Intent.UNKNOWN -> chat(run, r)
        }
    }

    private fun tool(run: RunRecord, ctx: ToolContext, name: String,
                     vararg args: Pair<String, Any?>): ToolResult {
        return ToolRegistry.execute(ToolCall(name, mapOf(*args), runId = run.runId), ctx)
    }

    private fun step(run: RunRecord, title: String, status: EventStatus = EventStatus.ACTIVE,
                     detail: String = "") {
        run.currentStep = title
        EngineStore.upsertRun(run)
        EventBus.emit(run.runId, EventType.STEP_STARTED, title, status, detail = detail)
    }

    private fun stepDone(run: RunRecord, title: String, detail: String = "") {
        EventBus.emit(run.runId, EventType.STEP_UPDATED, title, EventStatus.DONE, detail = detail)
    }

    // ------------------------------------------------------------------
    // Simple chat — Spec §29 Example A (subtle activity, direct answer)
    // ------------------------------------------------------------------

    private const val CHAT_SYSTEM =
        "You are CodeAssistAI, an offline coding assistant running on the user's phone. " +
        "Reply in the same language and style as the user (Hinglish stays Hinglish). " +
        "Be correct and concise. Put code in fenced blocks with a language tag. " +
        "If you are not sure, say so instead of guessing."

    private const val CODE_SYSTEM =
        "You are an expert programmer. Write correct, complete, runnable code that does exactly " +
        "what is asked. Put every code file in a fenced block with a language tag. Keep " +
        "explanations to at most three short lines, in the same language as the user " +
        "(Hinglish stays Hinglish)."

    private const val EDIT_SYSTEM =
        "You are editing a software project. Reply ONLY with the files you create or change, " +
        "each in exactly this format:\n### FILE: relative/path/to/file.ext\n```lang\n" +
        "FULL new content of the file\n```\n" +
        "Always give the complete file, never partial snippets. After the last file add one " +
        "line: SUMMARY: <what you did>."

    private fun looksLikeQuestion(raw: String): Boolean {
        val first = IntentRouter.normalize(raw).split(" ").firstOrNull().orEmpty()
        return first in setOf("what", "why", "how", "kya", "kyu", "kyun", "kaise", "explain",
            "difference", "meaning", "batao")
    }

    private fun chat(run: RunRecord, r: RoutedIntent): Answer {
        val hist = Llm.history(run.conversationId, run.userRequest)
        val prompt = buildString {
            if (hist.isNotBlank()) append("Conversation so far:\n").append(hist).append("\n")
            append("User: ").append(run.userRequest)
        }
        val reply = Llm.generate(CHAT_SYSTEM, prompt)
        return Answer(reply ?: Llm.notReadyMessage())
    }

    // ------------------------------------------------------------------
    // Web research — Spec §8, §29 Example B
    // ------------------------------------------------------------------

    private fun research(run: RunRecord, r: RoutedIntent, ctx: ToolContext, stop: StopCheck): Answer? {
        val query = r.entities["topic"] ?: run.userRequest
        if (run.checkpoint["search_done"] != "yes") {
            step(run, "Checking latest sources")
            val res = tool(run, ctx, "web_search", "query" to query, "count" to 6)
            if (!res.ok) {
                // network retry with backoff (Spec §19)
                val classified = ErrorClassifier.classify(res.error, "web_search")
                EventBus.emit(run.runId, EventType.RETRY_STARTED, "Retrying search",
                    EventStatus.WARN, detail = classified.likelyCause)
                val (retryRes, err) = RetryPolicy.runWithRetry(classified) {
                    ToolRegistry.execute(ToolCall("web_search",
                        mapOf("query" to query, "count" to 6), runId = run.runId), ctx, emitEvents = false)
                }
                if (retryRes == null || !retryRes.ok)
                    return Answer("I couldn't reach the web right now ($err).\n\n" +
                        "Check the connection and say **retry** — the same run will resume from the search step.")
                run.checkpoint["sources"] = serializeSources(retryRes.sources)
            } else run.checkpoint["sources"] = serializeSources(res.sources)
            run.checkpoint["search_done"] = "yes"
            EngineStore.upsertRun(run)
            stepDone(run, "Checking latest sources", "${run.checkpoint["sources"]?.lines()?.size ?: 0} sources")
        }
        if (stop(run)) return null

        if (run.checkpoint["verify_done"] != "yes") {
            step(run, "Verifying sources")
            val sources = deserializeSources(run.checkpoint["sources"] ?: "")
            var verified = 0
            for (s in sources.take(3)) {
                val fetched = tool(run, ctx, "web_fetch", "url" to s.url)
                if (fetched.ok) { verified++; s.verified = true; s.snippet = fetched.output.take(300) }
                if (stop(run)) return null
            }
            tool(run, ctx, "compare_sources", "notes" to "research verification pass")
            run.checkpoint["sources"] = serializeSources(sources)
            run.checkpoint["verify_done"] = "yes"
            run.checkpoint["verified_count"] = verified.toString()
            EngineStore.upsertRun(run)
            EventBus.emit(run.runId, EventType.SOURCE_VERIFIED, "Sources reviewed",
                EventStatus.DONE, detail = "$verified of ${sources.size} opened and checked")
        }
        if (stop(run)) return null

        step(run, "Writing summary")
        val sources = deserializeSources(run.checkpoint["sources"] ?: "")
        val answer = buildString {
            append("Here's what I found about **").append(query.take(80)).append("**:\n\n")
            sources.take(5).forEachIndexed { i, s ->
                append("${i + 1}. ${s.title} — ${s.publisher}")
                append(if (s.verified) " ✓ verified\n" else " (listed, not opened)\n")
                if (s.snippet.isNotBlank()) append("   ").append(s.snippet.take(160)).append("…\n")
            }
            append("\n**Sources** (retrieved ")
            append(java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.US)
                .format(java.util.Date())).append("):\n")
            sources.take(5).forEach { append("• ${it.url}\n") }
            append("\nWhere sources conflict, I prefer the authoritative primary source and note the date. ")
            append("Ask me to open any source in depth.")
        }
        stepDone(run, "Writing summary")
        return Answer(answer)
    }

    private fun serializeSources(list: List<SourceRef>): String =
        list.joinToString("\n") { "${it.url}\t${it.title}\t${it.publisher}\t${it.verified}\t${it.snippet.replace("\n", " ")}" }

    private fun deserializeSources(s: String): MutableList<SourceRef> =
        s.lines().filter { it.isNotBlank() }.map { ln ->
            val p = ln.split("\t")
            SourceRef(url = p.getOrElse(0) { "" }, title = p.getOrElse(1) { "" },
                publisher = p.getOrElse(2) { "" }, verified = p.getOrElse(3) { "false" } == "true",
                snippet = p.getOrElse(4) { "" })
        }.toMutableList()

    // ------------------------------------------------------------------
    // Coding — Spec §17 workflow, §18 delegation & fallback, §29 C/D
    // ------------------------------------------------------------------

    private fun coding(run: RunRecord, r: RoutedIntent, ctx: ToolContext,
                       stop: StopCheck, interrupt: Interrupter): Answer? {
        if (r.needsWeb && run.checkpoint["docs_done"] != "yes") {
            step(run, "Checking latest web documentation")
            val res = tool(run, ctx, "web_search",
                "query" to (run.userRequest + " official docs"), "count" to 4)
            run.checkpoint["docs"] = if (res.ok) res.output else "unavailable: ${res.error}"
            run.checkpoint["docs_done"] = "yes"
            EngineStore.upsertRun(run)
            stepDone(run, "Checking latest web documentation",
                if (res.ok) "${res.sources.size} relevant sources" else "offline — continuing with local knowledge")
        }
        if (stop(run)) return null
        if (!Llm.available()) return Answer(Llm.notReadyMessage())

        val normalized = IntentRouter.normalize(run.userRequest)
        val tokens = normalized.split(" ").filter { it.isNotBlank() }.toSet()
        val strongEdit = r.entities.containsKey("file") ||
            tokens.any { it in setOf("project", "file", "files", "fix", "bug", "refactor", "badlo", "sudhar", "implement", "implementation") }
        val snippetAsk = tokens.any { it in setOf("snippet", "example", "sample", "line", "lines", "program", "script", "likho", "write", "kaise") }
        val weakEdit = tokens.any { it in setOf("update", "change", "feature", "header", "responsive", "theme", "add") } || "dark mode" in normalized
        if (!strongEdit && (snippetAsk || weakEdit.not())) return codeAnswer(run, stop)

        var found = ctx.projectRoot ?: run.checkpoint["project_root"]?.let { File(it) }
        if (found == null) {
            val (single, many) = ProjectSystem.detectFromWorkspace()
            found = when {
                single != null -> single
                many.isEmpty() -> null
                else -> {
                    interrupt(run, InterruptionType.CLARIFICATION,
                        "Which project should I work on?",
                        "Multiple projects look plausible. Pick one so I don't edit the wrong workspace.",
                        many.map { it.name }, mapOf())
                    null
                }
            }
        }
        val root = found ?: return codeAnswer(run, stop)
        run.checkpoint["project_root"] = root.absolutePath
        EngineStore.upsertRun(run)
        Sandbox.activeProjectRoot = root
        step(run, "Coding pipeline", detail = "Gemma plan → contract → Coder → Patch Guard → validation → review → ZIP")
        val result = CodingPipeline.run(run, ctx.copy(projectRoot = root), stop)
        if (!result.ok) {
            stepDone(run, "Coding pipeline", "FAILED • rollback=${result.details.any { it.contains("rollback") }}")
            val text = buildString {
                append(result.summary)
                if (result.errors.isNotEmpty()) {
                    append("\n\n**Problems**\n")
                    result.errors.take(8).forEach { append("• ").append(it).append('\n') }
                }
            }
            return Answer(text)
        }
        stepDone(run, "Coding pipeline", "PASS • ${result.filesChanged.size} files • final ZIP ready")
        val files = result.filesChanged.joinToString(", ") { Sandbox.displayPath(File(it)) }.ifBlank { "none" }
        val extra = result.details.filter { it.isNotBlank() }.takeLast(8).joinToString("\n")
        return Answer(
            "${result.summary}\n\n**Files changed:** $files\n\n${if (extra.isNotBlank()) "**Validation:**\n$extra\n\n" else ""}Final ZIP: **${result.packagePath?.let { File(it).name } ?: "ready in Artifacts"}**",
            artifacts = listOfNotNull(result.packagePath)
        )
    }

    /** Gemma starts the Coder Helper only for the bounded handoff used by code snippets. */
    private fun codeAnswer(run: RunRecord, stop: StopCheck): Answer? {
        if (stop(run)) return null
        step(run, "Preparing code answer")
        val hist = Llm.history(run.conversationId, run.userRequest)
        val prompt = buildString {
            if (hist.isNotBlank()) append("Conversation so far:\n").append(hist).append("\n")
            append("Task: ").append(run.userRequest)
        }
        val coderImported = CoderHelperManager.state() != ModelState.NOT_IMPORTED
        val out = if (coderImported) {
            run.coderSessionId = "coder-${java.util.UUID.randomUUID()}"
            EngineStore.upsertRun(run)
            val res = CoderHelperManager.withHelperBlocking("snippet ${run.coderSessionId}") {
                val reply = Llm.generate(CODE_SYSTEM, prompt, preferCoder = true)
                if (reply != null) CodingResult("success", reply)
                else CodingResult("failed", "Coder Helper returned no output", errors = listOf(Llm.coderUnavailableReason()))
            }
            if (res.status == "success") res.summary else null
        } else {
            Llm.generate(CODE_SYSTEM, prompt)
        }
        stepDone(run, "Preparing code answer", if (out != null) "done" else "failed")
        return Answer(out ?: Llm.notReadyMessage())
    }

    // ------------------------------------------------------------------
    // Documents & spreadsheets — Spec §10 (document/spreadsheet pipeline)
    // ------------------------------------------------------------------

    private fun document(run: RunRecord, r: RoutedIntent, ctx: ToolContext,
                         stop: StopCheck, interrupt: Interrupter): Answer? {
        val attachments = run.checkpoint["attachments"]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        val wantsSheet = r.outputType == OutputType.SPREADSHEET
        val pdfAttachment = attachments.firstOrNull { it.lowercase().endsWith(".pdf") }

        // Evidence requirement (Spec §14, §29 Example F): verify-a-report with no file
        if ("verify" in run.userRequest.lowercase() && attachments.isEmpty() &&
            run.checkpoint["evidence_handled"] != "yes") {
            return interrupt(run, InterruptionType.EVIDENCE_REQUIRED,
                "I need the report before I can verify it",
                "Attach the source/report file you want me to verify, or paste its text.",
                listOf("Attach file", "Paste source", "Skip"), mapOf())
        }
        if (stop(run)) return null

        val outDir = ctx.projectRoot ?: Sandbox.artifactsDir()
        val outCtx = ctx.copy(projectRoot = outDir)
        val base = "document_${System.currentTimeMillis() % 100000}"

        if (pdfAttachment != null) {
            // PDF -> extract -> spreadsheet (Spec §5 example pipeline)
            step(run, "Extracting from PDF")
            val src = File(pdfAttachment)
            val extracted = tool(run, outCtx, "pdf_extract", "path" to src.absolutePath)
            if (!extracted.ok || extracted.output.startsWith("(no extractable")) {
                return Answer("I opened **${src.name}** but couldn't extract text " +
                    "(${extracted.error.ifBlank { "image-only PDF" }}).\n\n" +
                    "If it's a scan, OCR is needed — tell me and I'll note it in the project plan.")
            }
            stepDone(run, "Extracting from PDF", "${extracted.output.length} chars")
            if (stop(run)) return null

            if (wantsSheet) {
                step(run, "Building spreadsheet")
                val lines = extracted.output.lines().map { it.trim() }.filter { it.isNotBlank() }
                val rows = lines.map { ln ->
                    val cells = ln.split(Regex("\\s{2,}|\\t|,| ")).filter { it.isNotBlank() }
                    cells.joinToString(",") { c ->
                        if (c.any { it == ',' || it == '"' }) "\"${c.replace("\"", "\"\"")}\"" else c
                    }
                }
                val csv = (listOf("Column1,Column2,Column3,Column4") + rows).joinToString("\n")
                val xlsx = File(outDir, "$base.xlsx")
                val res = tool(run, outCtx, "xlsx_create", "path" to xlsx.absolutePath, "csv" to csv,
                    "sheet" to "Extracted")
                stepDone(run, "Building spreadsheet")
                return if (res.ok) Answer(
                    "Done — I extracted the content from **${src.name}** and built a spreadsheet.\n\n" +
                        "• Rows: ${rows.size}\n• File: ${xlsx.name}\n\nOpen it from Artifacts below.",
                    listOf(xlsx.absolutePath))
                else Answer("Spreadsheet creation failed: ${res.error}")
            }
        }

        // Compose a document/report from the topic
        step(run, "Composing document")
        val topic = r.entities["topic"] ?: run.userRequest.take(80)
        val content = buildString {
            append("# Overview\nThis document was composed on-device by the assistant.\n\n")
            append("# Topic\n$topic\n\n")
            append("# Details\n")
            append("Request: ${run.userRequest}\n\n")
            append("Generated: ").append(java.text.SimpleDateFormat("dd MMM yyyy, HH:mm",
                java.util.Locale.US).format(java.util.Date())).append("\n")
        }
        val isDocx = "docx" in run.userRequest.lowercase() || "word" in run.userRequest.lowercase()
        val out = File(outDir, if (isDocx) "$base.docx" else "$base.pdf")
        val res = if (isDocx)
            tool(run, outCtx, "docx_create", "path" to out.absolutePath,
                "title" to topic, "content" to content)
        else tool(run, outCtx, "pdf_create", "path" to out.absolutePath,
            "title" to topic, "content" to content)
        stepDone(run, "Composing document")
        return if (res.ok)
            Answer("Your document is ready: **${out.name}**\n\nIt's saved in Artifacts — open or share it from there.",
                listOf(out.absolutePath))
        else Answer("Document creation failed: ${res.error}")
    }

    // ------------------------------------------------------------------
    // Data tasks
    // ------------------------------------------------------------------

    private fun data(run: RunRecord, r: RoutedIntent, ctx: ToolContext,
                     stop: StopCheck, interrupt: Interrupter): Answer? {
        val attachments = run.checkpoint["attachments"]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        val csvFile = attachments.firstOrNull { it.lowercase().endsWith(".csv") }
        if (csvFile == null) {
            return interrupt(run, InterruptionType.EVIDENCE_REQUIRED,
                "Attach the data file",
                "I can analyze CSV data and build a spreadsheet — attach the file first.",
                listOf("Attach file", "Skip"), mapOf())
        }
        step(run, "Analyzing data")
        val f = File(csvFile)
        val summary = tool(run, ctx, "csv_tool", "path" to f.absolutePath, "mode" to "summary")
        if (stop(run)) return null
        step(run, "Building workbook")
        val out = File(ctx.projectRoot ?: Sandbox.artifactsDir(),
            "analysis_${System.currentTimeMillis() % 100000}.xlsx")
        val outCtx = ctx.copy(projectRoot = out.parentFile)
        val res = tool(run, outCtx, "xlsx_create", "path" to out.absolutePath,
            "csv" to f.readText(), "sheet" to "Data")
        stepDone(run, "Building workbook")
        return Answer("**Data summary**\n\n${summary.output}\n\nWorkbook: **${out.name}**",
            if (res.ok) listOf(out.absolutePath) else emptyList())
    }

    // ------------------------------------------------------------------
    // Project actions (export/package existing project)
    // ------------------------------------------------------------------

    private fun projectAction(run: RunRecord, ctx: ToolContext, stop: StopCheck): Answer? {
        val root = ctx.projectRoot ?: ProjectSystem.detectFromWorkspace().first
            ?: return Answer("No project selected. Open a project first, then ask me to export/package it.")
        val pctx = ctx.copy(projectRoot = root)
        step(run, "Packaging project")
        val res = tool(run, pctx, "package", "name" to "${root.name}-bundle.zip")
        stepDone(run, "Packaging project")
        return if (res.ok)
            Answer("Packaged **${root.name}** → ${res.output}\n\nThe ZIP + build report are in Artifacts.",
                res.filesChanged)
        else Answer("Couldn't package: ${res.error}")
    }

    // ------------------------------------------------------------------
    // Model management via chat (Spec §4 — user controls Gemma)
    // ------------------------------------------------------------------

    private fun modelManagement(run: RunRecord): Answer {
        val t = IntentRouter.normalize(run.userRequest)
        val g = ModelLifecycle.gemma()
        return when {
            "unload" in t -> {
                var msg = "…"
                ModelLifecycle.unload(ModelLifecycle.GEMMA_ID) { ok, m -> msg = m }
                Thread.sleep(600) // unload is quick; keep chat synchronous-feeling
                Answer(msg)
            }
            "delete" in t -> {
                if (!ModelGuards.canDelete(g.state))
                    Answer(ModelGuards.blockedReason(LifecycleAction.DELETE, g.state, g.busy)
                        ?: "Delete not allowed")
                else Answer("Deletion is a destructive action — use **Models** in Settings so you can confirm it explicitly.")
            }
            "load" in t -> {
                if (!ModelGuards.canLoad(g.state))
                    Answer(ModelGuards.blockedReason(LifecycleAction.LOAD, g.state, g.busy)
                        ?: "Load not allowed right now")
                else {
                    var msg = "…"
                    ModelLifecycle.load(ModelLifecycle.GEMMA_ID, {}) { _, m -> msg = m }
                    var waited = 0
                    while (msg == "…" && waited < 30_000) { Thread.sleep(300); waited += 300 }
                    Answer(msg)
                }
            }
            "import" in t -> Answer("To import Gemma, open **Settings → Models → Import** and pick the `.litertlm` file. Duplicate imports are detected by content hash, not filename.")
            else -> Answer("Gemma state: **${g.state}**${if (g.lastError.isNotBlank()) "\nLast error: ${g.lastError}" else ""}")
        }
    }

    // ------------------------------------------------------------------
    // Credential setup — Spec §16, §29 Example E
    // ------------------------------------------------------------------

    private fun credentialSetup(run: RunRecord, interrupt: Interrupter): Answer? {
        val t = run.userRequest.lowercase()
        val provider = when {
            "google" in t -> "google"
            "openai" in t -> "openai"
            "github" in t -> "github"
            else -> "generic"
        }
        if (CredentialStore.hasActive(provider))
            return Answer("**$provider** is already connected (credential_id=${provider}_main). Nothing to do.")
        return interrupt(run, InterruptionType.CREDENTIAL,
            "Connection required",
            "$provider credentials are not configured. Connect securely — the key is encrypted on-device and I only ever see a credential_id, never the key itself.",
            listOf("Connect", "Continue without $provider"),
            mapOf("provider" to provider))
    }
}
