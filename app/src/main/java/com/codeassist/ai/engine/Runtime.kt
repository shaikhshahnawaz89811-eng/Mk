package com.codeassist.ai.engine

import android.os.Build
import kotlinx.coroutines.runBlocking
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors

/** Supported on-device model container formats. */
enum class ModelFormat {
    LITERTLM,
    GGUF,
    UNSUPPORTED,
    UNKNOWN;

    companion object {
        fun fromPath(path: String): ModelFormat = when {
            path.lowercase().endsWith(".litertlm") -> LITERTLM
            path.lowercase().endsWith(".gguf") -> GGUF
            else -> UNKNOWN
        }
    }
}

/**
 * Common runtime contract. A runtime is not considered usable until load()
 * has initialized the native engine and completed the end-to-end probe.
 */
interface GemmaRuntime {
    fun load(record: ModelRecord, onProgress: (String) -> Unit)
    fun unload()
    fun describe(): String
    fun generate(prompt: String): String?
    fun generateWithImage(imagePath: String, prompt: String): String? = null
    val isRealModel: Boolean
    val format: ModelFormat
    val lastError: String
}

/**
 * Kept as an application-side deterministic fallback for routing/UX only.
 * It is never used as a substitute for an imported model runtime.
 */
class EmbeddedCore : GemmaRuntime {
    override fun load(record: ModelRecord, onProgress: (String) -> Unit) {
        onProgress("Embedded decision layer ready")
    }
    override fun unload() = Unit
    override fun describe(): String = "Embedded decision layer (no model inference)"
    override fun generate(prompt: String): String? = null
    override val isRealModel: Boolean = false
    override val format: ModelFormat = ModelFormat.UNKNOWN
    override val lastError: String = "Real model runtime not loaded"
}

/** Real Gemma runtime for .litertlm files. */
class LiteRtLmRuntime : GemmaRuntime {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile private var errorText: String = ""
    @Volatile private var runtimeReady: Boolean = false
    override val lastError: String get() = errorText
    override val format: ModelFormat = ModelFormat.LITERTLM
    override val isRealModel: Boolean get() = runtimeReady && engine != null && conversation != null && errorText.isBlank()

    override fun load(record: ModelRecord, onProgress: (String) -> Unit) {
        runtimeReady = false
        errorText = ""
        val file = File(record.storagePath)
        if (!file.exists()) throw IllegalStateException("Gemma model file missing: ${file.name}")
        if (!file.name.lowercase().endsWith(".litertlm")) {
            throw IllegalArgumentException("Gemma 4 E4B IT requires a .litertlm model")
        }
        onProgress("Initializing LiteRT-LM engine…")
        val backend = selectBackend(file)
        try {
            // Keep EngineConfig to the stable modelPath/backend surface.
            // Runtime-specific caches are owned by LiteRT-LM; the app must not
            // invent extra model files beside the imported .litertlm.
            val cfg = EngineConfig(
                modelPath = file.absolutePath,
                backend = backend,
                visionBackend = Backend.CPU()
            )
            val e = Engine(cfg)
            e.initialize()
            val c = e.createConversation()
            engine = e
            conversation = c
            runtimeReady = false
            errorText = ""
            onProgress("Running real Gemma inference probe…")
            val probe = c.sendMessage("Reply with exactly: OK", maxOutputToken = 16).toString().trim()
            if (probe.isBlank()) throw IllegalStateException("Real inference probe returned an empty response")
            runtimeReady = true
            onProgress("Gemma real inference ready")
            EventBus.emit(
                "system", EventType.MODEL_LIFECYCLE,
                "Gemma LiteRT-LM ready", EventStatus.DONE,
                detail = "backend=${backend.javaClass.simpleName} • probe=passed",
                visibility = EventVisibility.DIAGNOSTIC
            )
        } catch (e: Exception) {
            errorText = unwrapError(e)
            runtimeReady = false
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            conversation = null
            engine = null
            throw IllegalStateException("LiteRT-LM initialization failed: $errorText", e)
        }
    }

    override fun generate(prompt: String): String? {
        val c = conversation ?: return null
        return try {
            errorText = ""
            c.sendMessage(prompt, maxOutputToken = 4096).toString()
                .replace("<end_of_turn>", "")
                .replace("<start_of_turn>", "")
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            errorText = unwrapError(e)
            null
        }
    }

