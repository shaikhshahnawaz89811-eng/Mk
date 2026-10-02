package com.codeassist.ai.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Model lifecycle & ownership — Spec §4.
 *
 * Gemma 4 E4B IT: USER controlled (import / load / unload / delete).
 * Coder Helper:   GEMMA controlled (see CoderHelperManager in Runtime.kt).
 *
 * A model is LOADED only after the native runtime initializes and an
 * end-to-end inference probe succeeds. A file being present is not enough.
 */
object ModelLifecycle {

    const val GEMMA_ID = "gemma-4-e4b-it"
    const val CODER_ID = "coder-helper-code"

    private val executor = Executors.newSingleThreadExecutor()
    private var ctx: Context? = null

    interface Listener {
        fun onModelChanged(record: ModelRecord)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun register(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun unregister(l: Listener) { listeners.remove(l) }
    private fun notifyChanged(r: ModelRecord) = listeners.forEach { runCatching { it.onModelChanged(r) } }

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        Sandbox.init(c)
        EngineStore.init(c)
        reconcileOnStartup()
    }

    private fun freshRecord(modelId: String): ModelRecord = ModelRecord(
        modelId = modelId,
        name = if (modelId == GEMMA_ID) "Gemma 4 E4B IT" else "Coder Helper",
        state = ModelState.NOT_IMPORTED,
        owner = if (modelId == GEMMA_ID) ModelOwner.USER else ModelOwner.GEMMA,
        format = if (modelId == GEMMA_ID) ModelFormat.LITERTLM else ModelFormat.GGUF,
        capabilities = if (modelId == GEMMA_ID)
            listOf("orchestration", "function_calling", "multimodal", "multilingual")
        else
            listOf("coding", "refactoring", "debugging", "test_fixing", "code_review")
    )

    private fun record(modelId: String): ModelRecord {
        val rec = EngineStore.modelRecord(modelId) ?: return freshRecord(modelId)
        if (rec.format == ModelFormat.UNKNOWN || rec.format == ModelFormat.UNSUPPORTED) {
            rec.format = ModelFormat.fromPath(rec.storagePath)
            if (rec.format == ModelFormat.UNKNOWN) {
                // Migrate the old v2.2 "$modelId.model" naming without losing
                // a user's existing imported bytes.
                rec.format = when (modelId) {
                    GEMMA_ID -> ModelFormat.LITERTLM
                    CODER_ID -> ModelFormat.GGUF
                    else -> ModelFormat.UNSUPPORTED
                }
            }
        }
        return rec
    }

    fun gemma(): ModelRecord = record(GEMMA_ID)
    fun coder(): ModelRecord = record(CODER_ID)

    // ---------- Startup reconcile (Spec §24) ----------
    private fun reconcileOnStartup() {
        for (rec in EngineStore.modelRecords()) {
            migrateLegacyFilename(rec)
            rec.format = rec.format.takeIf { it != ModelFormat.UNKNOWN }
                ?: ModelFormat.fromPath(rec.storagePath)
            when (rec.state) {
                ModelState.LOADING, ModelState.UNLOADING, ModelState.IMPORTING -> {
                    rec.state = if (File(rec.storagePath).exists())
                        ModelState.IMPORTED_UNLOADED else ModelState.NOT_IMPORTED
                    rec.busy = false
                    EngineStore.upsertModelRecord(rec)
                }
                ModelState.BUSY -> {
                    // Native handles do not survive process death.
                    rec.state = if (File(rec.storagePath).exists()) ModelState.IMPORTED_UNLOADED else ModelState.NOT_IMPORTED
                    rec.busy = false
                    rec.loadedAt = 0
                    rec.lastError = ""
                    EngineStore.upsertModelRecord(rec)
                }
                ModelState.DELETING -> {
                    runCatching { File(rec.storagePath).delete() }
                    EngineStore.removeModelRecord(rec.modelId)
                }
                ModelState.LOADED -> {
                    // Runtime handles do not survive process death. A persisted
                    // LOADED flag is never trusted without a live native handle.
                    rec.state = if (File(rec.storagePath).exists())
                        ModelState.IMPORTED_UNLOADED else ModelState.NOT_IMPORTED
                    rec.busy = false
                    rec.loadedAt = 0
                    rec.lastError = ""
                    EngineStore.upsertModelRecord(rec)
                }
                else -> { }
            }
        }
    }

