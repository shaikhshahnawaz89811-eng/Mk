package com.codeassist.ai.engine

/**
 * Tool architecture — Spec §7.
 *
 * Tools are executable capabilities exposed to Gemma through a registry.
 * The model proposes structured tool calls; the application validates name
 * and arguments, executes, and returns the result — an application-side
 * loop, never unrestricted model execution.
 */

data class ToolCall(
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val runId: String = "",
    val callId: String = java.util.UUID.randomUUID().toString()
)

data class ToolResult(
    val ok: Boolean,
    val output: String,
    val error: String = "",
    val errorClass: ErrorClass? = null,
    val metadata: Map<String, String> = emptyMap(),
    val filesChanged: List<String> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    val durationMs: Long = 0
)

interface Tool {
    val name: String
    val description: String
    /** JSON-schema-ish arg spec: name -> (type, required, allowed values?) */
    val argSpec: Map<String, ArgRule>
    /** Which risk profile this tool inherently has */
    val baseRisk: RiskTier
    /** Domains a network tool may contact (empty = no network) */
    val allowedDomains: List<String> get() = emptyList()

    fun execute(call: ToolCall, ctx: ToolContext): ToolResult

    data class ArgRule(
        val type: String,                 // string|int|bool|enum|path|url
        val required: Boolean = true,
        val allowed: List<String> = emptyList()
    )
}

/** Context handed to tools: sandbox info + credential handles, never secrets. */
data class ToolContext(
    val runId: String,
    val projectId: String? = null,
    val projectRoot: java.io.File? = null,
    val credentialIds: List<String> = emptyList()
)

// ---------------------------------------------------------------------------
// Validation pipeline — Spec §7 tool validation rules
// ---------------------------------------------------------------------------

object ToolValidator {

    data class Validation(val ok: Boolean, val layer: String = "", val reason: String = "")

    fun validate(call: ToolCall, ctx: ToolContext): Validation {
        val tool = ToolRegistry.get(call.tool)
            ?: return Validation(false, "schema", "Unknown tool: ${call.tool}")

        // 1. Schema: required args, types, enums
        for ((arg, rule) in tool.argSpec) {
            val v = call.args[arg]
            if (rule.required && v == null)
                return Validation(false, "schema", "Missing required argument '$arg' for ${tool.name}")
            if (v != null) {
                when (rule.type) {
                    "int" -> if (v.toString().toIntOrNull() == null)
                        return Validation(false, "schema", "Argument '$arg' must be an integer")
                    "bool" -> if (v.toString() !in listOf("true", "false"))
                        return Validation(false, "schema", "Argument '$arg' must be a boolean")
                    "enum" -> if (rule.allowed.isNotEmpty() && v.toString() !in rule.allowed)
                        return Validation(false, "schema",
                            "Argument '$arg' must be one of ${rule.allowed}")
                    "url" -> if (!v.toString().startsWith("http"))
                        return Validation(false, "schema", "Argument '$arg' must be an http(s) URL")
                }
            }
        }

        // 2. Capability: tool must be granted by an active skill (or be a core tool)
        if (!ToolRegistry.isCore(call.tool) && call.tool !in SkillRegistry.grantedTools())
            return Validation(false, "capability",
                "Tool '${tool.name}' unavailable — no active skill grants it")

        // 3. Permission: filesystem writes stay inside sandbox roots
        if (call.args.containsKey("path") || call.args.containsKey("target")) {
            val p = (call.args["path"] ?: call.args["target"]).toString()
            try {
                Sandbox.authorize(p, write = tool.baseRisk != RiskTier.LOW)
            } catch (e: SecurityException) {
                return Validation(false, "permission", e.message ?: "path denied")
            }
        }

        // 4. Risk: destructive actions follow approval policy
        val risk = RiskEngine.assess(tool.name, call.args)
        if (risk.needsApproval && RiskEngine.approvalMode == ApprovalMode.ASK_WHEN_NEEDED &&
            risk.tier == RiskTier.HIGH)
            return Validation(false, "risk", "HIGH risk action requires approval: ${risk.reason}")

        // 5. Credential: tool receives credential IDs, never secret values
        call.args["credential"]?.toString()?.let { cid ->
            if (cid.contains("sk-") || cid.length > 40 && !cid.endsWith("_main"))
                return Validation(false, "credential", "Raw secret passed — use a credential_id")
            if (CredentialStore.lookup(cid) == null)
                return Validation(false, "credential", "Unknown credential_id: $cid")
        }

        // 6. Network: only approved destinations
        if (tool.allowedDomains.isNotEmpty()) {
            val url = call.args["url"]?.toString()
            if (url != null) {
                val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
                if (tool.allowedDomains.none { d -> host == d || host.endsWith(".$d") })
                    return Validation(false, "network", "Destination $host not in policy allowlist")
            }
        }

        // 7. Resource: reject duplicate operation when identical task is running
        if (ToolRegistry.isDuplicate(call))
            return Validation(false, "resource", "Identical ${tool.name} operation already running")

        return Validation(true)
    }
}

