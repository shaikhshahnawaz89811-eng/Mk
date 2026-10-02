package com.codeassist.ai.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Skills architecture — Spec §6.
 *
 * Skills are capability packages that teach the assistant how to perform a
 * category of work. They are not separate brains. Gemma decides which skill
 * or combination is relevant and loads them dynamically.
 *
 * Safety: activation is explicit in backend state; only trusted (bundled,
 * signed-by-build) packages load; each skill declares the tools it may
 * request; skill instructions can never override app security policy; a
 * malformed skill fails closed.
 */

data class Skill(
    val id: String,
    val displayName: String,          // human-readable capability name
    val category: String,
    val description: String,
    val declaredTools: List<String>,  // tools this skill may request
    val dependsOn: List<String> = emptyList(),
    val trusted: Boolean = true,      // only trusted packages are loadable
    val instructions: String = ""     // guidance merged into orchestration context
) {
    fun validate(): Boolean {
        // fail closed on malformed/conflicting skill packages
        return id.isNotBlank() && displayName.isNotBlank() && trusted &&
            declaredTools.all { it.isNotBlank() } && id.matches(Regex("[a-z0-9_]+"))
    }
}

data class ActiveSkill(
    val skill: Skill,
    val activatedAt: Long = System.currentTimeMillis(),
    var useCount: Int = 0
)

object SkillRegistry {

    private val catalog = LinkedHashMap<String, Skill>()
    private val active = ConcurrentHashMap<String, ActiveSkill>()

    init {
        // Skill catalog — Spec §6 list
        register(Skill("coding", "Coding", "engineering",
            "Implementation, refactoring, debugging, review, patches",
            listOf("file_read", "file_write", "file_patch", "file_list", "formatter",
                "static_check", "test_runner", "web_fetch", "workspace_scan", "pdf_render",
                "log_analyze", "reference_project_compare", "code_intelligence", "execution_validate", "package"),
            instructions = "Inspect before editing. Small verified diffs. Run checks after edits."))
        register(Skill("android", "Android", "engineering",
            "Android/Gradle projects: structure, manifest, resources, build pipeline, APK/AAB packaging guidance",
            listOf("file_read", "file_write", "file_patch", "file_list", "static_check",
                "test_runner", "gradle_check", "package"),
            dependsOn = listOf("coding"),
            instructions = "Follow Android project conventions: settings.gradle, app module, manifest, res. assembleDebug flow for APKs."))
        register(Skill("ios", "iOS", "engineering",
            "iOS/Xcode project structure, Swift sources, archive/export guidance",
            listOf("file_read", "file_write", "file_list"),
            dependsOn = listOf("coding")))
        register(Skill("website", "Website", "engineering",
            "Website creation: HTML/CSS/JS structure, responsive layout, accessibility",
            listOf("file_read", "file_write", "file_patch", "file_list", "static_check",
                "test_runner", "package"),
            dependsOn = listOf("coding"),
            instructions = "Responsive by default, semantic HTML, accessible contrast, small assets."))
        register(Skill("game", "Game", "engineering",
            "Game projects: loop, scenes, assets, builds",
            listOf("file_read", "file_write", "file_list", "package"),
            dependsOn = listOf("coding")))
        register(Skill("research", "Research", "knowledge",
            "Source gathering, comparison, verification, cited summaries",
            listOf("web_search", "web_fetch", "compare_sources", "file_write"),
            instructions = "Prefer primary/authoritative sources. Record dates. Never present conflicts as settled."))
        register(Skill("websearch", "Web search", "knowledge",
            "Fresh/current information lookup with source records",
            listOf("web_search", "web_fetch"),
            instructions = "Retrieve -> compare -> verify -> attribute -> cite."))
        register(Skill("git", "Git & version control", "engineering",
            "History-aware edits, patches, changelogs",
            listOf("file_read", "file_write", "file_patch")))
        register(Skill("database", "Database", "engineering",
            "Schema design, migrations, queries, app backends",
            listOf("file_read", "file_write", "test_runner"),
            dependsOn = listOf("coding")))
        register(Skill("data", "Data analysis", "data",
            "Tables, statistics, charts, validation",
            listOf("file_read", "csv_tool", "xlsx_create", "chart_data", "file_write")))
        register(Skill("ui", "UI / design implementation", "design",
            "Layouts, themes, accessibility, motion",
            listOf("file_read", "file_write", "file_patch")))
        register(Skill("testing", "Testing / QA", "engineering",
            "Unit/integration checks, lint, verification passes",
            listOf("test_runner", "static_check", "file_read")))
        register(Skill("files", "File management", "system",
            "Workspace file organization, move/rename, archives",
            listOf("file_read", "file_write", "file_move", "file_delete", "file_list", "archive")))
        register(Skill("export", "Export / packaging", "system",
            "ZIP bundles, build reports, artifact collection",
            listOf("package", "archive", "file_read", "file_list")))
        register(Skill("pdf_document", "PDF / document", "document",
            "PDF read/extract/render, DOCX creation, document reports",
            listOf("pdf_extract", "pdf_render", "docx_create", "pdf_create", "file_read", "file_write")))
        register(Skill("spreadsheet", "Spreadsheet", "document",
            "XLSX/CSV workbooks with formulas, charts data, validation",
            listOf("csv_tool", "xlsx_create", "file_read", "file_write")))
        register(Skill("video", "Video", "media",
            "Clips, timelines, captions, render planning (export via media pipeline)",
            listOf("file_read", "file_write", "package")))
        register(Skill("animation", "Animation / motion", "media",
            "Scenes, frames, motion files, render planning",
            listOf("file_read", "file_write", "package")))
        register(Skill("assets", "Asset generation", "media",
            "Asset manifests, placeholders, image info/resize/convert, resource organization",
            listOf("file_write", "file_list", "file_read", "image_tool")))
    }

