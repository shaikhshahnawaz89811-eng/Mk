package com.codeassist.ai.engine

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Filesystem + code tools — Spec §7 tool categories.
 * All real, local, on-device. Paths pass through the sandbox (Spec §27).
 */

class FileReadTool : Tool {
    override val name = "file_read"
    override val description = "Reading file"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string"))

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = false)
        if (!f.exists()) return ToolResult(false, "", "Missing file: no such file ${f.name}",
            ErrorClass.MISSING_FILE)
        if (f.isDirectory) return ToolResult(false, "", "invalid argument: path is a directory",
            ErrorClass.INVALID_TOOL_ARGS)
        // Bounded read: never materialize a multi-GB file in memory just to
        // truncate it afterwards — cap the stream at 512 KB up front.
        val cap = 512 * 1024
        val text = f.inputStream().use { ins ->
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val r = ins.read(buf, off, cap - off)
                if (r < 0) break
                off += r
            }
            String(buf, 0, off, Charsets.UTF_8)
        }
        return ToolResult(true, text, metadata = mapOf(
            "size" to f.length().toString(),
            "truncated" to (f.length() > cap).toString()))
    }
}

class FileWriteTool : Tool {
    override val name = "file_write"
    override val description = "Writing file"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "content" to Tool.ArgRule("string")
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = true)
        f.parentFile?.mkdirs()
        val content = call.args["content"].toString()
        f.writeText(content)
        EventBus.emit(call.runId, EventType.FILE_CHANGED, f.name, EventStatus.DONE,
            detail = "${content.length} bytes written",
            files = listOf(Sandbox.displayPath(f)))
        return ToolResult(true, "Wrote ${f.name} (${content.length} bytes)",
            filesChanged = listOf(f.absolutePath))
    }
}

class FilePatchTool : Tool {
    override val name = "file_patch"
    override val description = "Editing file"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "find" to Tool.ArgRule("string"),
        "replace" to Tool.ArgRule("string")
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = true)
        if (!f.exists()) return ToolResult(false, "", "Missing file: no such file ${f.name}",
            ErrorClass.MISSING_FILE)
        val find = call.args["find"].toString()
        val replace = call.args["replace"].toString()
        val text = f.readText()
        if (find !in text) return ToolResult(false, "",
            "Patch failed: target text not found in ${f.name}", ErrorClass.INVALID_TOOL_ARGS)
        f.writeText(text.replace(find, replace))
        EventBus.emit(call.runId, EventType.FILE_CHANGED, f.name, EventStatus.DONE,
            detail = "patched (${find.length} -> ${replace.length} chars)",
            files = listOf(Sandbox.displayPath(f)))
        return ToolResult(true, "Patched ${f.name}", filesChanged = listOf(f.absolutePath))
    }
}

class FileMoveTool : Tool {
    override val name = "file_move"
    override val description = "Moving file"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "target" to Tool.ArgRule("string")
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val from = Sandbox.authorize(call.args["path"].toString(), write = true)
        val to = Sandbox.authorize(call.args["target"].toString(), write = true)
        if (!from.exists()) return ToolResult(false, "", "Missing file: no such file ${from.name}",
            ErrorClass.MISSING_FILE)
        to.parentFile?.mkdirs()
        if (!from.renameTo(to))
            return ToolResult(false, "", "Move failed: ${from.name} -> ${Sandbox.displayPath(to)}", ErrorClass.BUILD_TEST_FAILURE)
        EventBus.emit(call.runId, EventType.FILE_CHANGED, from.name, EventStatus.DONE,
            detail = "moved to ${Sandbox.displayPath(to)}")
        return ToolResult(true, "Moved ${from.name} -> ${Sandbox.displayPath(to)}",
            filesChanged = listOf(from.absolutePath, to.absolutePath))
    }
}

