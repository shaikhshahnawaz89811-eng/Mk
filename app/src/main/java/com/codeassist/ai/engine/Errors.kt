package com.codeassist.ai.engine

/**
 * Error handling & recovery — Spec §19.
 *
 * tool_error -> classify -> inspect -> likely cause -> repair action ->
 * retry with bounded attempts -> validate -> success OR user-facing block.
 */

enum class ErrorClass {
    INVALID_TOOL_ARGS,
    MISSING_FILE,
    BUILD_TEST_FAILURE,
    NETWORK_FAILURE,
    AUTH_FAILURE,
    PERMISSION_DENIED,
    MODEL_CRASH,
    RESOURCE_EXHAUSTION,
    CONFLICTING_STATE,
    UNSAFE_BLOCKED,
    UNKNOWN
}

data class ClassifiedError(
    val errorClass: ErrorClass,
    val likelyCause: String,
    val repairHint: String,
    val retryable: Boolean,
    val maxAttempts: Int,
    val backoffMs: Long
)

object ErrorClassifier {

    fun classify(raw: String, toolName: String = ""): ClassifiedError {
        val t = raw.lowercase()
        return when {
            "auth" in t || "401" in t || "403" in t && "permission" !in t ||
                "credential" in t || "token" in t ->
                ClassifiedError(
                    ErrorClass.AUTH_FAILURE,
                    "Provider authentication failed",
                    "Pause for credential reconnect, then resume the same run",
                    retryable = false, maxAttempts = 0, backoffMs = 0
                )

            "permission denied" in t || "not authorized" in t || "unsafe" in t ||
                "blocked by policy" in t || "outside project" in t ->
                ClassifiedError(
                    ErrorClass.PERMISSION_DENIED,
                    "Action was blocked by a permission or sandbox boundary",
                    "Ask for authorization or choose a safe alternative",
                    retryable = false, maxAttempts = 0, backoffMs = 0
                )

            "timeout" in t || "network" in t || "unreachable" in t ||
                "connection" in t || "socket" in t || "dns" in t ->
                ClassifiedError(
                    ErrorClass.NETWORK_FAILURE,
                    "Transient network failure",
                    "Bounded exponential backoff, then ask or fall back",
                    retryable = true, maxAttempts = 3, backoffMs = 800
                )

            "no such file" in t || "not found" in t || "missing file" in t ||
                "does not exist" in t ->
                ClassifiedError(
                    ErrorClass.MISSING_FILE,
                    "A required file or path is missing",
                    "Re-inspect the project tree and fix the path before retrying",
                    retryable = true, maxAttempts = 2, backoffMs = 0
                )

            "invalid argument" in t || "schema" in t || "validation failed" in t ||
                "bad args" in t ->
                ClassifiedError(
                    ErrorClass.INVALID_TOOL_ARGS,
                    "Tool arguments failed validation",
                    "Fix arguments from the validation error — do not blindly repeat",
                    retryable = true, maxAttempts = 2, backoffMs = 0
                )

            "build failed" in t || "test failed" in t || "compilation" in t ||
                "lint" in t || "check failed" in t ->
                ClassifiedError(
                    ErrorClass.BUILD_TEST_FAILURE,
                    "Build or test failure",
                    "Inspect output, patch the likely cause, retry validation",
                    retryable = true, maxAttempts = 3, backoffMs = 0
                )

            "crash" in t || "segfault" in t || "runtime died" in t || "worker died" in t ->
                ClassifiedError(
                    ErrorClass.MODEL_CRASH,
                    "Model/runtime crash",
                    "Recover runtime; helper crash -> keep code task failed and return control to Gemma/user; primary crash -> user-controlled state",
                    retryable = true, maxAttempts = 1, backoffMs = 500
                )

            "out of memory" in t || "oom" in t || "resource" in t || "disk full" in t ->
                ClassifiedError(
                    ErrorClass.RESOURCE_EXHAUSTION,
                    "Resource exhaustion (memory/disk)",
                    "Reduce context, unload temporary skills/helper, checkpoint, retry",
                    retryable = true, maxAttempts = 2, backoffMs = 1000
                )

            "conflict" in t || "state mismatch" in t || "stale" in t ->
                ClassifiedError(
                    ErrorClass.CONFLICTING_STATE,
                    "Conflicting project state",
                    "Reconcile state from durable records before retrying",
                    retryable = true, maxAttempts = 1, backoffMs = 0
                )

            else -> ClassifiedError(
                ErrorClass.UNKNOWN,
                "Unclassified failure${if (toolName.isNotEmpty()) " in $toolName" else ""}",
                "Diagnose, repair, bounded retry, then graceful degradation",
                retryable = true, maxAttempts = 1, backoffMs = 300
            )
        }
    }
}

/**
 * Bounded retry executor with exponential backoff (Spec §19 retry rules).
 * Returns null on success; last error on exhaustion.
 */
object RetryPolicy {

    fun <T> runWithRetry(
        classified: ClassifiedError,
        onAttempt: (attempt: Int) -> Unit = {},
        block: (attempt: Int) -> T
    ): Pair<T?, String?> {
        var attempt = 0
        var lastError: String? = null
        val max = if (classified.retryable) classified.maxAttempts else 0
        while (attempt <= max) {
            try {
                onAttempt(attempt)
                return block(attempt) to null
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                attempt++
                if (attempt <= max && classified.backoffMs > 0) {
                    try {
                        Thread.sleep(classified.backoffMs * (1L shl (attempt - 1)))
                    } catch (_: InterruptedException) { return null to lastError }
                }
            }
        }
        return null to (lastError ?: "unknown error")
    }
}
