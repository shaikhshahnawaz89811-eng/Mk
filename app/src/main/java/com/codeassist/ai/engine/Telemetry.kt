package com.codeassist.ai.engine

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observability & diagnostics — Spec §25.
 *
 * Recommended telemetry: approval decisions, tool call name+duration+status,
 * skill activations, run IDs, model lifecycle events, retries, artifacts,
 * search sources, resource pressure, Coder Helper crashes/fallbacks.
 *
 * Privacy rule: references and structured metadata only — never raw secrets,
 * full private file contents, or unnecessary user content.
 */
object Telemetry {

    data class Entry(
        val at: Long = System.currentTimeMillis(),
        val kind: String,
        val label: String,
        val detail: String = "",
        val status: String = "info",
        val durationMs: Long = 0
    )

    private val entries = CopyOnWriteArrayList<Entry>()
    private const val MAX = 500

    fun record(e: ActivityEvent) {
        val kind = when (e.type) {
            EventType.TOOL_STARTED, EventType.TOOL_COMPLETED, EventType.TOOL_RUNNING -> "tool"
            EventType.SKILL_ACTIVE, EventType.SKILL_RELEASED -> "skill"
            EventType.MODEL_LIFECYCLE -> "model"
            EventType.APPROVAL_REQUIRED, EventType.CLARIFICATION_REQUIRED,
            EventType.CREDENTIAL_REQUIRED, EventType.EVIDENCE_REQUIRED -> "approval"
            EventType.RETRY_STARTED, EventType.REPAIR_RETRY -> "retry"
            EventType.SEARCH_STARTED, EventType.SEARCH_SOURCE_ADDED, EventType.SOURCE_VERIFIED -> "search"
            EventType.ARTIFACT_READY -> "artifact"
            EventType.CODER_STATUS, EventType.FALLBACK -> "coder"
            EventType.RUN_STARTED, EventType.RUN_COMPLETED, EventType.RUN_FAILED,
            EventType.RUN_PAUSED, EventType.RUN_DISCARDED, EventType.RUN_RESUMED -> "run"
            else -> "activity"
        }
        add(Entry(kind = kind, label = e.title, detail = sanitize(e.detail),
            status = e.status.name.lowercase()))
    }

    fun log(kind: String, label: String, detail: String = "", status: String = "info", durationMs: Long = 0) {
        add(Entry(kind = kind, label = label, detail = sanitize(detail), status = status, durationMs = durationMs))
    }

    private fun add(e: Entry) {
        entries.add(e)
        while (entries.size > MAX) entries.removeAt(0)
    }

    /** Strip anything that looks like a secret value from telemetry. */
    private fun sanitize(s: String): String {
        var out = s
        SECRET_PATTERNS.forEach { p -> out = out.replace(p, "[redacted]") }
        if (out.length > 300) out = out.take(300) + "…"
        return out
    }

    private val SECRET_PATTERNS = listOf(
        Regex("(sk|pk|api|key|token|secret|bearer)[-_]?[A-Za-z0-9]{16,}", RegexOption.IGNORE_CASE),
        Regex("AIza[0-9A-Za-z\\-_]{20,}"),
        Regex("ya29\\.[0-9A-Za-z\\-_]+")
    )

    fun entries(): List<Entry> = entries.toList()

    fun summary(): Map<String, Int> {
        val m = linkedMapOf<String, Int>()
        for (e in entries) m[e.kind] = (m[e.kind] ?: 0) + 1
        return m
    }

    fun clear() = entries.clear()
}
