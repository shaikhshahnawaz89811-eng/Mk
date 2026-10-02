package com.codeassist.ai.engine

import java.io.File

/** Deterministic tools that expose the coding-pipeline services to the application layer. */

class WorkspaceScanTool : Tool {
    override val name = "workspace_scan"
    override val description = "Scanning workspace"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) }
            ?: ctx.projectRoot ?: return ToolResult(false, "", "no project/workspace path", ErrorClass.CONFLICTING_STATE)
        val files = root.walkTopDown().filter { it.isFile }.filterNot { it.path.contains("${File.separator}.git${File.separator}") || it.path.contains("${File.separator}build${File.separator}") || it.path.contains("${File.separator}.gradle${File.separator}") }.toList()
        val suspicious = files.filter { it.name.endsWith(".exe", true) || it.name.endsWith(".apk", true) || it.name.endsWith(".so", true) }.take(50)
        val sizes = files.sortedByDescending { it.length() }.take(20).joinToString("\n") { "${it.relativeTo(root).path}\t${it.length()} B" }
        return ToolResult(true,
            "Root: ${root.absolutePath}\nFiles: ${files.size}\nSources: ${WorkspaceManager.indexSources(root).size}\nSuspicious/binary: ${suspicious.size}\nLargest files:\n$sizes",
            metadata = mapOf("files" to files.size.toString(), "sources" to WorkspaceManager.indexSources(root).size.toString()))
    }
}

class PdfRenderTool : Tool {
    override val name = "pdf_render"
    override val description = "Rendering PDF pages"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "output_dir" to Tool.ArgRule("string", required = false),
        "max_pages" to Tool.ArgRule("int", required = false)
    )

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = false)
        if (!f.exists()) return ToolResult(false, "", "Missing PDF: ${f.name}", ErrorClass.MISSING_FILE)
        val outDir = call.args["output_dir"]?.toString()?.let { Sandbox.authorize(it, write = true) }
            ?: File(Sandbox.workspaceRoot(), "runs/${call.runId}/pdf-pages/${f.nameWithoutExtension}").apply { mkdirs() }
        val maxPages = (call.args["max_pages"]?.toString()?.toIntOrNull() ?: 12).coerceIn(1, 50)
        val pages = AttachmentAnalyzer.renderPdfPages(f, outDir, maxPages)
        return if (pages.isEmpty()) ToolResult(false, "", "No PDF pages rendered", ErrorClass.INVALID_TOOL_ARGS)
        else ToolResult(true, "Rendered ${pages.size} page image(s)", filesChanged = pages.map { it.absolutePath }, metadata = mapOf("pages" to pages.size.toString()))
    }
}

class LogAnalyzeTool : Tool {
    override val name = "log_analyze"
    override val description = "Analyzing error and build logs"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string"))

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = false)
        if (!f.exists()) return ToolResult(false, "", "Missing log: ${f.name}", ErrorClass.MISSING_FILE)
        val parsed = LogParser.parse(f.readText())
        return ToolResult(true, parsed.summary, metadata = mapOf(
            "errors" to parsed.errors.toString(),
            "warnings" to parsed.warnings.toString(),
            "stackTraces" to parsed.stackTraces.toString(),
            "repeated" to parsed.repeated.size.toString()))
    }
}