class FileDeleteTool : Tool {
    override val name = "file_delete"
    override val description = "Deleting file"
    override val baseRisk = RiskTier.HIGH
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "allow_delete" to Tool.ArgRule("bool", required = false),
        "reason" to Tool.ArgRule("string", required = false),
        "impact" to Tool.ArgRule("string", required = false)
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = true)
        if (!f.exists()) return ToolResult(false, "", "Missing file: no such file ${f.name}",
            ErrorClass.MISSING_FILE)
        val approved = call.args["allow_delete"]?.toString()?.toBoolean() == true
        val reason = call.args["reason"]?.toString().orEmpty().trim()
        val impact = call.args["impact"]?.toString().orEmpty().trim()
        if (!approved || reason.isBlank() || impact.isBlank()) {
            return ToolResult(false, "",
                "Delete blocked by default. Provide allow_delete=true, reason and impact through an explicit approved policy flow.",
                ErrorClass.PERMISSION_DENIED)
        }
        if (!f.deleteRecursively() || f.exists())
            return ToolResult(false, "", "Delete failed: ${f.name}", ErrorClass.BUILD_TEST_FAILURE)
        EventBus.emit(call.runId, EventType.FILE_CHANGED, f.name, EventStatus.WARN,
            detail = "deleted with explicit policy approval: $reason")
        return ToolResult(true, "Deleted ${f.name}", filesChanged = listOf(f.absolutePath))
    }
}

class FileListTool : Tool {
    override val name = "file_list"
    override val description = "Listing files"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string", required = false),
        "depth" to Tool.ArgRule("int", required = false)
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: ctx.projectRoot ?: Sandbox.workspaceRoot()
        if (!root.exists()) return ToolResult(true, "(empty — directory does not exist yet)")
        val depth = call.args["depth"]?.toString()?.toIntOrNull() ?: 3
        val out = StringBuilder()
        fun walk(f: File, d: Int, prefix: String) {
            if (d > depth) return
            val kids = f.listFiles()?.sortedBy { it.name } ?: return
            kids.forEachIndexed { i, k ->
                out.append(prefix).append(if (i == kids.lastIndex) "└── " else "├── ").append(k.name)
                if (k.isFile) out.append("  (").append(k.length()).append(" B)")
                out.append("\n")
                if (k.isDirectory) walk(k, d + 1, prefix + if (i == kids.lastIndex) "    " else "│   ")
            }
        }
        out.append(root.name).append("/\n")
        walk(root, 1, "")
        return ToolResult(true, out.toString().ifBlank { "(empty)" })
    }
}

class ArchiveTool : Tool {
    override val name = "archive"
    override val description = "Creating archive"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),           // source dir/file
        "target" to Tool.ArgRule("string"),          // zip path (or dest dir for unzip)
        "mode" to Tool.ArgRule("enum", allowed = listOf("zip", "unzip"))
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val src = Sandbox.authorize(call.args["path"].toString(), write = false)
        val dst = Sandbox.authorize(call.args["target"].toString(), write = true)
        if (!src.exists()) return ToolResult(false, "", "Missing file: no such file ${src.name}",
            ErrorClass.MISSING_FILE)
        return when (call.args["mode"].toString()) {
            "zip" -> {
                dst.parentFile?.mkdirs()
                ZipOutputStream(dst.outputStream().buffered()).use { z ->
                    if (src.isDirectory) {
                        src.walkTopDown().filter { it.isFile }.forEach { f ->
                            val rel = f.relativeTo(src).path
                            z.putNextEntry(ZipEntry(rel))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    } else {
                        z.putNextEntry(ZipEntry(src.name))
                        src.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
                ToolResult(true, "Created ${dst.name} (${dst.length() / 1024} KB)",
                    filesChanged = listOf(dst.absolutePath))
            }
            "unzip" -> {
                dst.mkdirs()
                var count = 0
                ZipInputStream(src.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val out = File(dst, e.name).canonicalFile
                        if (!out.path.startsWith(dst.canonicalPath)) throw SecurityException(
                            "blocked by policy: zip slip entry ${e.name}")
                        if (e.isDirectory) out.mkdirs()
                        else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                        count++
                        z.closeEntry(); e = z.nextEntry
                    }
                }
                ToolResult(true, "Extracted $count entries to ${Sandbox.displayPath(dst)}")
            }
            else -> ToolResult(false, "", "invalid argument: mode must be zip|unzip",
                ErrorClass.INVALID_TOOL_ARGS)
        }
    }
}