// ---------------------------------------------------------------------------
// Registry + executor — Spec §7, §23 tool execution API
// ---------------------------------------------------------------------------

object ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val coreTools = setOf("approval_request", "clarify", "credential_connect")

    fun register(t: Tool) { tools[t.name] = t }
    fun get(name: String): Tool? = tools[name]
    fun all(): List<Tool> = tools.values.toList()
    fun isCore(name: String) = name in coreTools

    /** True when an identical operation is currently in flight. */
    fun isDuplicate(call: ToolCall): Boolean =
        running.contains(call.tool + "|" + call.args.toString())

    private fun beginMark(call: ToolCall): String {
        val key = call.tool + "|" + call.args.toString()
        running.add(key)
        return key
    }
    private fun endMark(key: String) = running.remove(key)

    /**
     * Application-side execution loop (Spec §7 diagram):
     * proposed call -> validate -> execute -> capture result -> return.
     * Retries are bounded and classified (Spec §19).
     */
    fun execute(call: ToolCall, ctx: ToolContext, emitEvents: Boolean = true): ToolResult {
        val tool = get(call.tool)
            ?: return ToolResult(false, "", "Unknown tool: ${call.tool}", ErrorClass.INVALID_TOOL_ARGS)

        val validation = ToolValidator.validate(call, ctx)
        if (!validation.ok) {
            if (emitEvents) EventBus.emit(call.runId, EventType.TOOL_COMPLETED,
                "${tool.name} blocked", EventStatus.ERROR, tool = tool.name,
                detail = "[${validation.layer}] ${validation.reason}", errorCode = "VALIDATION_${validation.layer.uppercase()}")
            return ToolResult(false, "", "[${validation.layer}] ${validation.reason}",
                when (validation.layer) {
                    "schema" -> ErrorClass.INVALID_TOOL_ARGS
                    "permission", "risk" -> ErrorClass.PERMISSION_DENIED
                    "network" -> ErrorClass.UNSAFE_BLOCKED
                    "credential" -> ErrorClass.AUTH_FAILURE
                    "resource" -> ErrorClass.CONFLICTING_STATE
                    else -> ErrorClass.UNSAFE_BLOCKED
                })
        }

        val mark = beginMark(call)
        val started = System.currentTimeMillis()
        if (emitEvents) EventBus.emit(call.runId, EventType.TOOL_STARTED,
            tool.description, EventStatus.ACTIVE, tool = tool.name,
            visibility = EventVisibility.DIAGNOSTIC)
        return try {
            val result = tool.execute(call, ctx)
            val dur = System.currentTimeMillis() - started
            Telemetry.log("tool", tool.name, result.output.take(120),
                if (result.ok) "ok" else "error", dur)
            if (emitEvents) EventBus.emit(call.runId, EventType.TOOL_COMPLETED,
                tool.description, if (result.ok) EventStatus.DONE else EventStatus.ERROR,
                tool = tool.name, detail = if (result.ok) result.output.take(200) else result.error,
                files = result.filesChanged, sources = result.sources)
            result.copy(durationMs = dur)
        } catch (e: SecurityException) {
            val dur = System.currentTimeMillis() - started
            Telemetry.log("tool", tool.name, e.message ?: "", "blocked", dur)
            ToolResult(false, "", e.message ?: "blocked", ErrorClass.UNSAFE_BLOCKED, durationMs = dur)
        } catch (e: Exception) {
            val dur = System.currentTimeMillis() - started
            val classified = ErrorClassifier.classify(e.message ?: "", tool.name)
            Telemetry.log("tool", tool.name, e.message ?: "", "error", dur)
            if (emitEvents) EventBus.emit(call.runId, EventType.TOOL_COMPLETED,
                tool.description, EventStatus.ERROR, tool = tool.name,
                detail = e.message ?: "failed", errorCode = classified.errorClass.name)
            ToolResult(false, "", e.message ?: "tool failed", classified.errorClass, durationMs = dur)
        } finally {
            endMark(mark)
        }
    }
}
