package com.codeassist.ai.models

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.diagnostics.DiagnosticsActivity
import com.codeassist.ai.engine.CoderHelperManager
import com.codeassist.ai.engine.Llm
import com.codeassist.ai.engine.LifecycleAction
import com.codeassist.ai.engine.ModelGuards
import com.codeassist.ai.engine.ModelLifecycle
import com.codeassist.ai.engine.ModelRecord
import com.codeassist.ai.engine.ModelState

/**
 * Model management — Spec §4.1. State-aware controls exactly per the
 * lifecycle table:
 *  - Loaded Gemma: Unload only (never Delete)
 *  - Unloaded: Load + Delete
 *  - Loading/importing: progress, no second load/delete
 *  - Duplicate import: detected by content hash -> "Already imported" + Load
 */
class ModelsFragment : Fragment(), ModelLifecycle.Listener {

    private lateinit var badgeGemma: TextView
    private lateinit var badgeCoder: TextView
    private lateinit var meta: TextView
    private lateinit var engineStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnImport: TextView
    private lateinit var btnLoad: TextView
    private lateinit var btnUnload: TextView
    private lateinit var btnDelete: TextView
    private lateinit var coderMeta: TextView
    private lateinit var progressCoder: ProgressBar
    private lateinit var btnCoderImport: TextView
    private lateinit var btnCoderDelete: TextView

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else "model.litertlm"
            } ?: "model.litertlm"
        }.getOrDefault("model.litertlm")

        if (!ModelLifecycle.supportedModelFile(name, ModelLifecycle.GEMMA_ID)) {
            toast("Gemma ke liye .litertlm model select karo")
            return@registerForActivityResult
        }
        progress.visibility = View.VISIBLE
        progress.progress = 0
        ModelLifecycle.importFromUri(ModelLifecycle.GEMMA_ID, uri, name, { p ->
            activity?.runOnUiThread { progress.progress = p }
        }) { res ->
            progress.visibility = View.GONE
            if (res.duplicate) {
                // Duplicate identity: offer Load, not a second copy (Spec §4.1)
                AlertDialog.Builder(requireContext())
                    .setTitle("Already imported")
                    .setMessage("This exact model (same content) is already on the device. No second copy was created.")
                    .setPositiveButton("Load") { _, _ -> doLoad() }
                    .setNegativeButton("OK", null)
                    .show()
            } else toast(res.message)
            refresh()
        }
    }

    private val pickCoder = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else "coder.gguf"
            } ?: "coder.gguf"
        }.getOrDefault("coder.gguf")
        if (!ModelLifecycle.supportedModelFile(name, ModelLifecycle.CODER_ID)) {
            toast("Coder Helper ke liye .gguf model select karo")
            return@registerForActivityResult
        }
        progressCoder.visibility = View.VISIBLE
        progressCoder.progress = 0
        ModelLifecycle.importFromUri(ModelLifecycle.CODER_ID, uri, name, { p ->
            activity?.runOnUiThread { progressCoder.progress = p }
        }) { res ->
            progressCoder.visibility = View.GONE
            if (res.duplicate) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Same file already imported")
                    .setMessage("Ye wahi file hai jo pehle se import hai (Gemma ya Coder Helper). " +
                        "Coder Helper ke liye alag, chhota coding model chuno.")
                    .setPositiveButton("OK", null)
                    .show()
            } else toast(res.message)
            refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_models, container, false)

    override fun onViewCreated(view: View, b: Bundle?) {
        badgeGemma = view.findViewById(R.id.badgeGemmaState)
        badgeCoder = view.findViewById(R.id.badgeCoderState)
        meta = view.findViewById(R.id.textGemmaMeta)
        engineStatus = view.findViewById(R.id.textEngineStatus)
        progress = view.findViewById(R.id.progressModel)
        btnImport = view.findViewById(R.id.btnImport)
        btnLoad = view.findViewById(R.id.btnLoad)
        btnUnload = view.findViewById(R.id.btnUnload)
        btnDelete = view.findViewById(R.id.btnDelete)

        coderMeta = view.findViewById(R.id.textCoderMeta)
        progressCoder = view.findViewById(R.id.progressCoder)
        btnCoderImport = view.findViewById(R.id.btnCoderImport)
        btnCoderDelete = view.findViewById(R.id.btnCoderDelete)
        btnCoderImport.setOnClickListener { pickCoder.launch(arrayOf("application/octet-stream", "application/*", "*/*")) }
        btnCoderDelete.setOnClickListener { confirmDeleteCoder() }

        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
        view.findViewById<View>(R.id.btnDiagnostics).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), DiagnosticsActivity::class.java))
        }

        btnImport.setOnClickListener {
            pickModel.launch(arrayOf("*/*"))
        }
        btnLoad.setOnClickListener { doLoad() }
        btnUnload.setOnClickListener {
            ModelLifecycle.unload(ModelLifecycle.GEMMA_ID) { _, m -> toast(m); refresh() }
        }
        btnDelete.setOnClickListener { confirmDelete() }

        ModelLifecycle.register(this)
        refresh()
    }

    private fun doLoad() {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        ModelLifecycle.load(ModelLifecycle.GEMMA_ID, { }) { _, m ->
            progress.isIndeterminate = false
            progress.visibility = View.GONE
            toast(m); refresh()
        }
    }

    private fun confirmDelete() {
        val g = ModelLifecycle.gemma()
        val blocked = ModelGuards.blockedReason(LifecycleAction.DELETE, g.state, g.busy)
        if (blocked != null) {
            // Explain instead of silently performing a destructive sequence (Spec §4.1 delete rule)
            AlertDialog.Builder(requireContext())
                .setTitle("Can't delete yet")
                .setMessage(blocked)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Gemma?")
            .setMessage("This removes the imported model file from the device. You can re-import it later.")
            .setPositiveButton("Delete") { _, _ ->
                ModelLifecycle.delete(ModelLifecycle.GEMMA_ID) { _, m -> toast(m); refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteCoder() {
        val c = ModelLifecycle.coder()
        if (c.busy || c.state == ModelState.BUSY || c.state == ModelState.LOADING) {
            AlertDialog.Builder(requireContext())
                .setTitle("Can't delete yet")
                .setMessage("Coder Helper abhi kaam kar raha hai. Kaam khatam hone ke baad delete karo.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Coder Helper?")
            .setMessage("Imported helper model file device se hat jayegi. Baad me dobara import kar sakte ho. " +
                "Tab tak Gemma coding khud karega.")
            .setPositiveButton("Delete") { _, _ ->
                // Gemma-owned lifecycle: unload first if needed, then delete the file
                CoderHelperManager.unloadWhenIdle { ok, msg ->
                    if (!ok) { toast(msg); refresh(); return@unloadWhenIdle }
                    CoderHelperManager.delete { _, m -> toast(m); refresh() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onModelChanged(record: ModelRecord) {
        activity?.runOnUiThread { if (view != null) refresh() }
    }

    private fun refresh() {
        val g = ModelLifecycle.gemma()
        badgeGemma.text = g.state.name.replace('_', ' ')
        meta.text = when (g.state) {
            ModelState.NOT_IMPORTED ->
                "Import the Gemma 4 E4B IT .litertlm model. Without it the assistant cannot generate real answers or code."
            ModelState.IMPORTED_UNLOADED ->
                "Imported • ${g.sizeBytes / (1024 * 1024)} MB • sha256:${g.contentHash.take(12)}\nReady to load."
            ModelState.LOADED -> "Loaded • ${g.sizeBytes / (1024 * 1024)} MB\n" +
                if (Llm.available()) "Real on-device inference: ACTIVE"
                else "Real inference: NOT active — ${Llm.unavailableReason()}"
            ModelState.BUSY -> "Working on a task…"
            ModelState.ERROR -> "Error: ${g.lastError}\nRetry or re-import."
            else -> g.state.name
        }

        // State-aware visibility (Spec §4.1): only the actions that are legal
        // in the current state are shown — no dead dimmed buttons. From ERROR
        // the user always gets Retry (load), Re-import and Delete (cleanup).
        btnImport.visibility = if (ModelGuards.canImport(g.state)) View.VISIBLE else View.GONE
        btnLoad.visibility = if (ModelGuards.canLoad(g.state)) View.VISIBLE else View.GONE
        btnUnload.visibility = if (g.state == ModelState.LOADED || g.state == ModelState.BUSY ||
            g.state == ModelState.UNLOADING) View.VISIBLE else View.GONE
        btnDelete.visibility = if (ModelGuards.canDelete(g.state)) View.VISIBLE else View.GONE

        btnLoad.isEnabled = ModelGuards.canLoad(g.state)
        btnUnload.isEnabled = ModelGuards.canUnload(g.state, g.busy)
        btnUnload.alpha = if (btnUnload.isEnabled) 1f else 0.45f

        // Contextual labels
        btnImport.text = if (g.state == ModelState.ERROR) "Re-import" else "Import"
        btnLoad.text = if (g.state == ModelState.ERROR) "Retry load" else "Load"

        // Even margins: first visible button gets no start margin
        var first = true
        for (v in listOf(btnImport, btnLoad, btnUnload, btnDelete)) {
            val lp = v.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.marginStart = if (first && v.visibility == View.VISIBLE) 0
                else if (v.visibility == View.VISIBLE) (10 * resources.displayMetrics.density).toInt()
                else lp.marginStart
            if (v.visibility == View.VISIBLE) first = false
            v.layoutParams = lp
        }

        val c = ModelLifecycle.coder()
        badgeCoder.text = when (c.state) {
            ModelState.NOT_IMPORTED -> "NOT IMPORTED"
            ModelState.LOADED -> "READY"
            ModelState.BUSY -> "WORKING"
            ModelState.ERROR -> "ERROR"
            ModelState.LOADING -> "STARTING"
            else -> "IDLE"
        }


        coderMeta.text = when (c.state) {
            ModelState.NOT_IMPORTED ->
                "Import a small Coder Helper model (.gguf). Gemma loads it only when a coding task needs it and unloads it afterwards. Coding implementation waits for this worker when a contract requires Coder."
            ModelState.ERROR -> "Error: ${c.lastError}\nCoder Helper will be retried on the next eligible task. Re-import or delete."
            else -> "Imported • ${c.sizeBytes / (1024 * 1024)} MB • sha256:${c.contentHash.take(12)}\nGemma loads and unloads it automatically."
        }
        val coderBusy = c.busy || c.state == ModelState.BUSY || c.state == ModelState.LOADING ||
            c.state == ModelState.IMPORTING
        btnCoderImport.visibility = if (c.state == ModelState.NOT_IMPORTED || c.state == ModelState.ERROR)
            View.VISIBLE else View.GONE
        btnCoderImport.text = if (c.state == ModelState.ERROR) "Re-import" else "Import"
        btnCoderDelete.visibility = if (c.state == ModelState.NOT_IMPORTED || c.state == ModelState.IMPORTING)
            View.GONE else View.VISIBLE
        btnCoderDelete.alpha = if (coderBusy) 0.45f else 1f

        // Engine status: is the assistant's brain actually running?
        engineStatus.text = buildString {
            append("Engine: ").append(if (Llm.available()) "ON (real inference)" else "OFF")
            append("  •  Gemma ").append(
                when (g.state) {
                    ModelState.LOADED -> "ready"
                    ModelState.BUSY -> "working"
                    ModelState.LOADING -> "starting…"
                    ModelState.ERROR -> "needs attention"
                    ModelState.IMPORTED_UNLOADED -> "imported, not loaded"
                    else -> "not imported"
                })
            append("  •  Coder Helper ").append(
                when (c.state) {
                    ModelState.LOADED -> "ready"
                    ModelState.BUSY -> "working"
                    ModelState.LOADING -> "starting…"
                    ModelState.ERROR -> "retry on next eligible task"
                    ModelState.NOT_IMPORTED -> "not imported (coding worker unavailable)"
                    else -> "idle (Gemma starts it when needed)"
                })
        }
    }

    override fun onDestroyView() {
        ModelLifecycle.unregister(this)
        super.onDestroyView()
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_LONG).show()
}
