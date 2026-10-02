package com.codeassist.ai.engine

import java.io.File

/**
 * Project system — Spec §9 (projects & context layers), §10 (flexible
 * project/output types). A project is a capability graph + artifact
 * pipeline; APK is not the only output type.
 */

object ProjectSystem {

    fun projectsRoot(): File = File(Sandbox.workspaceRoot(), "projects").apply { mkdirs() }
    fun rootFor(projectId: String): File = File(projectsRoot(), projectId).apply { mkdirs() }

    // ---------- Stack detection (Spec §9) ----------
    fun detectStack(root: File): String {
        fun has(p: String) = File(root, p).exists()
        return when {
            has("settings.gradle") || has("app/build.gradle") -> "Android/Gradle"
            has("package.json") -> {
                val pkg = File(root, "package.json").readText()
                when {
                    "next" in pkg -> "React/Next.js"
                    "react" in pkg -> "React"
                    else -> "Node/JavaScript"
                }
            }
            has("Package.swift") || has("project.pbxproj") -> "iOS/Xcode"
            has("project.godot") -> "Game (Godot)"
            has("pubspec.yaml") -> "Flutter"
            has("index.html") -> "Website (static)"
            root.walkTopDown().any { it.extension == "html" } -> "Website (static)"
            root.walkTopDown().any { it.extension == "ipynb" } -> "Data notebook"
            else -> "Document/Generic"
        }
    }

    /** Conservative detection (Spec §9): multiple plausible -> ask, don't guess. */
    fun detectFromWorkspace(): Pair<File?, List<File>> {
        val candidates = projectsRoot().listFiles()?.filter { it.isDirectory } ?: emptyList()
        val withManifests = candidates.filter {
            File(it, "settings.gradle").exists() || File(it, "package.json").exists() ||
                File(it, "index.html").exists()
        }
        return when {
            withManifests.size == 1 -> withManifests[0] to emptyList()
            withManifests.size > 1 -> null to withManifests
            else -> null to emptyList()
        }
    }

    /** Project context layers (Spec §9) summarized for orchestration. */
    fun contextSummary(projectId: String?): String {
        if (projectId == null) return "no project selected"
        val root = rootFor(projectId)
        val stack = detectStack(root)
        val files = root.walkTopDown().count { it.isFile }
        val sizeKb = root.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / 1024
        val creds = CredentialStore.list().joinToString(", ") { it.credentialId }
            .ifBlank { "none" }
        return "project=$projectId stack=$stack files=$files size=${sizeKb}KB credentials=$creds"
    }

    // ---------- Output-type artifact pipelines (Spec §10) ----------
    fun pipelineFor(outputType: OutputType): List<String> = when (outputType) {
        OutputType.WEBSITE -> listOf("scaffold source tree", "implement pages", "static checks", "tests", "zip export + report")
        OutputType.ANDROID_APP -> listOf("scaffold Gradle project", "implement sources", "manifest/resource checks", "tests", "gradle_check", "zip export + report")
        OutputType.IOS_APP -> listOf("scaffold Xcode layout", "implement Swift sources", "tests", "export bundle")
        OutputType.GAME -> listOf("scaffold engine project", "scenes + loop", "assets manifest", "build notes", "export bundle")
        OutputType.FILM_VIDEO -> listOf("outline", "shot list", "captions file", "render plan", "export bundle")
        OutputType.ANIMATION -> listOf("scene plan", "frames/motion files", "render notes", "export bundle")
        OutputType.DOCUMENT_PDF -> listOf("gather content", "compose document", "render PDF/DOCX", "validate output")
        OutputType.SPREADSHEET -> listOf("extract data", "build workbook", "formulas", "validate output")
        OutputType.RESEARCH -> listOf("search", "verify sources", "synthesize", "cited report")
        OutputType.BACKEND -> listOf("schema", "API surface", "tests", "export bundle")
        OutputType.NONE -> listOf("respond")
    }
}

// ---------------------------------------------------------------------------
// Project tools
// ---------------------------------------------------------------------------

class ProjectInspectTool : Tool {
    override val name = "project_inspect"
    override val description = "Inspecting project"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = ctx.projectRoot
            ?: call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: ProjectSystem.projectsRoot()
        val stack = ProjectSystem.detectStack(root)
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val byExt = files.groupBy { it.extension.lowercase() }
            .toSortedMap(compareByDescending { files.count { f -> f.extension.equals(it, true) } })
        val sb = StringBuilder()
        sb.append("Stack: $stack\n")
        sb.append("Files: ${files.size}\n")
        sb.append("Top types: " + byExt.entries.take(6)
            .joinToString { "${it.key.ifBlank { "(none)" }}×${it.value.size}" } + "\n")
        files.sortedByDescending { it.length() }.take(8).forEach {
            sb.append("  ${Sandbox.displayPath(it)} (${it.length()} B)\n")
        }
        EventBus.emit(call.runId, EventType.CONTEXT_LOADED, "Project context loaded",
            EventStatus.DONE, detail = "$stack • ${files.size} files")
        return ToolResult(true, sb.toString(),
            metadata = mapOf("stack" to stack, "files" to files.size.toString()))
    }
}

class StackDetectTool : Tool {
    override val name = "stack_detect"
    override val description = "Detecting project stack"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = ctx.projectRoot
            ?: call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: return ToolResult(false, "", "no project context", ErrorClass.CONFLICTING_STATE)
        val stack = ProjectSystem.detectStack(root)
        return ToolResult(true, stack, metadata = mapOf("stack" to stack))
    }
}