    override fun generateWithImage(imagePath: String, prompt: String): String? {
        val c = conversation ?: return null
        val image = File(imagePath)
        if (!image.exists()) {
            errorText = "Image missing: ${image.name}"
            return null
        }
        return try {
            errorText = ""
            c.sendMessage(
                Contents.of(
                    // LiteRT-LM multimodal contract: text must precede media.
                    Content.Text(prompt),
                    Content.ImageFile(image.absolutePath)
                ),
                maxOutputToken = 4096
            ).toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            errorText = unwrapError(e)
            null
        }
    }

    override fun unload() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        errorText = ""
        runtimeReady = false
    }

    override fun describe(): String = if (isRealModel)
        "Gemma 4 E4B IT (LiteRT-LM / CPU)"
    else
        "Gemma 4 E4B IT (LiteRT-LM unavailable)"

    private fun selectBackend(file: File): Backend {
        // GPU is opt-in for model files explicitly marked -gpu; generic models
        // start with CPU for the broadest device compatibility.
        return if (file.nameWithoutExtension.lowercase().endsWith("-gpu")) Backend.GPU()
        else Backend.CPU(threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
    }

    private fun unwrapError(t: Throwable): String {
        var cur: Throwable = t
        repeat(8) {
            val next = cur.cause ?: return@repeat
            cur = next
        }
        return (cur.message ?: t.message ?: t.javaClass.simpleName).take(320)
    }
}

/** GGUF coding-helper runtime backed by the maintained llama.cpp Android AAR. */
class LlamaCppRuntime : GemmaRuntime {
    private var generation: ((String, String) -> String?)? = null
    private var release: (() -> Unit)? = null
    @Volatile private var errorText: String = ""

    override val lastError: String get() = errorText
    override val format: ModelFormat = ModelFormat.GGUF
    override val isRealModel: Boolean get() = generation != null && errorText.isBlank()

