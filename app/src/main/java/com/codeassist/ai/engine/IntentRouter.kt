package com.codeassist.ai.engine

/**
 * Meaning-first intent routing — Spec §2 (meaning over keywords),
 * §5 (decision inputs, example pipelines), §10 (output types).
 *
 * Intent recognition normalizes typos, spacing, reordered phrases and
 * missing words (Hinglish included). It combines semantic signals with
 * entities, project state, required capabilities and confidence.
 */

enum class Intent {
    SIMPLE_CHAT,          // explain, difference, what is…
    WEB_RESEARCH,         // latest docs / verify / current info
    CODING_TASK,          // fix, implement, refactor, build feature
    BUILD_APP,            // make website / android app / game…
    DOCUMENT_TASK,        // pdf/table/spreadsheet/doc
    PROJECT_ACTION,       // open/create/inspect project, package artifact
    MEDIA_TASK,           // animation/video/film
    DATA_TASK,            // analyze data, charts
    MODEL_MANAGEMENT,     // load/unload/delete/import model
    CREDENTIAL_SETUP,     // connect provider/api key
    UNKNOWN
}

enum class OutputType {
    NONE, WEBSITE, ANDROID_APP, IOS_APP, GAME, FILM_VIDEO, ANIMATION,
    DOCUMENT_PDF, SPREADSHEET, RESEARCH, BACKEND
}

data class RoutedIntent(
    val intent: Intent,
    val outputType: OutputType,
    val confidence: Double,
    val entities: Map<String, String>,     // e.g. "file"->..., "topic"->...
    val needsWeb: Boolean,
    val freshnessRequired: Boolean,
    val isMultiStep: Boolean,
    val suggestedSkills: List<String>,
    val explanation: String
)

object IntentRouter {

    // typo/spacing tolerant normalization (Hinglish friendly)
    fun normalize(raw: String): String {
        var t = raw.lowercase().trim()
        t = t.replace(Regex("[^\\p{L}\\p{N}+/._ -]"), " ")
        t = t.replace(Regex("\\s+"), " ")
        // common typo/variant fixes
        val fixes = mapOf(
            "banao" to "banao", "bana do" to "banao", "bnana" to "banao", "bano" to "banao",
            "karo" to "karo", "krna" to "karna", "karna" to "karna",
            "dekh" to "dekho", "dekh ke" to "dekhkar", "dekhkar" to "dekhkar",
            "nikaal" to "nikalo", "nikal" to "nikalo",
            "sahi" to "sahi", "thik" to "thik", "theek" to "thik",
            "latset" to "latest", "lates" to "latest", "newest" to "latest",
            "webiste" to "website", "websit" to "website", "site" to "website",
            "andriod" to "android", "andorid" to "android", "apk" to "apk",
            "speadsheet" to "spreadsheet", "excel" to "spreadsheet", "xlsx" to "spreadsheet",
            "documnet" to "document", "dcos" to "docs", "dokumen" to "document",
            "reserch" to "research", "serach" to "search", "saerch" to "search",
            "animtion" to "animation", "vedio" to "video", "vido" to "video",
            "fixs" to "fix", "fik" to "fix", "thik karo" to "fix karo",
            "tabe" to "table", "tabel" to "table", "teble" to "table"
        )
        for ((k, v) in fixes) t = t.replace(Regex("\\b${Regex.escape(k)}\\b"), v)
        return t
    }

    private fun score(t: String, vararg phrases: String): Int {
        var s = 0
        for (p in phrases) if (p in t) s += p.split(" ").size  // longer matches weigh more
        return s
    }

