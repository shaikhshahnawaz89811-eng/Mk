package com.codeassist.ai.ui

import android.content.Context
import android.net.Uri
import com.codeassist.ai.data.AttachKind
import java.io.File
import java.util.UUID

/** Sandboxed attachment staging with per-file byte limits and cleanup on failure. */
object AttachmentStore {
    private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    private const val MAX_FILE_BYTES = 25L * 1024 * 1024

    fun inbox(ctx: Context): File = File(ctx.filesDir, "workspace/inbox").apply { mkdirs() }

    fun copyToInbox(ctx: Context, uriString: String, name: String, kind: AttachKind? = null): String? {
        val uri = Uri.parse(uriString)
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "file" }
        val out = File(inbox(ctx), "${UUID.randomUUID()}_$safe")
        val limit = if (kind == AttachKind.IMAGE || name.substringAfterLast('.', "").lowercase() in setOf("png","jpg","jpeg","webp")) MAX_IMAGE_BYTES else MAX_FILE_BYTES
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { src ->
                out.outputStream().use { dst ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw IllegalArgumentException("Attachment exceeds the ${limit / (1024 * 1024)} MB limit")
                        dst.write(buf, 0, n)
                    }
                }
            }
            if (out.length() <= 0L) throw IllegalArgumentException("Attachment is empty")
            out.absolutePath
        } catch (_: Exception) {
            runCatching { out.delete() }
            null
        }
    }

    fun cleanupStaged(paths: Collection<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }
}