    private fun migrateLegacyFilename(rec: ModelRecord) {
        val old = File(rec.storagePath)
        if (!old.exists() || old.extension.lowercase(Locale.US) != "model") return
        val desiredExt = when (rec.modelId) {
            GEMMA_ID -> "litertlm"
            CODER_ID -> "gguf"
            else -> return
        }
        val newFile = File(old.parentFile, "${rec.modelId}.$desiredExt")
        if (!newFile.exists() && old.renameTo(newFile)) {
            rec.storagePath = newFile.absolutePath
            rec.format = if (desiredExt == "litertlm") ModelFormat.LITERTLM else ModelFormat.GGUF
            EngineStore.upsertModelRecord(rec)
            Telemetry.log("model", "Migrated legacy model filename: ${old.name} -> ${newFile.name}")
        }
    }

    // ---------- Import (Spec §4.1, duplicate protection) ----------

    data class ImportResult(
        val ok: Boolean,
        val message: String,
        val duplicate: Boolean = false,
        val record: ModelRecord? = null
    )

    /** Backward-compatible generic validator: supported runtime-backed formats only. */
    fun supportedModelFile(name: String): Boolean = supportedModelFile(name, null)

    /** Slot-specific validation prevents a GGUF file being imported as Gemma. */
    fun supportedModelFile(name: String, modelId: String?): Boolean {
        val n = name.lowercase(Locale.US)
        return when (modelId) {
            GEMMA_ID -> n.endsWith(".litertlm")
            CODER_ID -> n.endsWith(".gguf") || n.endsWith(".litertlm")
            else -> n.endsWith(".litertlm") || n.endsWith(".gguf")
        }
    }

    fun importFromUri(modelId: String, uri: Uri, displayName: String, onProgress: (Int) -> Unit,
                      cb: (ImportResult) -> Unit) {
        val c = ctx ?: return cb(ImportResult(false, "Engine not initialized"))
        executor.execute {
            var rec = record(modelId)
            if (rec.state == ModelState.IMPORTING) {
                return@execute post { cb(ImportResult(false, "Import already in progress")) }
            }
            if (rec.state == ModelState.LOADED || rec.state == ModelState.BUSY || rec.state == ModelState.LOADING) {
                return@execute post { cb(ImportResult(false, "Unload the model before replacing its file", record = rec)) }
            }
            val stateBefore = rec.state
            try {
                val format = modelFormat(displayName, modelId)
                    ?: throw IllegalArgumentException("Unsupported file for this model slot")
                rec.state = ModelState.IMPORTING
                rec.busy = false
                rec.lastError = ""
                rec.format = format
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)

                // Copy to a temporary file first. A crash or bad source never
                // leaves a half-written runnable model at the final path.
                val finalFile = File(Sandbox.modelsDir(), "${modelId}.${extensionFor(format)}")
                val tempFile = File(Sandbox.modelsDir(), "${modelId}.${extensionFor(format)}.importing")
                runCatching { tempFile.delete() }
                val digest = MessageDigest.getInstance("SHA-256")
                val total = querySize(c, uri).coerceAtLeast(1L)
                c.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw Exception("Cannot open selected file")
                    tempFile.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            copied += read
                            onProgress(((copied * 100) / total).toInt().coerceIn(0, 99))
                        }
                        out.flush()
                    }
                }
                val importedSize = tempFile.length()
                val hash = digest.digest().joinToString("") { "%02x".format(it) }

                verifyManifest(tempFile, displayName, format)

                // Duplicate check by content identity across both model slots.
                val existing = EngineStore.modelRecords().firstOrNull {
                    it.modelId != modelId && it.contentHash == hash && hash.isNotBlank()
                }
                if (existing != null) {
                    tempFile.delete()
                    rec.state = stateBefore
                    EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                    Telemetry.log("model", "Duplicate import rejected: $displayName")
                    return@execute post {
                        cb(ImportResult(false, "Already imported as ${existing.name}", duplicate = true, record = existing))
                    }
                }

                // Replace only after the new file is fully copied and validated.
                // Keep the previous file until the replacement is in place so a
                // rename/copy failure cannot destroy a working imported model.
                val backupFile = File(finalFile.parentFile, "${finalFile.name}.previous")
                if (backupFile.exists()) backupFile.delete()
                if (finalFile.exists() && !finalFile.renameTo(backupFile))
                    throw IllegalStateException("Could not stage the previous model for safe replacement")
                try {
                    if (!tempFile.renameTo(finalFile)) {
                        tempFile.copyTo(finalFile, overwrite = false)
                        tempFile.delete()
                    }
                    if (!finalFile.exists() || finalFile.length() != importedSize)
                        throw IllegalStateException("Imported model replacement verification failed")
                    backupFile.delete()
                } catch (replaceError: Exception) {
                    if (finalFile.exists()) finalFile.delete()
                    if (backupFile.exists()) backupFile.renameTo(finalFile)
                    throw replaceError
                }