class FileIndexTool : Tool {
    override val name = "file_index"
    override val description = "Indexing project files"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = ctx.projectRoot ?: ProjectSystem.projectsRoot()
        val ignored = setOf(".git", ".gradle", "build", "node_modules", "dist", "out", "target", "venv", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".cache", "coverage", "bin", "obj", ".scannerwork")
        val files = root.walkTopDown().onEnter { it.name !in ignored }.filter { it.isFile }
            .map { Sandbox.displayPath(it) to it.length() }.toList()
        val out = files.take(200).joinToString("\n") { "${it.first} (${it.second} B)" }
        return ToolResult(true, out.ifBlank { "(empty project)" },
            metadata = mapOf("count" to files.size.toString()))
    }
}

/**
 * Package tool — builds the deliverable bundle: ZIP of the project +
 * a build report (Spec §7 packaging category, §10 artifacts).
 */
class PackageTool : Tool {
    override val name = "package"
    override val description = "Packaging deliverable"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string", required = false),
        "name" to Tool.ArgRule("string", required = false)
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = ctx.projectRoot
            ?: call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: return ToolResult(false, "", "no project to package", ErrorClass.CONFLICTING_STATE)
        if (!root.exists() || root.walkTopDown().none { it.isFile })
            return ToolResult(false, "", "project is empty — nothing to package",
                ErrorClass.CONFLICTING_STATE)
        val requestedName = call.args["name"]?.toString().orEmpty()
        val safeName = File(if (requestedName.isBlank()) root.name + "-bundle.zip" else requestedName).name
            .let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val outDir = Sandbox.artifactsDir().apply { mkdirs() }
        val zip = File(outDir, safeName)
        val opId = "package-${root.name}-${zip.name}"
        // idempotent packaging (Spec §24)
        if (EngineStore.hasOperation(opId) && zip.exists())
            return ToolResult(true, "Already packaged: ${zip.name} (${zip.length() / 1024} KB)",
                filesChanged = listOf(zip.absolutePath))

        val ignoredDirs = setOf(".git", ".gradle", "build", "node_modules", "dist", "out", "target", "venv", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".cache", "coverage", "bin", "obj", ".scannerwork")
        val files = root.walkTopDown()
            .onEnter { d -> d.name !in ignoredDirs }
            .filter { it.isFile }
            .toList()
        val stack = ProjectSystem.detectStack(root)
        val report = buildString {
            append("# Delivery Manifest\n\n")
            append("Project: ${root.name}\nStack: $stack\nFiles packaged: ${files.size}\n")
            append("Packaged: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date())}\n\n")
            append("## Contents\n")
            files.sortedBy { it.path }.forEach { append("- ${it.relativeTo(root).path} (${it.length()} B)\n") }
        }
        val reportBase = File(safeName).nameWithoutExtension
        val reportFile = File(outDir, "${reportBase}-DELIVERY_MANIFEST.md")
        reportFile.writeText(report)

        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { z ->
            files.sortedBy { it.relativeTo(root).path }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(root).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        EngineStore.markOperation(opId)
        EventBus.emit(call.runId, EventType.ARTIFACT_READY, zip.name, EventStatus.DONE,
            detail = "${zip.length() / 1024} KB • ${files.size} files • delivery manifest kept outside project",
            files = listOf(zip.absolutePath, reportFile.absolutePath))
        return ToolResult(true, "Packaged ${zip.name} (${zip.length() / 1024} KB); report: ${reportFile.name}",
            filesChanged = listOf(zip.absolutePath, reportFile.absolutePath))
    }
}

// ---------------------------------------------------------------------------
// Credential tools — model sees credential IDs, never values (Spec §16)
// ---------------------------------------------------------------------------

class CredentialConnectTool : Tool {
    override val name = "credential_connect"
    override val description = "Connecting a credential"
    override val baseRisk = RiskTier.HIGH
    override val argSpec = mapOf(
        "provider" to Tool.ArgRule("string"),
        "scopes" to Tool.ArgRule("string", required = false)
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        // The secret itself is collected via a secure UI interruption, never
        // through tool args. This tool only records the handle.
        val provider = call.args["provider"].toString()
        return if (CredentialStore.hasActive(provider))
            ToolResult(true, "credential_id=${provider.lowercase()}_main already active")
        else ToolResult(false, "", "credential missing for $provider — requires secure connect UI",
            ErrorClass.AUTH_FAILURE)
    }
}

class CredentialLookupTool : Tool {
    override val name = "credential_lookup"
    override val description = "Checking credential status"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("provider" to Tool.ArgRule("string"))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val provider = call.args["provider"].toString()
        val ref = CredentialStore.list().firstOrNull { it.provider.equals(provider, true) }
        return ToolResult(true,
            ref?.let { "credential_id=${it.credentialId} status=${it.status} scopes=${it.scopes}" }
                ?: "no credential for $provider")
    }
}

/** Registers every tool — called once at engine init. */
object ToolBootstrap {
    private var done = false
    fun registerAll() {
        if (done) return
        done = true
        listOf(
            FileReadTool(), FileWriteTool(), FilePatchTool(), FileMoveTool(),
            FileDeleteTool(), FileListTool(), ArchiveTool(),
            FormatterTool(), StaticCheckTool(), TestRunnerTool(), GradleCheckTool(),
            WebSearchTool(), WebFetchTool(), CompareSourcesTool(),
            PdfExtractTool(), PdfCreateTool(), DocxCreateTool(), XlsxCreateTool(), CsvTool(),
            ImageTool(),
            WorkspaceScanTool(), PdfRenderTool(), LogAnalyzeTool(), ReferenceProjectTool(),
            CodeIntelligenceTool(), ExecutionValidateTool(),
            ProjectInspectTool(), StackDetectTool(), FileIndexTool(), PackageTool(),
            CredentialConnectTool(), CredentialLookupTool()
        ).forEach { ToolRegistry.register(it) }
    }
}
