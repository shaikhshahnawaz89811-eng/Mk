package com.codeassist.ai

import com.codeassist.ai.engine.CheckpointManager
import com.codeassist.ai.engine.ImplementationContract
import com.codeassist.ai.engine.Llm
import com.codeassist.ai.engine.LspBridge
import com.codeassist.ai.engine.ModelFormat
import com.codeassist.ai.engine.PatchGuard
import com.codeassist.ai.engine.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelFormatTest {
    @Test fun `model format follows real container extension`() {
        assertEquals(ModelFormat.LITERTLM, ModelFormat.fromPath("gemma-4-E4B-it.litertlm"))
        assertEquals(ModelFormat.GGUF, ModelFormat.fromPath("qwen-coder.Q4_K_M.gguf"))
    }
}

class PatchGuardTest {
    private fun contract() = ImplementationContract(
        task_id = "test-1",
        mode = "existing_project",
        goal = "update one file",
        requirements = listOf("change code"),
        acceptance_criteria = listOf("valid source"),
        files_allowed_to_modify = listOf("src/Main.kt"),
        files_to_create = listOf("src/New.kt"),
        protected_files = listOf("README.md")
    )

    @Test fun `approved paths and syntax pass`() {
        val root = Files.createTempDirectory("mj-guard").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/Main.kt").writeText("fun main() { println(1) }")
            val reply = "FILE: src/Main.kt\n```kotlin\nfun main() { println(2) }\n```"
            val result = PatchGuard.validate(
                root, contract(), listOf(Llm.FileBlock("src/Main.kt", "fun main() { println(2) }")),
                reply, setOf("src/Main.kt")
            )
            assertTrue(result.ok)
            assertEquals(listOf("src/Main.kt"), result.changedPaths)
        } finally { root.deleteRecursively() }
    }

    @Test fun `unexpected path and delete markers are rejected before write`() {
        val root = Files.createTempDirectory("mj-guard").toFile()
        try {
            File(root, "src").mkdirs()
            val result = PatchGuard.validate(
                root, contract(),
                listOf(Llm.FileBlock("src/Unexpected.kt", "fun x() {}")),
                "DELETE: README.md\nFILE: src/Unexpected.kt\n```kotlin\nfun x() {}\n```",
                emptySet()
            )
            assertFalse(result.ok)
            assertTrue(result.errors.any { it.contains("Delete operations are blocked") })
            assertTrue(result.errors.any { it.contains("not authorized") })
        } finally { root.deleteRecursively() }
    }

    @Test fun `final scan detects files changed outside contract`() {
        val root = Files.createTempDirectory("mj-final").toFile()
        val checkpoint = Files.createTempDirectory("mj-checkpoint").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/Main.kt").writeText("fun main() { println(1) }")
            File(root, "unapproved.txt").writeText("before")
            val before = manifestFor(root)
            File(checkpoint, "_manifest.txt").writeText(before)
            File(root, "src/Main.kt").writeText("fun main() { println(2) }")
            File(root, "unapproved.txt").writeText("after")
            val result = PatchGuard.finalScan(root, contract(), checkpoint)
            assertFalse(result.ok)
            assertTrue(result.errors.any { it.contains("Unexpected changed paths") })
        } finally {
            root.deleteRecursively(); checkpoint.deleteRecursively()
        }
    }

    private fun manifestFor(root: File): String = root.walkTopDown().filter { it.isFile }.map {
        "${it.relativeTo(root).path}\t${it.length()}\t${sha256(it)}"
    }.sorted().joinToString("\n")

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(file.readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class WorkspaceIntakeTest {
    @Test fun `safe zip extraction rejects zip slip`() {
        val temp = Files.createTempDirectory("mj-zip").toFile()
        try {
            val zip = File(temp, "bad.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("../evil.txt"))
                out.write("evil".toByteArray())
                out.closeEntry()
            }
            var blocked = false
            try { WorkspaceManager.safeExtract(zip, File(temp, "out")) }
            catch (_: SecurityException) { blocked = true }
            assertTrue(blocked)
            assertFalse(File(temp.parentFile, "evil.txt").exists())
        } finally { temp.deleteRecursively() }
    }

    @Test fun `lsp bridge exposes deterministic fallback when unconfigured`() {
        val root = Files.createTempDirectory("mj-lsp").toFile()
        try {
            val status = LspBridge.status(root)
            assertTrue(status.contains("deterministic symbol-index fallback"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `checkpoint changed paths detects modified files`() {
        val root = Files.createTempDirectory("mj-check").toFile()
        val checkpoint = Files.createTempDirectory("mj-check-dir").toFile()
        try {
            val file = File(root, "a.txt")
            file.writeText("before")
            val md = MessageDigest.getInstance("SHA-256")
            md.update(file.readBytes())
            File(checkpoint, "_manifest.txt").writeText("a.txt\t${file.length()}\t${md.digest().joinToString("") { "%02x".format(it) }}")
            file.writeText("after")
            assertEquals(listOf("a.txt"), CheckpointManager.changedPaths(root, checkpoint))
        } finally { root.deleteRecursively(); checkpoint.deleteRecursively() }
    }
}