                rec = record(modelId)
                rec.storagePath = finalFile.absolutePath
                rec.sizeBytes = finalFile.length()
                rec.contentHash = hash
                rec.version = hash.take(12)
                rec.format = format
                rec.name = if (modelId == GEMMA_ID) "Gemma 4 E4B IT" else "Coder Helper"
                rec.state = ModelState.IMPORTED_UNLOADED
                rec.busy = false
                rec.lastError = ""
                rec.importedAt = System.currentTimeMillis()
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                EventBus.emit("system", EventType.MODEL_LIFECYCLE,
                    "${rec.name} imported", EventStatus.DONE,
                    detail = "$displayName • ${rec.sizeBytes / (1024 * 1024)} MB • ${format.name}")
                post { cb(ImportResult(true, "Imported ${rec.name} • ${format.name}", record = rec)) }
            } catch (e: Exception) {
                runCatching {
                    File(Sandbox.modelsDir(), "${modelId}.litertlm.importing").delete()
                    File(Sandbox.modelsDir(), "${modelId}.gguf.importing").delete()
                }
                rec.state = if (File(rec.storagePath).exists() && stateBefore != ModelState.NOT_IMPORTED)
                    ModelState.IMPORTED_UNLOADED else ModelState.ERROR
                rec.lastError = e.message ?: "import failed"
                rec.busy = false
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                post { cb(ImportResult(false, "Import failed: ${rec.lastError}", record = rec)) }
            }
        }
    }

    private fun modelFormat(name: String, modelId: String): ModelFormat? = when {
        modelId == GEMMA_ID && name.lowercase(Locale.US).endsWith(".litertlm") -> ModelFormat.LITERTLM
        modelId == CODER_ID && name.lowercase(Locale.US).endsWith(".gguf") -> ModelFormat.GGUF
        modelId == CODER_ID && name.lowercase(Locale.US).endsWith(".litertlm") -> ModelFormat.LITERTLM
        else -> null
    }

    private fun extensionFor(format: ModelFormat): String = when (format) {
        ModelFormat.LITERTLM -> "litertlm"
        ModelFormat.GGUF -> "gguf"
        else -> "model"
    }

    private fun querySize(c: Context, uri: Uri): Long {
        return try {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.SIZE)
                if (cur.moveToFirst() && i >= 0) cur.getLong(i) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun verifyManifest(file: File, name: String, format: ModelFormat) {
        if (!file.exists() || file.length() < 1024)
            throw Exception("File too small to be a model (${file.length()} bytes)")
        if (!supportedModelFile(name, if (format == ModelFormat.LITERTLM) GEMMA_ID else CODER_ID))
            throw Exception("Unsupported model file")
        if (format == ModelFormat.GGUF) {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "GGUF")
                    throw Exception("Invalid GGUF file header")
            }
        }
    }

    // ---------- Load / Unload / Delete ----------

    fun load(modelId: String, onProgress: (String) -> Unit = {}, cb: (Boolean, String) -> Unit) {
        executor.execute {
            val rec = record(modelId)
            ModelGuards.blockedReason(LifecycleAction.LOAD, rec.state, rec.busy)?.let { reason ->
                return@execute post { cb(false, reason) }
            }
            try {
                RuntimeFactory.detach(modelId)
                rec.state = ModelState.LOADING
                rec.busy = false
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                post { onProgress("Verifying model files…") }

                if (rec.storagePath.isBlank() || !File(rec.storagePath).exists())
                    throw Exception("Model file missing — re-import required")

                val file = File(rec.storagePath)
                rec.format = rec.format.takeIf { it != ModelFormat.UNKNOWN }
                    ?: ModelFormat.fromPath(file.name)
                if (rec.format == ModelFormat.UNKNOWN || rec.format == ModelFormat.UNSUPPORTED)
                    throw Exception("Unsupported model format — import a .litertlm Gemma or GGUF Coder Helper")
                if (modelId == GEMMA_ID && rec.format != ModelFormat.LITERTLM)
                    throw Exception("Gemma 4 E4B IT must use a .litertlm runtime-backed model")
                if (modelId == CODER_ID && rec.format !in setOf(ModelFormat.GGUF, ModelFormat.LITERTLM))
                    throw Exception("Coder Helper format has no compatible runtime")

                post { onProgress("Verifying integrity…") }
                verifyIntegrity(rec)

                post { onProgress("Starting runtime…") }
                val runtime = RuntimeFactory.create(rec)
                try {
                    runtime.load(rec) { msg -> post { onProgress(msg) } }
                    if (!runtime.isRealModel) throw IllegalStateException("Runtime initialized without real model inference")
                    RuntimeFactory.attach(modelId, runtime)
                } catch (e: Exception) {
                    runCatching { runtime.unload() }
                    throw e
                }

                rec.state = ModelState.LOADED
                rec.loadedAt = System.currentTimeMillis()
                rec.lastError = ""
                rec.busy = false
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                EventBus.emit("system", EventType.MODEL_LIFECYCLE,
                    "${rec.name} loaded", EventStatus.DONE,
                    detail = runtime.describe())
                post { cb(true, "${rec.name} loaded — ${runtime.describe()}") }
            } catch (e: Exception) {
                RuntimeFactory.detach(modelId)
                rec.state = if (File(rec.storagePath).exists()) ModelState.ERROR else ModelState.NOT_IMPORTED
                rec.lastError = e.message ?: "load failed"
                rec.busy = false
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                EventBus.emit("system", EventType.MODEL_LIFECYCLE,
                    "${rec.name} load failed", EventStatus.ERROR,
                    detail = rec.lastError, visibility = EventVisibility.DIAGNOSTIC)
                post { cb(false, "Load failed: ${rec.lastError}") }
            }
        }
    }

    private fun verifyIntegrity(rec: ModelRecord) {
        val f = File(rec.storagePath)
        if (!f.exists()) throw Exception("Model file missing")
        if (rec.sizeBytes > 0 && f.length() != rec.sizeBytes)
            throw Exception("Model file changed since import (size mismatch)")
        if (rec.contentHash.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(rec.contentHash, ignoreCase = true))
                throw Exception("Model file changed since import (sha256 mismatch)")
        }
    }

    fun unload(modelId: String, cb: (Boolean, String) -> Unit) {
        executor.execute {
            val rec = record(modelId)
            ModelGuards.blockedReason(LifecycleAction.UNLOAD, rec.state, rec.busy)?.let { reason ->
                return@execute post { cb(false, reason) }
            }
            try {
                rec.state = ModelState.UNLOADING
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                RuntimeFactory.detach(modelId)
                rec.state = if (File(rec.storagePath).exists()) ModelState.IMPORTED_UNLOADED else ModelState.NOT_IMPORTED
                rec.loadedAt = 0
                rec.busy = false
                rec.lastError = ""
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                EventBus.emit("system", EventType.MODEL_LIFECYCLE, "${rec.name} unloaded", EventStatus.DONE)
                post { cb(true, "${rec.name} unloaded") }
            } catch (e: Exception) {
                rec.state = ModelState.ERROR; rec.lastError = e.message ?: "unload failed"
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                post { cb(false, "Unload failed: ${rec.lastError}") }
            }
        }
    }

    fun delete(modelId: String, cb: (Boolean, String) -> Unit) {
        executor.execute {
            val rec = record(modelId)
            ModelGuards.blockedReason(LifecycleAction.DELETE, rec.state, rec.busy)?.let { reason ->
                return@execute post { cb(false, reason) }
            }
            try {
                rec.state = ModelState.DELETING
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                RuntimeFactory.detach(modelId)
                if (rec.storagePath.isNotBlank()) File(rec.storagePath).delete()
                File(Sandbox.modelsDir(), "${modelId}.litertlm.importing").delete()
                File(Sandbox.modelsDir(), "${modelId}.gguf.importing").delete()
                EngineStore.removeModelRecord(modelId)
                EventBus.emit("system", EventType.MODEL_LIFECYCLE, "${rec.name} deleted", EventStatus.DONE)
                val fresh = freshRecord(modelId)
                notifyChanged(fresh)
                post { cb(true, "${rec.name} deleted") }
            } catch (e: Exception) {
                rec.state = ModelState.ERROR; rec.lastError = e.message ?: "delete failed"
                EngineStore.upsertModelRecord(rec); notifyChanged(rec)
                post { cb(false, "Delete failed: ${rec.lastError}") }
            }
        }
    }

    fun setBusy(modelId: String, busy: Boolean) {
        val rec = record(modelId)
        val ok = if (busy) ModelGuards.canMarkBusy(rec.state) else ModelGuards.canMarkIdle(rec.state)
        if (ok) {
            rec.state = if (busy) ModelState.BUSY else ModelState.LOADED
            rec.busy = busy
            EngineStore.upsertModelRecord(rec); notifyChanged(rec)
        }
    }

    private fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