    override fun load(record: ModelRecord, onProgress: (String) -> Unit) {
        val file = File(record.storagePath)
        if (!file.exists()) throw IllegalStateException("Coder Helper model file missing: ${file.name}")
        if (!file.name.lowercase().endsWith(".gguf")) {
            throw IllegalArgumentException("Coder Helper GGUF runtime requires a .gguf model")
        }
        if (!isArm64Device()) {
            throw IllegalStateException("GGUF helper runtime requires arm64-v8a on this build")
        }
        onProgress("Initializing GGUF Coder Helper…")
        try {
            val threads = (Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            val model = runBlocking {
                Llama.loadModel(
                    modelPath = file.absolutePath,
                    config = LlamaConfig(contextSize = 4096, threads = threads)
                )
            }
            generation = { prompt, system ->
                runCatching {
                    runBlocking {
                        Llama.complete(
                            model,
                            prompt = prompt,
                            systemPrompt = system,
                            maxTokens = 4096
                        ).text
                    }
                }.onFailure { errorText = (it.message ?: "GGUF generation failed").take(320) }
                    .getOrNull()
            }
            release = {
                runCatching { Llama.releaseModel(model) }
                generation = null
            }
            errorText = ""
            onProgress("Running Coder Helper inference probe…")
            val probe = generation?.invoke("Return only: OK", "You are a code-only helper. Return only OK.")
            if (probe.isNullOrBlank()) throw IllegalStateException("Coder Helper probe returned an empty response")
            onProgress("Coder Helper real inference ready")
        } catch (e: Exception) {
            release?.invoke()
            generation = null
            release = null
            errorText = (e.message ?: "GGUF runtime initialization failed").take(320)
            throw IllegalStateException(errorText, e)
        }
    }

    override fun generate(prompt: String): String? = generation?.invoke(prompt, "You are a code-only implementation worker. Follow the supplied contract exactly.")

    override fun unload() {
        release?.invoke()
        release = null
        generation = null
        errorText = ""
    }

    override fun describe(): String = if (isRealModel)
        "Coder Helper (GGUF / llama.cpp, CPU)"
    else
        "Coder Helper (GGUF runtime unavailable)"

    private fun isArm64Device(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
}

object RuntimeFactory {
    private val active = java.util.concurrent.ConcurrentHashMap<String, GemmaRuntime>()

    fun create(record: ModelRecord): GemmaRuntime {
        val file = File(record.storagePath)
        if (!file.exists()) throw IllegalStateException("Model file missing")
        return when (record.format.takeIf { it != ModelFormat.UNKNOWN } ?: ModelFormat.fromPath(file.name)) {
            ModelFormat.LITERTLM -> LiteRtLmRuntime()
            ModelFormat.GGUF -> LlamaCppRuntime()
            else -> throw IllegalArgumentException("Unsupported model format: ${file.extension}")
        }
    }

    fun attach(modelId: String, rt: GemmaRuntime) { active[modelId] = rt }

    fun detach(modelId: String) {
        active.remove(modelId)?.let { runCatching { it.unload() } }
    }

    fun get(modelId: String): GemmaRuntime? = active[modelId]
    fun isLoaded(modelId: String): Boolean = active.containsKey(modelId)
}

// ---------------------------------------------------------------------------
// Coding result contract — Spec §17
// ---------------------------------------------------------------------------

data class CodingResult(
    val status: String,
    val summary: String,
    val filesChanged: List<String> = emptyList(),
    val patchOrDiff: String = "",
    val commands: List<Pair<String, Int>> = emptyList(),
    val tests: List<Pair<String, Boolean>> = emptyList(),
    val buildArtifacts: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val nextStep: String = "",
    val confidence: String = "high"
)

// ---------------------------------------------------------------------------
// Coder Helper lifecycle — Gemma-controlled handoff
// ---------------------------------------------------------------------------

object CoderHelperManager {
    private val executor = Executors.newSingleThreadExecutor()
    private val handoffLock = Any()
    @Volatile var injectCrash: Boolean = false

    fun record(): ModelRecord = ModelLifecycle.coder()
    fun state(): ModelState = record().state

    fun ensureLoadedBlocking(): Pair<Boolean, String> {
        val rec = record()
        when (rec.state) {
            ModelState.LOADED, ModelState.BUSY -> {
                // Persisted state is not proof that the native handle survived
                // an Android process recreation. Reuse only with a live runtime.
                if (Llm.coder() != null) return true to "reused"
                val stale = record()
                stale.state = if (File(stale.storagePath).exists()) ModelState.IMPORTED_UNLOADED else ModelState.NOT_IMPORTED
                stale.busy = false
                stale.loadedAt = 0
                stale.lastError = ""
                EngineStore.upsertModelRecord(stale)
            }
            ModelState.LOADING -> {
                var waited = 0
                while (record().state == ModelState.LOADING && waited < 180_000) {
                    Thread.sleep(250); waited += 250
                }
                val ok = record().state == ModelState.LOADED && Llm.coder() != null
                return ok to if (ok) "loaded" else record().lastError.ifBlank { "load timeout" }
            }
            else -> Unit
        }
        if (injectCrash) {
            injectCrash = false
            markError("Injected crash (failure-injection test)")
            return false to "coder crashed"
        }
        val r = record()
        if (r.state == ModelState.NOT_IMPORTED || r.storagePath.isBlank() || !File(r.storagePath).exists())
            return false to "Coder Helper import nahi hua"
        if (r.state == ModelState.ERROR) {
            r.state = ModelState.IMPORTED_UNLOADED
            r.lastError = ""
            EngineStore.upsertModelRecord(r)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        var msg = "load timeout"
        ModelLifecycle.load(ModelLifecycle.CODER_ID, {}) { success, m ->
            ok = success; msg = m; latch.countDown()
        }
        if (!latch.await(240, java.util.concurrent.TimeUnit.SECONDS)) return false to "load timeout"
        val usable = ok && Llm.coder() != null
        if (ok && !usable) {
            ModelLifecycle.unload(ModelLifecycle.CODER_ID) { _, _ -> }
        }
        return usable to if (usable) "loaded" else msg
    }

    fun ensureLoaded(cb: (Boolean, String) -> Unit) {
        executor.execute { val r = ensureLoadedBlocking(); cb(r.first, r.second) }
    }

    /** Runs a helper task with a safe Gemma -> Coder -> Gemma handoff. */
    fun withHelperBlocking(taskName: String, work: () -> CodingResult): CodingResult = synchronized(handoffLock) {
        val gemmaRecord = ModelLifecycle.gemma()
        val gemmaWasLoaded = gemmaRecord.state == ModelState.LOADED && !gemmaRecord.busy
        if (gemmaWasLoaded) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var unloaded = false
            ModelLifecycle.unload(ModelLifecycle.GEMMA_ID) { ok, _ -> unloaded = ok; latch.countDown() }
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS) || !unloaded) {
                return@synchronized CodingResult(
                    "failed",
                    "Gemma ko safely unload nahi kar saka",
                    errors = listOf("model handoff aborted before Coder start")
                )
            }
        }

        val (loaded, why) = ensureLoadedBlocking()
        if (!loaded) {
            val restored = !gemmaWasLoaded || restoreGemmaBlocking()
            val errors = buildList {
                add(why)
                if (gemmaWasLoaded && !restored) add("Gemma restore failed after Coder load failure")
            }
            return@synchronized CodingResult("failed", "Coder Helper load nahi hua", errors = errors)
        }

        ModelLifecycle.setBusy(ModelLifecycle.CODER_ID, true)
        var result = CodingResult("failed", "Coder Helper task did not produce a result")
        try {
            if (injectCrash) {
                injectCrash = false
                throw RuntimeException("worker died: injected crash")
            }
            result = work()
        } catch (e: Exception) {
            markError(e.message ?: "worker died")
            result = CodingResult("failed", "Coder Helper crashed: ${e.message}", errors = listOf(e.message ?: "crash"))
        } finally {
            ModelLifecycle.setBusy(ModelLifecycle.CODER_ID, false)
            // Requested handoff order: after Coder finishes, restore Gemma first;
            // only then release the Coder Helper. This keeps the master brain
            // available before the helper memory is returned to the OS.
            var gemmaRestored = !gemmaWasLoaded
            if (gemmaWasLoaded) gemmaRestored = restoreGemmaBlocking()

            val coder = ModelLifecycle.coder()
            if (coder.state == ModelState.LOADED || coder.state == ModelState.BUSY) {
                val latch = java.util.concurrent.CountDownLatch(1)
                ModelLifecycle.unload(ModelLifecycle.CODER_ID) { _, _ -> latch.countDown() }
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            } else {
                RuntimeFactory.detach(ModelLifecycle.CODER_ID)
            }

            EventBus.emit(
                "system", EventType.CODER_STATUS, "$taskName complete", EventStatus.DONE,
                detail = "Gemma restored before Coder release=$gemmaRestored",
                visibility = EventVisibility.DIAGNOSTIC
            )
            if (gemmaWasLoaded && !gemmaRestored) {
                result = CodingResult(
                    "failed",
                    "Coder finished, but Gemma could not be restored safely",
                    errors = result.errors + "Gemma restore failed after Coder handoff"
                )
            }
        }
        result
    }

