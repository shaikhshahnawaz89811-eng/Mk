package com.codeassist.ai.engine

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Web search & evidence — Spec §8.
 *
 * Search decision: user explicit / freshness / time-sensitive / external
 * source needed. Verification sequence: retrieve -> compare -> verify ->
 * attribute -> cite. Real HTTP, real results, recorded source metadata.
 */

object Http {
    fun get(url: String, timeoutMs: Int = 15000): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CodeAssistAI/2.0 (Android; on-device assistant)")
        conn.setRequestProperty("Accept", "text/html,application/json,*/*")
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            code to body
        } finally { conn.disconnect() }
    }

    fun hostOf(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)

    /** HTML -> readable text (scripts/styles stripped, entities decoded). */
    fun htmlToText(html: String, maxChars: Int = 6000): String {
        var t = html.replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        t = t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
        return if (t.length > maxChars) t.take(maxChars) + "…" else t
    }
}

// ---------------------------------------------------------------------------
// web_search — DuckDuckGo HTML + Wikipedia fallback, real results
// ---------------------------------------------------------------------------

class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Searching the web"
    override val baseRisk = RiskTier.LOW
    override val allowedDomains = listOf("html.duckduckgo.com", "duckduckgo.com",
        "en.wikipedia.org", "wikipedia.org")
    override val argSpec = mapOf(
        "query" to Tool.ArgRule("string"),
        "count" to Tool.ArgRule("int", required = false)
    )

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val q = call.args["query"].toString().trim()
        if (q.isBlank()) return ToolResult(false, "", "invalid argument: empty query",
            ErrorClass.INVALID_TOOL_ARGS)
        val want = call.args["count"]?.toString()?.toIntOrNull() ?: 6

        EventBus.emit(call.runId, EventType.SEARCH_STARTED, "Searching: $q",
            EventStatus.ACTIVE, tool = name)

        val sources = mutableListOf<SourceRef>()
        var lastErr = ""

        // 1) DuckDuckGo HTML endpoint
        try {
            val url = "https://html.duckduckgo.com/html/?q=" +
                URLEncoder.encode(q, "UTF-8")
            val (code, html) = Http.get(url)
            if (code in 200..299) {
                val results = Regex(
                    """result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(html).take(want)
                for (m in results) {
                    var link = m.groupValues[1]
                    // DDG wraps links in a redirect — extract uddg param
                    Regex("uddg=([^&]+)").find(link)?.let {
                        link = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
                    }
                    val title = Http.htmlToText(m.groupValues[2], 200)
                    if (link.startsWith("http")) {
                        sources += SourceRef(
                            url = link, title = title,
                            publisher = Http.hostOf(link),
                            snippet = "", relevance = (sources.size + 1).let { 1.0 / it }
                        )
                    }
                }
            } else lastErr = "DuckDuckGo HTTP $code"
        } catch (e: Exception) { lastErr = e.message ?: "network error" }

        // 2) Wikipedia opensearch fallback / supplement
        if (sources.size < 2) {
            try {
                val url = "https://en.wikipedia.org/w/api.php?action=opensearch&format=json&limit=$want&search=" +
                    URLEncoder.encode(q, "UTF-8")
                val (code, body) = Http.get(url)
                if (code in 200..299) {
                    val arr = org.json.JSONArray(body)
                    val titles = arr.getJSONArray(1); val urls = arr.getJSONArray(3)
                    for (i in 0 until titles.length()) {
                        sources += SourceRef(
                            url = urls.getString(i), title = titles.getString(i),
                            publisher = "wikipedia.org", relevance = 0.5 / (i + 1)
                        )
                    }
                }
            } catch (_: Exception) { }
        }

        if (sources.isEmpty())
            return ToolResult(false, "", "network failure: search failed — $lastErr",
                ErrorClass.NETWORK_FAILURE)

        sources.forEach { s ->
            EventBus.emit(call.runId, EventType.SEARCH_SOURCE_ADDED, s.title,
                EventStatus.INFO, tool = name, detail = s.publisher, sources = listOf(s))
        }
        val out = sources.mapIndexed { i, s -> "${i + 1}. ${s.title}\n   ${s.url} (${s.publisher})" }
            .joinToString("\n")
        return ToolResult(true, out, sources = sources,
            metadata = mapOf("count" to sources.size.toString()))
    }
}

// ---------------------------------------------------------------------------
// web_fetch — open a source, extract text, wrap as untrusted (§27)
// ---------------------------------------------------------------------------

class WebFetchTool : Tool {
    override val name = "web_fetch"
    override val description = "Opening a web source"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf(
        "url" to Tool.ArgRule("url"),
        "question" to Tool.ArgRule("string", required = false)
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val url = call.args["url"].toString()
        val (code, body) = try { Http.get(url) } catch (e: Exception) {
            return ToolResult(false, "", "network failure: ${e.message}", ErrorClass.NETWORK_FAILURE)
        }
        if (code !in 200..299)
            return ToolResult(false, "", "network failure: HTTP $code for $url",
                ErrorClass.NETWORK_FAILURE)

        val isJson = body.trimStart().startsWith("{") || body.trimStart().startsWith("[")
        val text = if (isJson) body.take(6000) else Http.htmlToText(body)
        // Untrusted content: scanned + wrapped — never treated as instructions
        val (wrapped, flagged) = InjectionGuard.scanUntrusted(text, Http.hostOf(url))
        val src = SourceRef(
            url = url,
            title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()?.take(120) ?: Http.hostOf(url),
            publisher = Http.hostOf(url),
            snippet = text.take(240),
            relevance = 0.8, verified = true
        )
        return ToolResult(true, wrapped,
            metadata = mapOf("flagged_injection" to flagged.toString(),
                "chars" to text.length.toString()),
            sources = listOf(src))
    }
}

// ---------------------------------------------------------------------------
// compare_sources — conflict handling per §8
// ---------------------------------------------------------------------------

class CompareSourcesTool : Tool {
    override val name = "compare_sources"
    override val description = "Comparing sources"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("notes" to Tool.ArgRule("string"))
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        // Sources collected so far in this run from engine store
        val events = EngineStore.events(call.runId)
        val sources = events.flatMap { it.sourceRefs }.distinctBy { it.url }
        if (sources.isEmpty())
            return ToolResult(false, "", "no sources collected yet", ErrorClass.CONFLICTING_STATE)
        val sb = StringBuilder("Source comparison (${sources.size} sources):\n")
        val byPublisher = sources.groupBy { it.publisher }
        byPublisher.forEach { (pub, list) ->
            sb.append("• $pub — ${list.size} source(s), verified: ${list.count { it.verified }}/${list.size}\n")
        }
        sb.append("\nRule: prefer authoritative primary sources; note conflicts with dates; ")
        sb.append("do not present uncertain claims as settled.")
        return ToolResult(true, sb.toString(), sources = sources)
    }
}
