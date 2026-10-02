package com.codeassist.ai.engine

/**
 * Formal state machines — Spec §21, §4, §14, §15.
 *
 * Every lifecycle and run transition in the app goes through these guards.
 * UI buttons are only a convenience layer; these guards are the enforcement.
 */

enum class ModelState {
    NOT_IMPORTED,
    IMPORTING,
    IMPORTED_UNLOADED,
    LOADING,
    LOADED,
    BUSY,
    UNLOADING,
    DELETING,
    ERROR
}

enum class RunState {
    QUEUED,
    UNDERSTANDING,
    PLANNING,
    RUNNING,
    INTERRUPTED,
    WAITING,
    RESUMED,
    VALIDATING,
    PAUSED,
    STOP_REQUESTED,
    COMPLETED,
    FAILED,
    DISCARDED
}

enum class InterruptionType {
    APPROVAL,
    CLARIFICATION,
    CREDENTIAL,
    EVIDENCE_REQUIRED
}

enum class InterruptionState { PENDING, RESOLVED, REVOKED }

enum class ModelOwner { USER, GEMMA }

/** Lifecycle actions from the safety matrix (Spec §20). */
enum class LifecycleAction { IMPORT, LOAD, UNLOAD, DELETE }

/**
 * Backend state guards for models. Pure functions — unit tested.
 * Spec §21.4 guard examples + §20 lifecycle safety matrix.
 */
object ModelGuards {

    /**
     * Spec §4.1: from ERROR the user must be able to retry (load),
     * re-import, or clean up (delete) — ERROR is recoverable, never a trap.
     */
    fun canImport(state: ModelState): Boolean =
        state == ModelState.NOT_IMPORTED || state == ModelState.IMPORTED_UNLOADED ||
        state == ModelState.ERROR

    fun canLoad(state: ModelState): Boolean =
        state == ModelState.IMPORTED_UNLOADED || state == ModelState.ERROR

    fun canUnload(state: ModelState, busy: Boolean): Boolean =
        state == ModelState.LOADED && !busy

    fun canDelete(state: ModelState): Boolean =
        state == ModelState.IMPORTED_UNLOADED || state == ModelState.ERROR

    /** Busy worker / loaded Gemma can move to BUSY only from LOADED. */
    fun canMarkBusy(state: ModelState): Boolean = state == ModelState.LOADED
    fun canMarkIdle(state: ModelState): Boolean = state == ModelState.BUSY

    /** Human explanation of why an action is blocked (shown in UI). */
    fun blockedReason(action: LifecycleAction, state: ModelState, busy: Boolean): String? {
        val blocked = when (action) {
            LifecycleAction.IMPORT -> !canImport(state)
            LifecycleAction.LOAD -> !canLoad(state)
            LifecycleAction.UNLOAD -> !canUnload(state, busy)
            LifecycleAction.DELETE -> !canDelete(state)
        }
        if (!blocked) return null
        return when (action) {
            LifecycleAction.IMPORT -> when (state) {
                ModelState.IMPORTING -> "Import already in progress"
                else -> "Import not allowed from state $state"
            }
            LifecycleAction.LOAD -> when (state) {
                ModelState.LOADING -> "Model is already loading"
                ModelState.LOADED, ModelState.BUSY -> "Model is already loaded"
                ModelState.UNLOADING -> "Wait for unload to finish"
                ModelState.IMPORTING -> "Wait for import to finish"
                ModelState.DELETING -> "Model is being deleted"
                ModelState.NOT_IMPORTED -> "Import the model first"
                ModelState.ERROR -> "Resolve the model error first (retry or re-import)"
                else -> "Load not allowed from state $state"
            }
            LifecycleAction.UNLOAD -> when {
                busy -> "Model is busy — stop current work first"
                state == ModelState.UNLOADING -> "Already unloading"
                state == ModelState.BUSY -> "Model is busy — stop current work first"
                else -> "Model is not loaded"
            }
            LifecycleAction.DELETE -> when (state) {
                ModelState.LOADED, ModelState.BUSY ->
                    "A loaded model can't be deleted — unload it first"
                ModelState.LOADING -> "Wait for load to finish before deleting"
                ModelState.UNLOADING -> "Wait for unload to finish before deleting"
                ModelState.IMPORTING -> "Wait for import to finish before deleting"
                ModelState.DELETING -> "Delete already in progress"
                ModelState.NOT_IMPORTED -> "Nothing to delete"
                else -> "Delete not allowed from state $state"
            }
        }
    }
}

/**
 * Guards for runs — Spec §14, §15, §21.4.
 */
object RunGuards {

    private val ACTIVE = setOf(
        RunState.QUEUED, RunState.UNDERSTANDING, RunState.PLANNING,
        RunState.RUNNING, RunState.RESUMED, RunState.VALIDATING
    )

    fun isActive(state: RunState): Boolean = state in ACTIVE

    fun canStop(state: RunState): Boolean = isActive(state)

    fun canResume(state: RunState, snapshotValid: Boolean): Boolean =
        (state == RunState.WAITING || state == RunState.PAUSED || state == RunState.INTERRUPTED) && snapshotValid

    fun canDiscard(state: RunState): Boolean =
        state == RunState.PAUSED || state == RunState.WAITING ||
        state == RunState.INTERRUPTED || state == RunState.FAILED

    fun isTerminal(state: RunState): Boolean =
        state == RunState.COMPLETED || state == RunState.FAILED || state == RunState.DISCARDED

    /**
     * Legal run transitions (Spec §14 diagram).
     * QUEUED -> UNDERSTANDING -> PLANNING -> RUNNING -> (INTERRUPTED -> WAITING -> RESUMED -> RUNNING)
     * RUNNING -> VALIDATING -> COMPLETED | (error -> repair -> retry) | FAILED
     * RUNNING -> STOP_REQUESTED -> PAUSED -> RESUMED | DISCARDED
     */
    fun canTransition(from: RunState, to: RunState): Boolean = when (from) {
        RunState.QUEUED -> to == RunState.UNDERSTANDING || to == RunState.DISCARDED
        RunState.UNDERSTANDING -> to == RunState.PLANNING || to == RunState.FAILED ||
            to == RunState.STOP_REQUESTED || to == RunState.INTERRUPTED
        RunState.PLANNING -> to == RunState.RUNNING || to == RunState.FAILED ||
            to == RunState.STOP_REQUESTED || to == RunState.INTERRUPTED
        RunState.RUNNING -> to == RunState.INTERRUPTED || to == RunState.VALIDATING ||
            to == RunState.STOP_REQUESTED || to == RunState.FAILED
        RunState.INTERRUPTED -> to == RunState.WAITING || to == RunState.DISCARDED
        RunState.WAITING -> to == RunState.RESUMED || to == RunState.DISCARDED ||
            to == RunState.PAUSED
        RunState.RESUMED -> to == RunState.RUNNING || to == RunState.FAILED
        RunState.VALIDATING -> to == RunState.COMPLETED || to == RunState.RUNNING || // repair/retry loops back
            to == RunState.FAILED || to == RunState.STOP_REQUESTED
        RunState.STOP_REQUESTED -> to == RunState.PAUSED || to == RunState.RUNNING
        RunState.PAUSED -> to == RunState.RESUMED || to == RunState.DISCARDED
        RunState.COMPLETED, RunState.FAILED, RunState.DISCARDED -> false
    }

    fun canExecuteApproval(istate: InterruptionState, decisionFresh: Boolean): Boolean =
        istate == InterruptionState.PENDING && decisionFresh
}