    fun delegate(task: String, work: () -> CodingResult): CodingResult =
        withHelperBlocking(task, work)

    fun unloadWhenIdle(cb: (Boolean, String) -> Unit = { _, _ -> }) {
        val rec = record()
        if (rec.state == ModelState.BUSY || rec.busy) return cb(false, "Coder Helper is busy — safe stop first")
        if (rec.state != ModelState.LOADED) return cb(true, "already unloaded")
        ModelLifecycle.unload(ModelLifecycle.CODER_ID, cb)
    }

    fun onMemoryPressure() {
        val rec = record()
        if (rec.state == ModelState.LOADED && !rec.busy) unloadWhenIdle()
    }

    fun delete(cb: (Boolean, String) -> Unit) {
        val rec = record()
        when (rec.state) {
            ModelState.IMPORTED_UNLOADED, ModelState.ERROR -> ModelLifecycle.delete(ModelLifecycle.CODER_ID, cb)
            else -> cb(false, ModelGuards.blockedReason(LifecycleAction.DELETE, rec.state, rec.busy) ?: "Delete not allowed")
        }
    }

    private fun restoreGemmaBlocking(): Boolean {
        if (ModelLifecycle.gemma().state == ModelState.LOADED || ModelLifecycle.gemma().state == ModelState.BUSY) return true
        if (ModelLifecycle.gemma().state != ModelState.IMPORTED_UNLOADED) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        ModelLifecycle.load(ModelLifecycle.GEMMA_ID, {}) { success, _ ->
            ok = success
            latch.countDown()
        }
        return latch.await(240, java.util.concurrent.TimeUnit.SECONDS) && ok && Llm.gemma() != null
    }

    private fun markError(msg: String) {
        // Release the native helper handle before persisting ERROR; otherwise
        // the lifecycle guard (correctly) prevents an ERROR model from being
        // unloaded and the native memory could remain resident.
        RuntimeFactory.detach(ModelLifecycle.CODER_ID)
        val r = record()
        r.state = ModelState.ERROR
        r.lastError = msg
        r.busy = false
        EngineStore.upsertModelRecord(r)
    }

    fun recoverOnce(cb: (Boolean, String) -> Unit) {
        val r = record()
        if (r.state == ModelState.ERROR) {
            r.state = ModelState.IMPORTED_UNLOADED
            r.lastError = ""
            EngineStore.upsertModelRecord(r)
        }
        ensureLoaded(cb)
    }
}
