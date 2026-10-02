package com.codeassist.ai.engine

import com.codeassist.ai.data.MsgKind
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import java.io.File

/**
 * Single entry point for real on-device generation.
 *
 * Gemma is the planner/analyzer/reviewer. Coder Helper is a code-only worker.
 * No canned answer is returned when a real runtime is unavailable.
 */
object Llm {

    private val lock = Any()

    @Volatile var lastError: String = ""
        private set

    data class FileBlock(val path: String, val content: String)

    fun gemma(): GemmaRuntime? =
        RuntimeFactory.get(ModelLifecycle.GEMMA_ID)?.takeIf { it.isRealModel && it.format != ModelFormat.GGUF }

    fun coder(): GemmaRuntime? =
        RuntimeFactory.get(ModelLifecycle.CODER_ID)?.takeIf { it.isRealModel }

    fun available(): Boolean = gemma() != null

    /** Ask any running model to stop generating (best effort, never throws). */
    fun cancelGeneration() {
        runCatching { gemma()?.cancel() }
        runCatching { coder()?.cancel() }
    }

    fun unavailableReason(): String {
        val g = ModelLifecycle.gemma()
        return when (g.state) {
            ModelState.NOT_IMPORTED ->
                "Gemma model import nahi hua hai. Models screen me .litertlm import karo."
            ModelState.IMPORTED_UNLOADED ->
                "Gemma import hai par load nahi hua. Models screen me Load dabao."
            ModelState.LOADING, ModelState.IMPORTING ->
                "Gemma abhi load/import ho raha hai. Thodi der baad dobara try karo."
            ModelState.ERROR ->
                "Gemma load error: ${g.lastError}"
            else -> {
                val rt = RuntimeFactory.get(ModelLifecycle.GEMMA_ID)
                when {
                    g.lastError.isNotBlank() -> "Gemma error: ${g.lastError}"
                    !rt?.lastError.isNullOrBlank() -> "Gemma runtime error: ${rt?.lastError}"
                    else -> "Gemma ka real inference abhi active nahi hai."
                }
            }
        }
    }

    fun coderUnavailableReason(): String {
        val c = ModelLifecycle.coder()
        return when (c.state) {
            ModelState.NOT_IMPORTED ->
                "Coder Helper import nahi hua hai. Models screen se supported .gguf coding helper import karo."
            ModelState.IMPORTED_UNLOADED ->
                "Coder Helper import hai par abhi load nahi hua."
            ModelState.LOADING, ModelState.IMPORTING ->
                "Coder Helper abhi load/import ho raha hai. Thodi der baad dobara try karo."
            ModelState.ERROR ->
                "Coder Helper load error: ${c.lastError}"
            else -> c.lastError.ifBlank { "Coder Helper ka real inference abhi active nahi hai." }
        }
    }

    fun notReadyMessage(): String =
        "Abhi mere paas asli model se jawab dene ka rasta nahi hai, isliye main andaze se kuch nahi likhunga.\n\n" +
            "**Wajah:** ${unavailableReason()}"

    private fun plainPrompt(system: String, user: String): String = buildString {
        if (system.isNotBlank()) append("SYSTEM INSTRUCTION:\n").append(system.trim()).append("\n\n")
        append("USER REQUEST:\n").append(user.trim())
    }

    /** Blocking generation; callers already run this on the engine thread. */
    fun generate(system: String, user: String, preferCoder: Boolean = false): String? {
        // Code-worker mode is strict: no Gemma fallback. Gemma plans/reviews;
        // the Coder Helper is the only model allowed to emit implementation code.
        val primary = if (preferCoder) coder() else gemma()
        if (primary == null) {
            lastError = ""
            return null
        }
        val prompt = plainPrompt(system, user)
        val out = synchronized(lock) { primary.generate(prompt) }
        // Coder mode is strict: a failed/missing Coder response must never
        // silently fall back to Gemma, because Gemma is the planning/review
        // layer and Coder is the code-only implementation worker.
        if (out.isNullOrBlank()) {
            lastError = primary.lastError.ifBlank { "model ne khali jawab diya" }
            return null
        }
        lastError = ""
        return clean(out)
    }

    fun generateWithImage(system: String, user: String, imagePath: String): String? {
        val g = gemma() ?: return null.also { lastError = "" }
        val prompt = plainPrompt(system, user)
        val out = synchronized(lock) { g.generateWithImage(imagePath, prompt) }
        if (out.isNullOrBlank()) {
            lastError = g.lastError.ifBlank { "vision inference failed" }
            return null
        }
        lastError = ""
        return clean(out)
    }

    private fun clean(out: String): String = out
        .replace("<end_of_turn>", "")
        .replace("<start_of_turn>", "")
        .trim()
        .takeIf { it.isNotBlank() } ?: ""

    fun history(conversationId: String, currentRequest: String, maxTurns: Int = 6,
                maxChars: Int = 1800): String {
        val msgs = runCatching { Store.messages(conversationId) }.getOrNull() ?: return ""
        val turns = msgs.filter { it.kind == MsgKind.TEXT && it.text.isNotBlank() }
        val trimmed = if (turns.isNotEmpty() && turns.last().role == Role.USER &&
            turns.last().text.trim() == currentRequest.trim()) turns.dropLast(1) else turns
        val sb = StringBuilder()
        for (m in trimmed.takeLast(maxTurns)) {
            val who = if (m.role == Role.USER) "User" else "Assistant"
            sb.append(who).append(": ").append(m.text.trim().take(500)).append("\n")
        }
        val s = sb.toString()
        return if (s.length > maxChars) s.takeLast(maxChars) else s
    }

    /** Parse model output in the stable FILE-block form used by the patch guard. */
    fun parseFileBlocks(text: String): List<FileBlock> {
        val out = mutableListOf<FileBlock>()
        val header = Regex("(?m)^\\s*(?:#{1,4}\\s*)?FILE:\\s*`?([^\\n`]+?)`?\\s*$")
        val matches = header.findAll(text).toList()
        for ((i, m) in matches.withIndex()) {
            val path = m.groupValues[1].trim().removePrefix("./")
            if (path.isBlank() || path.startsWith("/") || ".." in path.split("/", "\\\\")) continue
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val chunk = text.substring(m.range.last + 1, end)
            val body = firstCodeBlock(chunk) ?: continue
            out += FileBlock(path, body)
        }
        return out
    }

    fun firstCodeBlock(text: String): String? {
        val open = Regex("```[a-zA-Z0-9_+.-]*[ \\t]*\\n").find(text) ?: return null
        val rest = text.substring(open.range.last + 1)
        val close = rest.indexOf("```")
        val body = if (close >= 0) rest.substring(0, close) else rest
        return body.trimEnd('\n', ' ', '\t', '\r').takeIf { it.isNotBlank() }
    }

    fun languageOf(path: String): String = when (File(path).extension.lowercase()) {
        "kt" -> "kotlin"; "java" -> "java"; "py" -> "python"; "js" -> "javascript"
        "ts" -> "typescript"; "html" -> "html"; "css" -> "css"; "xml" -> "xml"
        "json" -> "json"; "md" -> "markdown"; else -> ""
    }
}
