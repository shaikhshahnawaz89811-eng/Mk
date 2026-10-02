package com.codeassist.ai.ui

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Copies picked attachments into the app workspace inbox so engine tools
 * receive real local paths (spec: tools work on sandboxed files).
 */
object AttachmentStore {

    fun inbox(ctx: Context): File = File(ctx.filesDir, "workspace/inbox").apply { mkdirs() }

    /** Returns the absolute sandbox path, or null if the copy failed. */
    fun copyToInbox(ctx: Context, uriString: String, name: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(inbox(ctx), "${System.currentTimeMillis()}_$safe")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            out.absolutePath
        } catch (_: Exception) { null }
    }
}
