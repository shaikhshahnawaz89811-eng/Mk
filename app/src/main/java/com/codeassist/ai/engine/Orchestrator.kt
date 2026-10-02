package com.codeassist.ai.engine

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Core orchestration loop — Spec §5, §14, §15, §18, §19.
 *
 * USER REQUEST -> UNDERSTAND -> LOAD CONTEXT -> PLAN/DECIDE ->
 * SELECT SKILLS+TOOLS -> EXECUTE -> OBSERVE -> VALIDATE ->
 * REPAIR/RETRY if needed -> COMPLETE
 *
 * At any point: APPROVAL / CLARIFICATION / CREDENTIAL / EVIDENCE ->
 * WAIT -> RESUME SAME RUN.
 *
 * Stop is cooperative and state-aware: signal -> finish safe boundary ->
 * checkpoint -> PAUSED -> Continue / Discard.
 */
object Orchestrator {

    private val executor = Executors.newCachedThreadPool()
    private val activeRuns = ConcurrentHashMap<String, RunHandle>()

    class RunHandle(val run: RunRecord) {
        @Volatile var stopRequested = false
        @Volatile var thread: Thread? = null
    }

    interface RunListener {
        fun onRunUpdated(run: RunRecord)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<RunListener>()
    fun register(l: RunListener) { if (!listeners.contains(l)) listeners.add(l) }
    fun unregister(l: RunListener) { listeners.remove(l) }
    private fun notifyRun(r: RunRecord) { listeners.forEach { runCatching { it.onRunUpdated(r) } } }

    // ------------------------------------------------------------------
    // Run API — Spec §23
    // ------------------------------------------------------------------

    fun createRun(message: String, attachments: List<String>, projectId: String?,
                  conversationId: String, parentRunId: String? = null): RunRecord {
        ToolBootstrap.registerAll()
        val run = RunRecord(
            conversationId = conversationId,
            projectId = projectId,
            parentRunId = parentRunId,
            branchId = parentRunId?.let { java.util.UUID.randomUUID().toString().take(8) },
            userRequest = message
        )
        run.checkpoint["attachments"] = attachments.joinToString("|")
        run.checkpoint["snapshot"] = "valid"
        EngineStore.upsertRun(run)
        startExecution(run)
        return run
    }

    fun getRun(runId: String): RunRecord? = EngineStore.run(runId)

    fun stopRun(runId: String): RunRecord? {
        val run = EngineStore.run(runId) ?: return null
        if (!RunGuards.canStop(run.state)) return run
        transition(run, RunState.STOP_REQUESTED)
        activeRuns[runId]?.stopRequested = true
        EngineStore.upsertRun(run); notifyRun(run)
        return run
    }

    fun discardRun(runId: String): RunRecord? {
        val run = EngineStore.run(runId) ?: return null
        if (!RunGuards.canDiscard(run.state)) return run
        transition(run, RunState.DISCARDED)
        SkillRegistry.releaseAll(runId)
        activeRuns.remove(runId)
        EventBus.emit(runId, EventType.RUN_PAUSED, "Run discarded", EventStatus.INFO,
            visibility = EventVisibility.DIAGNOSTIC)
        EngineStore.upsertRun(run); notifyRun(run)
        return run
    }

    /**
     * Resume with a user decision (Spec §14 resumption invariant):
     * same logical run, persisted snapshot, no replayed side effects.
     */
    fun resumeRun(runId: String, decision: String, payload: Map<String, String> = emptyMap()): RunRecord? {
        val run = EngineStore.run(runId) ?: return null
        val interruption = run.pendingInterruption
        if (interruption != null) {
            if (!RunGuards.canExecuteApproval(interruption.state, true)) return run
            interruption.state = InterruptionState.RESOLVED
            interruption.decision = decision
            interruption.decisionAt = System.currentTimeMillis()
            interruption.payload.putAll(payload)
            Telemetry.log("approval", "${interruption.type} -> $decision", runId.take(8))
            run.pendingInterruption = null
        }
        if (!RunGuards.canResume(run.state, run.snapshotValid())) return run
        transition(run, RunState.RESUMED)
        run.checkpoint["resume_decision"] = decision
        run.checkpoint.putAll(payload)
        EngineStore.upsertRun(run); notifyRun(run)
        EventBus.emit(runId, EventType.RUN_RESUMED, "Continuing…", EventStatus.ACTIVE)
        startExecution(run, resumed = true)
        return run
    }

