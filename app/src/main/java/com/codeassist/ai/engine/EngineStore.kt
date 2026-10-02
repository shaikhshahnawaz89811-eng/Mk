package com.codeassist.ai.engine

import java.io.File

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Durable engine state — Spec §22 (backend data model), §24 (persistence).
 *
 * Survives process death: model registry, run records + checkpoints,
 * activity events, credential references (never secret values),
 * idempotency keys, project metadata handled by data.Store.
 */
object EngineStore {

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("codeassist_engine", Context.MODE_PRIVATE)
        }
    }

    private inline fun <reified T> readList(key: String): MutableList<T> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<T>>() {}.type) ?: mutableListOf()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun writeList(key: String, list: Any) {
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }

    // ---------- Model registry (Spec §22) ----------
    fun modelRecords(): MutableList<ModelRecord> = readList("model_registry")
    fun saveModelRecords(list: List<ModelRecord>) = writeList("model_registry", list)

    fun modelRecord(modelId: String): ModelRecord? = modelRecords().firstOrNull { it.modelId == modelId }

    fun upsertModelRecord(rec: ModelRecord) {
        val list = modelRecords()
        val i = list.indexOfFirst { it.modelId == rec.modelId }
        if (i >= 0) list[i] = rec else list.add(rec)
        saveModelRecords(list)
    }

    fun removeModelRecord(modelId: String) {
        saveModelRecords(modelRecords().filterNot { it.modelId == modelId })
    }

    // ---------- Runs (Spec §22 run record) ----------
    fun runs(): MutableList<RunRecord> = readList("runs")
    fun saveRuns(list: List<RunRecord>) = writeList("runs", list)

    fun run(runId: String): RunRecord? = runs().firstOrNull { it.runId == runId }

    fun upsertRun(run: RunRecord) {
        run.updatedAt = System.currentTimeMillis()
        val list = runs()
        val i = list.indexOfFirst { it.runId == run.runId }
        if (i >= 0) list[i] = run else list.add(0, run)
        // keep the store bounded
        if (list.size > 200) list.subList(200, list.size).clear()
        saveRuns(list)
    }

    fun removeRun(runId: String) {
        saveRuns(runs().filterNot { it.runId == runId })
    }

    // ---------- Activity events (bounded ring) ----------
    fun events(runId: String? = null): MutableList<ActivityEvent> {
        val all: MutableList<ActivityEvent> = readList("activity_events")
        return if (runId == null) all else all.filter { it.runId == runId }.toMutableList()
    }

    fun appendEvent(e: ActivityEvent) {
        val list: MutableList<ActivityEvent> = readList("activity_events")
        list.add(e)
        if (list.size > 1500) list.subList(0, list.size - 1500).clear()
        writeList("activity_events", list)
    }

    fun clearEvents() = prefs.edit().remove("activity_events").apply()

    // ---------- Idempotency keys (Spec §24) ----------
    fun hasOperation(opId: String): Boolean = prefs.getBoolean("op_$opId", false)
    fun markOperation(opId: String) = prefs.edit().putBoolean("op_$opId", true).apply()

    // ---------- Crash recovery flag ----------
    var lastSessionClean: Boolean
        get() = prefs.getBoolean("session_clean", true)
        set(v) = prefs.edit().putBoolean("session_clean", v).apply()
}

/** Model registry record — Spec §22. */
data class ModelRecord(
    val modelId: String,               // canonical stable identifier
    var name: String,
    var version: String = "",
    var contentHash: String = "",      // SHA-256 — duplicate identity
    var storagePath: String = "",
    var sizeBytes: Long = 0,
    var state: ModelState = ModelState.NOT_IMPORTED,
    var format: ModelFormat = ModelFormat.UNKNOWN,
    var owner: ModelOwner = ModelOwner.USER,
    var loadedAt: Long = 0,
    var lastError: String = "",
    var capabilities: List<String> = emptyList(),
    var busy: Boolean = false,
    var importedAt: Long = System.currentTimeMillis()
)

/** Interruption record — Spec §14 (four interruption types). */
data class Interruption(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: InterruptionType,
    var state: InterruptionState = InterruptionState.PENDING,
    val title: String,
    val explanation: String,
    val options: List<String> = emptyList(),   // e.g. ["Allow once", "Deny"]
    var decision: String? = null,
    var decisionAt: Long = 0,
    val payload: MutableMap<String, String> = mutableMapOf(), // pending action context
    val createdAt: Long = System.currentTimeMillis()
) {
    fun decisionFresh(): Boolean =
        state == InterruptionState.RESOLVED &&
            System.currentTimeMillis() - decisionAt < 10 * 60 * 1000
}

/** Run record — Spec §22, §14 resumption invariant. */
data class RunRecord(
    val runId: String = java.util.UUID.randomUUID().toString(),
    val conversationId: String,
    val projectId: String? = null,
    val parentRunId: String? = null,
    val branchId: String? = null,
    var state: RunState = RunState.QUEUED,
    var currentStep: String = "",
    var plan: MutableList<String> = mutableListOf(),
    var checkpoint: MutableMap<String, String> = mutableMapOf(),
    var pendingInterruption: Interruption? = null,
    var activeSkillIds: MutableList<String> = mutableListOf(),
    var activeToolCall: String = "",
    var coderSessionId: String? = null,
    var activityCursor: Int = 0,
    var artifactRefs: MutableList<String> = mutableListOf(),
    var userRequest: String = "",
    var finalAnswer: String = "",
    var error: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun snapshotValid(): Boolean {
        if (conversationId.isBlank() || checkpoint["snapshot"] == "corrupt") return false
        val path = checkpoint.entries.firstOrNull { it.key.startsWith("checkpoint_") && it.value.isNotBlank() }?.value
        return path?.let { File(it).exists() } == true
    }
}