    private fun register(s: Skill) { catalog[s.id] = s }

    fun all(): List<Skill> = catalog.values.toList()
    fun get(id: String): Skill? = catalog[id]

    /** Dynamic loading — Spec §6: load only relevant, keep while needed, release after. */
    fun activate(ids: List<String>, runId: String): List<Skill> {
        val loaded = mutableListOf<Skill>()
        for (id in ids.distinct()) {
            val skill = catalog[id] ?: continue
            if (!skill.validate()) {
                EventBus.emit(runId, EventType.DIAGNOSTIC, "Skill rejected: $id",
                    EventStatus.ERROR, detail = "malformed or untrusted package",
                    visibility = EventVisibility.DIAGNOSTIC)
                continue  // fail closed
            }
            // dependencies first (composition)
            for (dep in skill.dependsOn) {
                catalog[dep]?.let { d ->
                    if (d.validate() && active.putIfAbsent(dep, ActiveSkill(d)) == null) {
                        loaded.add(d)
                        EventBus.emit(runId, EventType.SKILL_ACTIVE, d.displayName,
                            EventStatus.INFO, phase = "skills",
                            detail = "dependency of ${skill.displayName}",
                            visibility = EventVisibility.DIAGNOSTIC)
                    }
                }
            }
            val existing = active.putIfAbsent(id, ActiveSkill(skill))
            if (existing == null) {
                loaded.add(skill)
                EventBus.emit(runId, EventType.SKILL_ACTIVE, skill.displayName,
                    EventStatus.INFO, phase = "skills", detail = skill.description,
                    visibility = EventVisibility.DIAGNOSTIC)
            } else existing.useCount++
        }
        return loaded
    }

    /** Release skills no longer needed (Spec §26 resource policy). */
    fun release(ids: List<String>, runId: String) {
        for (id in ids) {
            active.remove(id)?.let {
                EventBus.emit(runId, EventType.SKILL_RELEASED, it.skill.displayName,
                    EventStatus.INFO, phase = "skills",
                    detail = "used ${it.useCount + 1}x, ${System.currentTimeMillis() - it.activatedAt} ms",
                    visibility = EventVisibility.DIAGNOSTIC)
            }
        }
    }

    fun releaseAll(runId: String) = release(active.keys().toList(), runId)

    fun activeSkills(): List<ActiveSkill> = active.values.toList()

    /** Tools granted by currently active skills (capability layer, Spec §7). */
    fun grantedTools(): Set<String> =
        active.values.flatMap { it.skill.declaredTools }.toSet()

    fun isActive(id: String): Boolean = active.containsKey(id)
}
