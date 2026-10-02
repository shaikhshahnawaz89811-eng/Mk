package com.codeassist.ai.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Attachment

/**
 * Attachment rules (industry-standard chat limits):
 *  - images: max 10 MB each
 *  - zip:    max 25 MB
 *  - other:  max 25 MB
 *  - max 5 attachments, 50 MB total per message
 */
class AttachmentHelper {

    companion object {
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
        const val MAX_COUNT = 5
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private var pickImages: ActivityResultLauncher<Array<String>>
    private var pickZip: ActivityResultLauncher<Array<String>>
    private var pickFiles: ActivityResultLauncher<Array<String>>

    var current: MutableList<Attachment> = mutableListOf()
        private set

    var onChanged: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var resolver: android.content.ContentResolver
    private var toastCtx: android.content.Context

    constructor(activity: AppCompatActivity) {
        resolver = activity.contentResolver
        toastCtx = activity
        pickImages = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onPicked(uris, AttachKind.IMAGE) }
        pickZip = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(listOfNotNull(uri), null) }
        pickFiles = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onPicked(uris, null) }
    }

    constructor(fragment: Fragment) {
        resolver = fragment.requireContext().contentResolver
        toastCtx = fragment.requireContext()
        pickImages = fragment.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onPicked(uris, AttachKind.IMAGE) }
        pickZip = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(listOfNotNull(uri), null) }
        pickFiles = fragment.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onPicked(uris, null) }
    }

    fun openImages() = pickImages.launch(arrayOf("image/*"))
    fun openZip() = pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    fun openFiles() = pickFiles.launch(arrayOf("*/*"))

    fun remove(a: Attachment) {
        current.remove(a)
        onChanged?.invoke()
    }

    fun clear() {
        current.clear()
        onChanged?.invoke()
    }

    private fun onPicked(uris: List<Uri>, forceKind: AttachKind?) {
        if (uris.isEmpty()) return
        for (uri in uris) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            val (name, size, mime) = queryMeta(uri)
            val kind = forceKind ?: detectKind(name, mime)
            if (!validate(kind, name, size)) continue
            if (current.size >= MAX_COUNT) {
                error("You can attach up to $MAX_COUNT files per message.")
                break
            }
            val total = current.sumOf { it.size } + size
            if (total > MAX_TOTAL_BYTES) {
                error("Total attachment size can't exceed ${formatSize(MAX_TOTAL_BYTES)}.")
                break
            }
            current.add(Attachment(uri.toString(), name, size, mime, kind))
        }
        onChanged?.invoke()
    }

    private fun validate(kind: AttachKind, name: String, size: Long): Boolean {
        return when (kind) {
            AttachKind.IMAGE -> if (size > MAX_IMAGE_BYTES) {
                error("\"$name\" is ${formatSize(size)}. Images are limited to ${formatSize(MAX_IMAGE_BYTES)} each.")
                false
            } else true
            else -> if (size > MAX_FILE_BYTES) {
                error("\"$name\" is ${formatSize(size)}. Files are limited to ${formatSize(MAX_FILE_BYTES)} each.")
                false
            } else true
        }
    }

    private fun error(msg: String) {
        onError?.invoke(msg) ?: Toast.makeText(toastCtx, msg, Toast.LENGTH_LONG).show()
    }

    private fun detectKind(name: String, mime: String): AttachKind = when {
        mime.startsWith("image/") -> AttachKind.IMAGE
        name.lowercase().endsWith(".zip") || mime.contains("zip") -> AttachKind.ZIP
        else -> AttachKind.FILE
    }

    private fun queryMeta(uri: Uri): Triple<String, Long, String> {
        var name = "file"
        var size = 0L
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: "file"
                    if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (_: Exception) { }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (size <= 0L) {
            size = try { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (_: Exception) { 0L }
        }
        return Triple(name, size, mime)
    }
}