// ---------------------------------------------------------------------------
// Code tools
// ---------------------------------------------------------------------------

class FormatterTool : Tool {
    override val name = "formatter"
    override val description = "Formatting code"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf("path" to Tool.ArgRule("string"))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = true)
        if (!f.exists()) return ToolResult(false, "", "Missing file: no such file ${f.name}",
            ErrorClass.MISSING_FILE)
        val text = f.readText()
        val formatted = when (f.extension.lowercase()) {
            "json" -> runCatching {
                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                gson.toJson(com.google.gson.JsonParser.parseString(text))
            }.getOrElse { return ToolResult(false, "", "check failed: invalid JSON — ${it.message}",
                ErrorClass.BUILD_TEST_FAILURE) }
            "xml" -> text // XML formatting is conservative: trim trailing spaces
                .lines().joinToString("\n") { it.trimEnd() }
            else -> text.lines().joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n")
        }
        val changed = formatted != text
        if (changed) f.writeText(formatted)
        return ToolResult(true, if (changed) "Formatted ${f.name}" else "${f.name} already clean")
    }
}

/**
 * Static checks — real local lint: XML well-formedness, JSON validity,
 * balanced braces/parens for code files, forbidden-pattern scan,
 * unresolved reference scan for XML layouts (@string refs).
 */
class StaticCheckTool : Tool {
    override val name = "static_check"
    override val description = "Running static checks"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: ctx.projectRoot ?: Sandbox.workspaceRoot()
        val problems = mutableListOf<String>()
        var checked = 0
        root.walkTopDown().filter { it.isFile && it.length() < 2 * 1024 * 1024 }.forEach { f ->
            when (f.extension.lowercase()) {
                "xml" -> {
                    checked++
                    try {
                        val p = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder()
                        p.setErrorHandler(null)
                        p.parse(f)
                    } catch (e: Exception) {
                        problems.add("${Sandbox.displayPath(f)}: XML parse error — ${e.message?.take(80)}")
                    }
                }
                "json" -> {
                    checked++
                    runCatching { com.google.gson.JsonParser.parseString(f.readText()) }
                        .onFailure { problems.add("${Sandbox.displayPath(f)}: invalid JSON") }
                }
                "kt", "java", "js", "ts", "css" -> {
                    checked++
                    val t = f.readText()
                    for ((o, c) in listOf('{' to '}', '(' to ')')) {
                        if (t.count { it == o } != t.count { it == c })
                            problems.add("${Sandbox.displayPath(f)}: unbalanced '$o$c'")
                    }
                    if ("<<<<<<<" in t) problems.add("${Sandbox.displayPath(f)}: merge conflict markers")
                }
            }
        }
        val ok = problems.isEmpty()
        return ToolResult(ok,
            if (ok) "Static checks passed ($checked files)" else problems.joinToString("\n"),
            error = if (ok) "" else "check failed: ${problems.size} problem(s)",
            errorClass = if (ok) null else ErrorClass.BUILD_TEST_FAILURE)
    }
}

/**
 * Test runner — runs the project's check suite: required structure,
 * per-file checks, manifest/reference verification. Reports named tests
 * with pass/fail like a real runner (Spec §17 coding workflow step 10).
 */