    fun route(raw: String, hasProject: Boolean, hasAttachments: Boolean): RoutedIntent {
        val t = normalize(raw)
        val entities = mutableMapOf<String, String>()

        // freshness detection (Spec §8 search decision)
        val freshness = listOf("latest", "current", "aaj", "abhi", "recent", "today",
            "2026", "naya", "new docs", "official docs").any { it in t }
        val explicitWeb = listOf("search", "web", "internet", "google", "online",
            "docs dekh", "official docs").any { it in t }

        val scores = linkedMapOf(
            Intent.BUILD_APP to score(t, "website banao", "app banao", "android app", "game banao",
                "ios app", "landing page", "web app", "banao website", "create website",
                "build app", "make app", "bana ke do", "apk banao"),
            Intent.CODING_TASK to score(t, "fix karo", "fix", "bug", "error", "refactor",
                "implement", "code", "responsive", "header", "crash", "issue", "debug",
                "function", "class", "patch", "review code", "test likho", "tests",
                "python", "javascript", "kotlin", "java", "script", "program", "snippet", "sql", "c++"),
            Intent.DOCUMENT_TASK to score(t, "pdf", "table nikalo", "spreadsheet", "csv",
                "report banao", "document", "docx", "resume", "invoice", "extract table"),
            Intent.WEB_RESEARCH to score(t, "research", "compare", "sources", "cite",
                "verify", "fact check", "explain with sources"),
            Intent.MEDIA_TASK to score(t, "animation", "video", "film", "motion",
                "scene", "render", "animate"),
            Intent.DATA_TASK to score(t, "analyze data", "data analysis", "chart",
                "graph", "statistics", "dataset"),
            Intent.MODEL_MANAGEMENT to score(t, "model load", "model unload", "model delete",
                "model import", "gemma load", "gemma unload", "gemma delete", "gemma import"),
            Intent.CREDENTIAL_SETUP to score(t, "api key", "connect google", "oauth",
                "credential", "token add", "add key"),
            Intent.PROJECT_ACTION to score(t, "project kholo", "open project", "new project",
                "project banao", "export", "zip", "package", "deliver")
        )

        val top = scores.maxByOrNull { it.value }!!
        var bestValue = top.value
        var intent = top.key
        if (bestValue == 0) {
            bestValue = score(t, "what", "kaise", "kya", "difference",
                "explain", "batao", "meaning", "how")
            intent = Intent.SIMPLE_CHAT
        }
        if (bestValue == 0) intent = Intent.UNKNOWN
        if (intent == Intent.CODING_TASK && freshness) intent = Intent.WEB_RESEARCH.takeIf {
            // "latest docs dekhkar bug fix karo" is a multi-step job (Spec §5)
            false
        } ?: intent

        val outputType = when {
            score(t, "android", "apk", "kotlin app") > 0 -> OutputType.ANDROID_APP
            score(t, "ios", "iphone", "swift") > 0 -> OutputType.IOS_APP
            score(t, "game") > 0 -> OutputType.GAME
            score(t, "website", "landing page", "web app", "html") > 0 -> OutputType.WEBSITE
            score(t, "animation") > 0 -> OutputType.ANIMATION
            score(t, "video", "film") > 0 -> OutputType.FILM_VIDEO
            score(t, "spreadsheet", "csv", "table nikalo") > 0 -> OutputType.SPREADSHEET
            score(t, "pdf", "document", "docx", "report") > 0 -> OutputType.DOCUMENT_PDF
            score(t, "research") > 0 -> OutputType.RESEARCH
            score(t, "backend", "database", "api server") > 0 -> OutputType.BACKEND
            else -> OutputType.NONE
        }

        // entity extraction: filenames, quoted topics
        Regex("([\\w.-]+\\.(?:kt|java|xml|json|html|css|js|ts|tsx|jsx|pdf|csv|xlsx|zip|gradle|md))")
            .find(raw)?.let { entities["file"] = it.value }
        Regex("\"([^\"]{3,60})\"").find(raw)?.let { entities["topic"] = it.groupValues[1] }

        val needsWeb = explicitWeb || freshness
        val multiStep = needsWeb && (intent == Intent.CODING_TASK) ||
            intent == Intent.BUILD_APP || intent == Intent.DOCUMENT_TASK ||
            intent == Intent.MEDIA_TASK || "aur" in t && "phir" in t

        val skills = mutableListOf<String>()
        when (intent) {
            Intent.CODING_TASK -> skills += listOf("coding", "testing")
            Intent.BUILD_APP -> when (outputType) {
                OutputType.ANDROID_APP -> skills += listOf("android", "coding", "testing", "export")
                OutputType.WEBSITE -> skills += listOf("website", "ui", "coding", "testing")
                OutputType.GAME -> skills += listOf("game", "coding", "assets")
                OutputType.IOS_APP -> skills += listOf("ios", "coding", "testing")
                OutputType.BACKEND -> skills += listOf("coding", "database", "testing")
                else -> skills += listOf("coding")
            }
            Intent.DOCUMENT_TASK -> when (outputType) {
                OutputType.SPREADSHEET -> skills += listOf("pdf_document", "spreadsheet", "data")
                else -> skills += listOf("pdf_document", "research")
            }
            Intent.WEB_RESEARCH -> skills += listOf("research", "websearch")
            Intent.MEDIA_TASK -> skills += listOf("animation", "assets")
            Intent.DATA_TASK -> skills += listOf("data", "spreadsheet")
            else -> { }
        }
        if (needsWeb && "websearch" !in skills) skills += "websearch"
        if (hasProject && "files" !in skills && intent != Intent.SIMPLE_CHAT) skills += "files"

        val confidence = when {
            bestValue >= 4 -> 0.9
            bestValue >= 2 -> 0.75
            bestValue == 1 -> 0.55
            else -> 0.3
        }

        return RoutedIntent(
            intent = intent,
            outputType = outputType,
            confidence = confidence,
            entities = entities,
            needsWeb = needsWeb,
            freshnessRequired = freshness,
            isMultiStep = multiStep,
            suggestedSkills = skills,
            explanation = "intent=$intent conf=$confidence skills=$skills"
        )
    }
}