    /** Continue after a user-requested pause (same run id, same checkpoint). */
    fun continueRun(runId: String): RunRecord? {
        val run = EngineStore.run(runId) ?: return null
        if (!RunGuards.canResume(run.state, run.snapshotValid())) return run
        transition(run, RunState.RESUMED)
        EngineStore.upsertRun(run); notifyRun(run)
        EventBus.emit(runId, EventType.RUN_RESUMED, "Resuming work…", EventStatus.ACTIVE)
        startExecution(run, resumed = true)
        return run
    }

    // ------------------------------------------------------------------
    // Execution loop
    // ------------------------------------------------------------------

    private fun startExecution(run: RunRecord, resumed: Boolean = false) {
        val handle = activeRuns.getOrPut(run.runId) { RunHandle(run) }
        executor.execute {
            handle.thread = Thread.currentThread()
            try {
                execute(run, resumed)
            } catch (e: Exception) {
                if (RunGuards.isActive(run.state) || run.state == RunState.RESUMED) {
                    run.error = e.message ?: "unknown error"
                    transition(run, RunState.FAILED)
                    EventBus.emit(run.runId, EventType.RUN_FAILED,
                        "Run into a problem", EventStatus.ERROR, detail = run.error)
                    EngineStore.upsertRun(run); notifyRun(run)
                }
            } finally {
                activeRuns.remove(run.runId)
            }
        }
    }

    private fun execute(run: RunRecord, resumed: Boolean) {
        val routed = if (resumed && run.checkpoint["intent"] != null)
            restoreRoute(run)
        else IntentRouter.route(run.userRequest, run.projectId != null,
            run.checkpoint["attachments"]?.isNotBlank() == true)
        run.checkpoint["intent"] = routed.intent.name
        run.checkpoint["output"] = routed.outputType.name

        // --- UNDERSTAND ---
        if (!resumed) {
            transition(run, RunState.UNDERSTANDING)
            EventBus.emit(run.runId, EventType.RUN_STARTED, "Working…", EventStatus.ACTIVE,
                phase = "understand")
            transition(run, RunState.PLANNING)
        }

        // --- LOAD CONTEXT (Spec §5, §9) ---
        if (run.checkpoint["context_loaded"] != "yes") {
            run.currentStep = "context"
            val projectRoot = run.projectId?.let { ProjectSystem.rootFor(it) }
            if (run.projectId != null) {
                EventBus.emit(run.runId, EventType.CONTEXT_LOADED, "Project found",
                    EventStatus.DONE, phase = "context",
                    detail = ProjectSystem.contextSummary(run.projectId))
            }
            run.checkpoint["context_loaded"] = "yes"
            EngineStore.upsertRun(run)
            if (checkStop(run)) return
        }

        // --- PLAN (Spec §5, §13 MEDIUM shows compact plan) ---
        if (run.plan.isEmpty()) {
            run.plan = buildPlan(run, routed).toMutableList()
            run.currentStep = "plan"
            EngineStore.upsertRun(run); notifyRun(run)
        }

        // --- SELECT SKILLS + TOOLS (dynamic loading, Spec §6) ---
        if (run.activeSkillIds.isEmpty() && routed.suggestedSkills.isNotEmpty()) {
            SkillRegistry.activate(routed.suggestedSkills, run.runId)
            run.activeSkillIds = routed.suggestedSkills.toMutableList()
            EngineStore.upsertRun(run)
        }

        // --- EXECUTE per intent ---
        if (!RunGuards.isActive(run.state) && run.state != RunState.RESUMED) return
        transition(run, RunState.RUNNING)

        val ctx = ToolContext(runId = run.runId, projectId = run.projectId,
            projectRoot = run.projectId?.let { ProjectSystem.rootFor(it) },
            credentialIds = CredentialStore.list().map { it.credentialId })

        val answer = TaskExecutors.execute(run, routed, ctx, ::checkStop, ::interrupt)
        if (answer == null) return  // interrupted / paused — waiting for user

        // --- VALIDATE + COMPLETE ---
        transition(run, RunState.VALIDATING)
        run.finalAnswer = answer.text
        run.artifactRefs.addAll(answer.artifacts)
        SkillRegistry.release(run.activeSkillIds, run.runId)
        run.activeSkillIds.clear()
        transition(run, RunState.COMPLETED)
        EventBus.emit(run.runId, EventType.RUN_COMPLETED, "Done", EventStatus.DONE,
            detail = answer.artifacts.joinToString().ifBlank { "answer ready" },
            files = answer.artifacts)
        EngineStore.upsertRun(run); notifyRun(run)
    }