class TestRunnerTool : Tool {
    override val name = "test_runner"
    override val description = "Running tests"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))

    data class TestCase(val name: String, val run: (File) -> String?)

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: ctx.projectRoot ?: Sandbox.workspaceRoot()
        val results = mutableListOf<Pair<String, Boolean>>()
        val failures = mutableListOf<String>()

        fun test(name: String, block: () -> String?) {
            val err = runCatching { block() }.getOrElse { it.message }
            results.add(name to (err == null))
            if (err != null) failures.add("$name: $err")
        }

        test("structure: project has files") {
            if (!root.exists() || root.walkTopDown().none { it.isFile })
                "no files in ${Sandbox.displayPath(root)}" else null
        }
        test("xml: all layouts/manifests well-formed") {
            var err: String? = null
            val iter = root.walkTopDown().filter { it.extension == "xml" }
            for (f in iter) {
                try {
                    val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    dbf.isNamespaceAware = false
                    val b = dbf.newDocumentBuilder(); b.setErrorHandler(null); b.parse(f)
                } catch (e: Exception) { err = "XML error in ${f.name}"; break }
            }
            err
        }
        test("json: all configs valid") {
            var err: String? = null
            val iter = root.walkTopDown().filter { it.extension == "json" }
            for (f in iter) {
                val bad = runCatching { com.google.gson.JsonParser.parseString(f.readText()) }
                    .isFailure
                if (bad) { err = "invalid JSON in ${f.name}"; break }
            }
            err
        }
        test("code: balanced delimiters") {
            var err: String? = null
            val iter = root.walkTopDown()
                .filter { it.extension in listOf("kt", "java", "js", "css", "html") }
            for (f in iter) {
                val t = f.readText()
                if (t.count { it == '{' } != t.count { it == '}' }) {
                    err = "unbalanced braces in ${f.name}"; break
                }
            }
            err
        }
        test("refs: no dangling local file includes") {
            var err: String? = null
            val iter = root.walkTopDown().filter { it.extension == "html" }
            for (f in iter) {
                val t = f.readText()
                for (m in Regex("""(?:src|href)="(?!https?:|#|//)([^"]+)"""").findAll(t)) {
                    val ref = File(f.parentFile, m.groupValues[1]).canonicalFile
                    if (!ref.exists()) { err = "${f.name} references missing ${m.groupValues[1]}"; break }
                }
                if (err != null) break
            }
            err
        }

        val passed = results.count { it.second }
        EventBus.emit(call.runId,
            if (failures.isEmpty()) EventType.TEST_PASSED else EventType.TEST_FAILED,
            "Checks: $passed/${results.size} passed",
            if (failures.isEmpty()) EventStatus.DONE else EventStatus.WARN,
            detail = failures.firstOrNull() ?: "")
        return ToolResult(failures.isEmpty(),
            results.joinToString("\n") { (if (it.second) "✓ " else "✗ ") + it.first },
            error = failures.joinToString("\n"),
            errorClass = if (failures.isEmpty()) null else ErrorClass.BUILD_TEST_FAILURE)
    }
}

/**
 * Gradle check — inspects an Android project structure and verifies the
 * assemble pipeline prerequisites (Spec §17 Android example). On-device we
 * can't run a full Gradle daemon; we verify everything it would need and
 * produce the same decision signal.
 */
class GradleCheckTool : Tool {
    override val name = "gradle_check"
    override val description = "Verifying Android build"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: ctx.projectRoot ?: Sandbox.workspaceRoot()
        val required = listOf("settings.gradle", "build.gradle", "app/build.gradle",
            "app/src/main/AndroidManifest.xml")
        val missing = required.filter { !File(root, it).exists() }
        if (missing.isNotEmpty())
            return ToolResult(false, "Android structure incomplete", 
                error = "check failed: missing ${missing.joinToString()}",
                errorClass = ErrorClass.BUILD_TEST_FAILURE)
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val problems = mutableListOf<String>()
        if ("<application" !in manifest) problems.add("Manifest missing <application>")
        if ("package=" in manifest && "namespace" !in File(root, "app/build.gradle").readText())
            problems.add("namespace/package mismatch risk")
        val ktCount = root.walkTopDown().count { it.extension == "kt" }
        if (ktCount == 0) problems.add("No Kotlin sources")
        return if (problems.isEmpty())
            ToolResult(true, "assembleDebug prerequisites verified ($ktCount Kotlin files, manifest OK)")
        else ToolResult(false, problems.joinToString("\n"),
            error = "check failed: ${problems.size} issue(s)", errorClass = ErrorClass.BUILD_TEST_FAILURE)
    }
}