class ReferenceProjectTool : Tool {
    override val name = "reference_project_compare"
    override val description = "Comparing reference project patterns"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf(
        "reference" to Tool.ArgRule("string"),
        "target" to Tool.ArgRule("string", required = false)
    )

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val ref = Sandbox.authorize(call.args["reference"].toString(), write = false)
        val target = call.args["target"]?.toString()?.let { Sandbox.authorize(it, write = false) } ?: ctx.projectRoot
            ?: return ToolResult(false, "", "no target project", ErrorClass.CONFLICTING_STATE)
        if (!ref.exists()) return ToolResult(false, "", "reference project missing", ErrorClass.MISSING_FILE)
        val refFiles = WorkspaceManager.indexSources(ref).toSet()
        val targetFiles = WorkspaceManager.indexSources(target).toSet()
        val same = refFiles.intersect(targetFiles).sorted().take(100)
        val onlyRef = (refFiles - targetFiles).sorted().take(50)
        val onlyTarget = (targetFiles - refFiles).sorted().take(50)
        val refStack = ProjectSystem.detectStack(ref)
        val targetStack = ProjectSystem.detectStack(target)
        val compatibility = when {
            refStack.isBlank() || targetStack.isBlank() -> "UNKNOWN"
            refStack.equals(targetStack, true) && same.isNotEmpty() -> "COMPATIBLE"
            refStack.equals(targetStack, true) -> "COMPATIBLE_WITH_DIFFERENT_LAYOUT"
            else -> "INCOMPATIBLE_STACKS"
        }
        val assessmentText = when (compatibility) {
            "COMPATIBLE" -> "same stack with overlapping source patterns"
            "COMPATIBLE_WITH_DIFFERENT_LAYOUT" -> "same stack but different file layout; adaptation required"
            "INCOMPATIBLE_STACKS" -> "different stacks; adaptation required and reference remains read-only"
            else -> "compatibility could not be established from detected stack"
        }
        return ToolResult(true,
            "Reference stack=$refStack\nTarget stack=$targetStack\nAssessment=$compatibility — $assessmentText\n" +
                "Same file-patterns=${same.size}\n" + same.joinToString("\n") { "same: $it" } +
                "\nReference-only patterns=${onlyRef.size}\n" + onlyRef.joinToString("\n") { "reference-only: $it" } +
                "\nTarget-only patterns=${onlyTarget.size}\n" + onlyTarget.joinToString("\n") { "target-only: $it" } +
                "\nReference is READ ONLY; no files were copied.",
            metadata = mapOf("referenceStack" to refStack, "targetStack" to targetStack, "sameFiles" to same.size.toString(), "assessment" to compatibility, "referenceReadOnly" to "true"))
    }
}

class CodeIntelligenceTool : Tool {
    override val name = "code_intelligence"
    override val description = "Finding symbols, references and implementations"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string", required = false),
        "symbol" to Tool.ArgRule("string", required = false),
        "mode" to Tool.ArgRule("enum", required = false, allowed = listOf("symbols", "references", "lsp_status"))
    )

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = false) } ?: ctx.projectRoot
            ?: return ToolResult(false, "", "no project context", ErrorClass.CONFLICTING_STATE)
        return when (call.args["mode"]?.toString() ?: "symbols") {
            "references" -> {
                val symbol = call.args["symbol"]?.toString().orEmpty()
                if (symbol.isBlank()) return ToolResult(false, "", "symbol is required", ErrorClass.INVALID_TOOL_ARGS)
                ToolResult(true, ProjectIntelligence.references(root, symbol).joinToString("\n").ifBlank { "No references found" })
            }
            "lsp_status" -> ToolResult(true, LspBridge.status(root), metadata = mapOf("fallback" to "symbol-index"))
            else -> ToolResult(true, ProjectIntelligence.symbols(root).joinToString("\n").ifBlank { "No symbols found" })
        }
    }
}

class ExecutionValidateTool : Tool {
    override val name = "execution_validate"
    override val description = "Executing deterministic validation"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf("path" to Tool.ArgRule("string", required = false))

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val root = call.args["path"]?.toString()?.let { Sandbox.authorize(it, write = true) } ?: ctx.projectRoot
            ?: return ToolResult(false, "", "no project context", ErrorClass.CONFLICTING_STATE)
        val result = ExecutionEngine.validate(root, ProjectSystem.detectStack(root))
        val executions = result.executions.joinToString("\n") { "${it.status}: ${it.command}${it.exitCode?.let { c -> " (exit $c)" } ?: ""}" }
        return ToolResult(result.passed, (result.checks + executions).joinToString("\n"), result.errors.joinToString("\n"), if (result.passed) null else ErrorClass.BUILD_TEST_FAILURE)
    }
}