    private fun restoreRoute(run: RunRecord): RoutedIntent {
        val intent = runCatching { Intent.valueOf(run.checkpoint["intent"]!!) }
            .getOrDefault(Intent.UNKNOWN)
        val output = runCatching { OutputType.valueOf(run.checkpoint["output"]!!) }
            .getOrDefault(OutputType.NONE)
        val fresh = run.userRequest.let { IntentRouter.normalize(it) }
        return RoutedIntent(intent, output, 0.8, emptyMap(),
            needsWeb = "latest" in fresh || "search" in fresh || "docs" in fresh,
            freshnessRequired = "latest" in fresh,
            isMultiStep = true,
            suggestedSkills = run.activeSkillIds.ifEmpty {
                IntentRouter.route(run.userRequest, run.projectId != null, false).suggestedSkills },
            explanation = "restored")
    }

    private fun buildPlan(run: RunRecord, r: RoutedIntent): List<String> {
        return when (r.intent) {
            Intent.WEB_RESEARCH -> listOf("Search official sources", "Verify + compare", "Summarize with citations")
            Intent.CODING_TASK -> (if (r.needsWeb) listOf("Check latest documentation") else emptyList()) +
                listOf("Inspect project", "Plan changes", "Implement", "Run checks", "Validate")
            Intent.BUILD_APP -> ProjectSystem.pipelineFor(r.outputType)
            Intent.DOCUMENT_TASK -> ProjectSystem.pipelineFor(r.outputType)
            Intent.MEDIA_TASK -> ProjectSystem.pipelineFor(r.outputType)
            Intent.DATA_TASK -> ProjectSystem.pipelineFor(OutputType.SPREADSHEET)
            Intent.MODEL_MANAGEMENT -> listOf("Check model state", "Apply lifecycle action")
            Intent.CREDENTIAL_SETUP -> listOf("Identify provider", "Connect securely", "Verify handle")
            Intent.PROJECT_ACTION -> listOf("Inspect", "Package", "Deliver")
            else -> listOf("Respond")
        }
    }

    // ------------------------------------------------------------------
    // Stop / interruption plumbing
    // ------------------------------------------------------------------

    /** Cooperative stop check between steps (Spec §15). Returns true if paused. */
    private fun checkStop(run: RunRecord): Boolean {
        val handle = activeRuns[run.runId]
        if (handle?.stopRequested == true || run.state == RunState.STOP_REQUESTED) {
            // persist durable activity + checkpoint before marking paused
            run.checkpoint["paused_at_step"] = run.currentStep
            transition(run, RunState.PAUSED)
            run.pendingInterruption = Interruption(
                type = InterruptionType.APPROVAL,
                title = "Work paused",
                explanation = "Checkpoint saved at step '${run.currentStep}'.\n" +
                    "Continue resumes the same run; Discard abandons it safely.",
                options = listOf("Continue", "Discard"))
            EventBus.emit(run.runId, EventType.RUN_PAUSED, "Work paused", EventStatus.WARN,
                detail = "checkpoint saved at '${run.currentStep}' — Continue or Discard")
            EngineStore.upsertRun(run); notifyRun(run)
            return true
        }
        return false
    }

