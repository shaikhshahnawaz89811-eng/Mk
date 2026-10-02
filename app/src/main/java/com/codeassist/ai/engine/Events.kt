package com.codeassist.ai.engine

import java.util.UUID

/**
 * Activity events — Spec §12 (live activity UI), §22 (activity event model),
 * §23 (frontend event contracts), §25 (observability).
 *
 * USER events render in the conversation timeline in plain human language.
 * DIAGNOSTIC events are only visible on the developer diagnostics surface.
 */

enum class EventType {
    RUN_STARTED,
    CONTEXT_LOADED,
    STEP_STARTED,
    STEP_UPDATED,
    SEARCH_STARTED,
    SEARCH_SOURCE_ADDED,
    SOURCE_VERIFIED,
    SKILL_ACTIVE,
    SKILL_RELEASED,
    TOOL_STARTED,
    TOOL_RUNNING,
    TOOL_COMPLETED,
    FILE_CHANGED,
    TEST_FAILED,
    TEST_PASSED,
    REPAIR_RETRY,
    RETRY_STARTED,
    APPROVAL_REQUIRED,
    CLARIFICATION_REQUIRED,
    CREDENTIAL_REQUIRED,
    EVIDENCE_REQUIRED,
    WAITING_FOR_USER,
    RUN_PAUSED,
    RUN_DISCARDED,
    RUN_RESUMED,
    RUN_COMPLETED,
    RUN_FAILED,
    MODEL_LIFECYCLE,
    CODER_STATUS,
    FALLBACK,
    ARTIFACT_READY,
    DIAGNOSTIC
}

enum class EventVisibility { USER, DIAGNOSTIC }

enum class EventStatus { ACTIVE, DONE, WARN, ERROR, INFO }

data class SourceRef(
    val url: String,
    val title: String,
    val publisher: String = "",
    val publishedDate: String = "",
    val retrievedAt: Long = System.currentTimeMillis(),
    var snippet: String = "",
    val relevance: Double = 0.0,
    val supportsClaims: MutableList<String> = mutableListOf(),
    var verified: Boolean = false
)

data class ActivityEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val runId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: EventType,
    val phase: String = "",
    var status: EventStatus = EventStatus.INFO,
    val title: String,
    var detail: String = "",
    val toolOrCapability: String = "",
    val fileRefs: List<String> = emptyList(),
    val sourceRefs: List<SourceRef> = emptyList(),
    val errorCode: String? = null,
    val visibility: EventVisibility = EventVisibility.USER
)

/** Records emitted by the engine, rendered by the UI in human language. */
interface EventListener {
    fun onEvent(event: ActivityEvent)
}

/** Simple bus that fans engine events out to UI + telemetry + persistence. */
object EventBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<EventListener>()

    fun register(l: EventListener) { if (!listeners.contains(l)) listeners.add(l) }
    fun unregister(l: EventListener) { listeners.remove(l) }

    fun emit(e: ActivityEvent) {
        EngineStore.appendEvent(e)
        Telemetry.record(e)
        for (l in listeners) {
            try { l.onEvent(e) } catch (_: Exception) { }
        }
    }

    fun emit(
        runId: String, type: EventType, title: String,
        status: EventStatus = EventStatus.INFO,
        phase: String = "", detail: String = "",
        tool: String = "", files: List<String> = emptyList(),
        sources: List<SourceRef> = emptyList(),
        errorCode: String? = null,
        visibility: EventVisibility = EventVisibility.USER
    ): ActivityEvent {
        val e = ActivityEvent(
            runId = runId, type = type, title = title, status = status, phase = phase,
            detail = detail, toolOrCapability = tool, fileRefs = files,
            sourceRefs = sources, errorCode = errorCode, visibility = visibility
        )
        emit(e)
        return e
    }
}