    /** Resolve a pause card: Continue resumes the same run, Discard ends it. */
    fun resolvePause(runId: String, cont: Boolean) {
        val run = EngineStore.run(runId) ?: return
        run.pendingInterruption?.let {
            it.state = InterruptionState.RESOLVED
            it.decision = if (cont) "Continue" else "Discard"
            it.decisionAt = System.currentTimeMillis()
        }
        run.pendingInterruption = null
        EngineStore.upsertRun(run)
        if (cont) continueRun(runId) else discardRun(runId)
    }

    /**
     * Create an interruption and park the run (Spec §14). Returns null to
     * signal "waiting for user"; execution resumes via resumeRun().
     */
    private fun interrupt(run: RunRecord, type: InterruptionType, title: String,
                          explanation: String, options: List<String>,
                          payload: Map<String, String> = emptyMap()): Nothing? {
        val it = Interruption(type = type, title = title, explanation = explanation,
            options = options, payload = payload.toMutableMap())
        run.pendingInterruption = it
        transition(run, RunState.INTERRUPTED)
        transition(run, RunState.WAITING)
        val evType = when (type) {
            InterruptionType.APPROVAL -> EventType.APPROVAL_REQUIRED
            InterruptionType.CLARIFICATION -> EventType.CLARIFICATION_REQUIRED
            InterruptionType.CREDENTIAL -> EventType.CREDENTIAL_REQUIRED
            InterruptionType.EVIDENCE_REQUIRED -> EventType.EVIDENCE_REQUIRED
        }
        EventBus.emit(run.runId, evType, title, EventStatus.WARN, detail = explanation)
        EventBus.emit(run.runId, EventType.WAITING_FOR_USER, "Waiting for your input",
            EventStatus.ACTIVE)
        EngineStore.upsertRun(run); notifyRun(run)
        return null
    }

    private fun transition(run: RunRecord, to: RunState) {
        if (run.state == to) return
        if (!RunGuards.canTransition(run.state, to)) {
            Telemetry.log("run", "Blocked illegal transition ${run.state} -> $to", run.runId.take(8), "warn")
            return
        }
        run.state = to
        run.updatedAt = System.currentTimeMillis()
        EngineStore.upsertRun(run)
    }
}

// ---------------------------------------------------------------------------
// Crash recovery — Spec §24
// ---------------------------------------------------------------------------

object RunRecovery {

    /**
     * startup -> read durable registry -> verify model files + state ->
     * reconcile RUNNING/WAITING runs -> recover or mark safely paused ->
     * restore activity timeline -> resume only when prerequisites valid.
     */
    fun reconcileOnStartup() {
        for (run in EngineStore.runs()) {
            when (run.state) {
                RunState.RUNNING, RunState.UNDERSTANDING, RunState.PLANNING,
                RunState.RESUMED, RunState.VALIDATING, RunState.STOP_REQUESTED -> {
                    // process died mid-run: checkpoint makes it safely pausable
                    run.state = RunState.PAUSED
                    run.checkpoint["recovery"] = "paused after app restart at '${run.currentStep}'"
                    EngineStore.upsertRun(run)
                    EventBus.emit(run.runId, EventType.RUN_PAUSED,
                        "Run safely paused after restart", EventStatus.WARN,
                        detail = run.checkpoint["recovery"] ?: "")
                }
                RunState.INTERRUPTED -> {
                    run.state = RunState.WAITING
                    EngineStore.upsertRun(run)
                }
                else -> { }
            }
        }
    }
}
