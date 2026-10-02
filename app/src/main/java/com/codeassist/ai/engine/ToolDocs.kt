package com.codeassist.ai.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Document tools — Spec §7 document category, §10 document/spreadsheet
 * outputs. Real local implementations: PDF text extraction, minimal PDF
 * writer, real .docx and .xlsx (OOXML zip packages), CSV read/write.
 */

// ---------------------------------------------------------------------------
// PDF text extraction (FlateDecode streams + Tj/TJ operators)
// ---------------------------------------------------------------------------

data class PdfStructureEvidence(
    val text: String,
    val headings: List<String>,
    val tableLikeLines: List<String>,
    val codeLikeLines: List<String>,
    val hasRenderablePages: Boolean = true
)

class PdfExtractTool : Tool {
    override val name = "pdf_extract"
    override val description = "Extracting PDF text"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf("path" to Tool.ArgRule("string"))

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = false)
        if (!f.exists()) return ToolResult(false, "", "Missing file: no such file ${f.name}",
            ErrorClass.MISSING_FILE)
        val bytes = f.readBytes()
        if (bytes.size < 5 || !bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()))
            return ToolResult(false, "", "invalid argument: not a PDF file", ErrorClass.INVALID_TOOL_ARGS)
        val text = extractText(bytes)
        return if (text.isBlank())
            ToolResult(true, "(no extractable text — likely a scanned/image PDF)",
                metadata = mapOf("chars" to "0"))
        else ToolResult(true, text, metadata = mapOf("chars" to text.length.toString()))
    }

    companion object {
        /** Best-effort document structure view. Page images remain the authoritative
         * visual path for scanned pages, diagrams, screenshots and complex tables. */
        fun extractStructured(bytes: ByteArray): PdfStructureEvidence {
            val text = extractText(bytes)
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val headings = lines.filter { line ->
                line.length in 2..140 && (line.startsWith("#") ||
                    (line == line.uppercase() && line.any { it.isLetter() } && line.split(" ").size <= 14))
            }.distinct().take(80)
            val tableLike = lines.filter { it.contains("|") || Regex("\\s{3,}").containsMatchIn(it) }
                .distinct().take(80)
            val codeLike = lines.filter { line ->
                line.startsWith("fun ") || line.startsWith("class ") || line.startsWith("def ") ||
                    line.startsWith("import ") || line.startsWith("package ") || line.startsWith("#include") ||
                    line.contains("{ ") || line.contains("=>") || line.contains("::")
            }.distinct().take(120)
            return PdfStructureEvidence(text, headings, tableLike, codeLike)
        }

        fun extractText(bytes: ByteArray): String {
            val raw = String(bytes, Charsets.ISO_8859_1)
            val out = StringBuilder()
            val streamRe = Regex("stream\\r?\\n")
            var idx = 0
            for (m in streamRe.findAll(raw)) {
                val start = m.range.last + 1
                val end = raw.indexOf("endstream", start)
                if (end <= start) continue
                idx++
                val dictStart = raw.substring(maxOf(0, m.range.first - 400), m.range.first)
                val data = bytes.copyOfRange(start, end).let {
                    var d = it
                    while (d.isNotEmpty() && (d.last() == '\n'.code.toByte() || d.last() == '\r'.code.toByte()))
                        d = d.copyOfRange(0, d.size - 1)
                    d
                }
                val decoded: ByteArray = if ("FlateDecode" in dictStart) {
                    try {
                        val inf = Inflater()
                        inf.setInput(data)
                        val bos = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (!inf.finished()) {
                            val n = inf.inflate(buf)
                            if (n <= 0) break
                            bos.write(buf, 0, n)
                        }
                        inf.end(); bos.toByteArray()
                    } catch (_: Exception) { continue }
                } else data
                val content = String(decoded, Charsets.ISO_8859_1)
                // (text) Tj  and  [(a) 12 (b)] TJ
                for (tj in Regex("\\((?:\\\\.|[^\\\\)])*\\)\\s*Tj").findAll(content)) {
                    out.append(unescape(tj.value.removeSuffix("Tj").trim()
                        .removePrefix("(").removeSuffix(")"))).append(' ')
                }
                for (arr in Regex("\\[(?:[^\\[\\]])*\\]\\s*TJ").findAll(content)) {
                    for (part in Regex("\\((?:\\\\.|[^\\\\)])*\\)").findAll(arr.value)) {
                        out.append(unescape(part.value.removePrefix("(").removeSuffix(")")))
                    }
                    out.append(' ')
                }
                out.append('\n')
            }
            return out.toString().replace(Regex("[ \\t]+"), " ").trim()
        }

        private fun unescape(s: String): String =
            s.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
    }
}

// ---------------------------------------------------------------------------
// Minimal real PDF writer (text pages, Helvetica)
// ---------------------------------------------------------------------------

class PdfCreateTool : Tool {
    override val name = "pdf_create"
    override val description = "Creating PDF"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "title" to Tool.ArgRule("string"),
        "content" to Tool.ArgRule("string")
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = true)
        f.parentFile?.mkdirs()
        val title = call.args["title"].toString()
        val content = call.args["content"].toString()
        writePdf(f, title, content)
        EventBus.emit(call.runId, EventType.FILE_CHANGED, f.name, EventStatus.DONE,
            detail = "PDF • ${f.length() / 1024} KB", files = listOf(Sandbox.displayPath(f)))
        return ToolResult(true, "Created ${f.name} (${f.length() / 1024} KB)",
            filesChanged = listOf(f.absolutePath))
    }

    companion object {
        fun writePdf(out: File, title: String, body: String) {
            val lines = mutableListOf<String>()
            var cur = StringBuilder()
            for (w in body.split(Regex("\\s+"))) {
                if (cur.length + w.length > 82) { lines.add(cur.toString()); cur = StringBuilder() }
                cur.append(w).append(' ')
            }
            if (cur.isNotEmpty()) lines.add(cur.toString())
            val pages = lines.chunked(44).ifEmpty { listOf(listOf("(empty)")) }

            val objects = mutableListOf<ByteArray>()
            fun esc(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

            objects.add("<< /Type /Catalog /Pages 2 0 R >>".toByteArray())
            objects.add("<< /Type /Pages /Kids [${pages.indices.joinToString(" ") { "${3 + it * 2} 0 R" }}] /Count ${pages.size} >>".toByteArray())
            pages.forEachIndexed { i, pageLines ->
                val contentId = 4 + i * 2
                objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> /F2 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >> >> >> " +
                    "/Contents $contentId 0 R >>").toByteArray())
                val sb = StringBuilder("BT\n")
                if (i == 0) sb.append("/F2 16 Tf 56 756 Td (").append(esc(title)).append(") Tj\n")
                sb.append("/F1 10 Tf 56 ${if (i == 0) 726 else 756} Td 14 TL\n")
                for (ln in pageLines) sb.append("(").append(esc(ln)).append(") Tj T*\n")
                sb.append("ET")
                val data = sb.toString().toByteArray()
                objects.add(("<< /Length ${data.size} >>\nstream\n".toByteArray() + data + "\nendstream".toByteArray()))
            }

            val bos = ByteArrayOutputStream()
            bos.write("%PDF-1.4\n".toByteArray())
            val offsets = IntArray(objects.size + 1)
            objects.forEachIndexed { i, obj ->
                offsets[i + 1] = bos.size()
                bos.write("${i + 1} 0 obj\n".toByteArray())
                bos.write(obj)
                bos.write("\nendobj\n".toByteArray())
            }
            val xrefPos = bos.size()
            bos.write("xref\n0 ${objects.size + 1}\n".toByteArray())
            bos.write("0000000000 65535 f \n".toByteArray())
            for (i in 1..objects.size) bos.write("%010d 00000 n \n".format(offsets[i]).toByteArray())
            bos.write(("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n" +
                "startxref\n$xrefPos\n%%EOF").toByteArray())
            out.writeBytes(bos.toByteArray())
        }
    }
}

// ---------------------------------------------------------------------------
// Real .docx (OOXML) writer
// ---------------------------------------------------------------------------

class DocxCreateTool : Tool {
    override val name = "docx_create"
    override val description = "Creating DOCX document"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "title" to Tool.ArgRule("string"),
        "content" to Tool.ArgRule("string")
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = true)
        f.parentFile?.mkdirs()
        writeDocx(f, call.args["title"].toString(), call.args["content"].toString())
        EventBus.emit(call.runId, EventType.FILE_CHANGED, f.name, EventStatus.DONE,
            detail = "DOCX • ${f.length() / 1024} KB", files = listOf(Sandbox.displayPath(f)))
        return ToolResult(true, "Created ${f.name}", filesChanged = listOf(f.absolutePath))
    }

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun writeDocx(out: File, title: String, body: String) {
            val paras = body.split("\n").filter { it.isNotBlank() }
            val docXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
                append("""<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="40"/></w:rPr><w:t>${esc(title)}</w:t></w:r></w:p>""")
                for (p in paras) {
                    val heading = p.startsWith("#")
                    val txt = p.removePrefix("#").trim()
                    if (heading) append("""<w:p><w:r><w:rPr><w:b/><w:sz w:val="28"/></w:rPr><w:t>${esc(txt)}</w:t></w:r></w:p>""")
                    else append("""<w:p><w:r><w:t xml:space="preserve">${esc(txt)}</w:t></w:r></w:p>""")
                }
                append("""</w:body></w:document>""")
            }
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                fun put(name: String, content: String) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(content.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
                put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
                put("word/document.xml", docXml)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Real .xlsx (OOXML spreadsheet) writer with formulas
// ---------------------------------------------------------------------------

class XlsxCreateTool : Tool {
    override val name = "xlsx_create"
    override val description = "Creating spreadsheet"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "sheet" to Tool.ArgRule("string", required = false),
        "csv" to Tool.ArgRule("string")   // CSV content; cells starting with '=' become formulas
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = true)
        f.parentFile?.mkdirs()
        val sheetName = call.args["sheet"]?.toString() ?: "Sheet1"
        val rows = CsvTool.parse(call.args["csv"].toString())
        writeXlsx(f, sheetName, rows)
        EventBus.emit(call.runId, EventType.FILE_CHANGED, f.name, EventStatus.DONE,
            detail = "XLSX • ${rows.size} rows", files = listOf(Sandbox.displayPath(f)))
        return ToolResult(true, "Created ${f.name} (${rows.size} rows)",
            filesChanged = listOf(f.absolutePath))
    }

    companion object {
        fun colName(i: Int): String {
            var n = i + 1; var s = ""
            while (n > 0) { val r = (n - 1) % 26; s = ('A' + r) + s; n = (n - 1) / 26 }
            return s
        }

        fun writeXlsx(out: File, sheetName: String, rows: List<List<String>>) {
            val sheet = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                rows.forEachIndexed { r, row ->
                    append("""<row r="${r + 1}">""")
                    row.forEachIndexed { c, cell ->
                        val ref = "${colName(c)}${r + 1}"
                        when {
                            cell.startsWith("=") ->
                                append("""<c r="$ref"><f>${DocxCreateTool.esc(cell.removePrefix("="))}</f></c>""")
                            cell.toDoubleOrNull() != null ->
                                append("""<c r="$ref"><v>$cell</v></c>""")
                            else ->
                                append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${DocxCreateTool.esc(cell)}</t></is></c>""")
                        }
                    }
                    append("</row>")
                }
                append("""</sheetData></worksheet>""")
            }
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                fun put(name: String, content: String) {
                    z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry()
                }
                put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
                put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${DocxCreateTool.esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
                put("xl/worksheets/sheet1.xml", sheet)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CSV read/write + summary
// ---------------------------------------------------------------------------

class CsvTool : Tool {
    override val name = "csv_tool"
    override val description = "Processing CSV data"
    override val baseRisk = RiskTier.LOW
    override val argSpec = mapOf(
        "path" to Tool.ArgRule("string"),
        "mode" to Tool.ArgRule("enum", required = false, allowed = listOf("read", "summary")),
    )
    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val f = Sandbox.authorize(call.args["path"].toString(), write = false)
        if (!f.exists()) return ToolResult(false, "", "Missing file: no such file ${f.name}",
            ErrorClass.MISSING_FILE)
        val rows = parse(f.readText())
        if (rows.isEmpty()) return ToolResult(true, "(empty CSV)")
        return when (call.args["mode"]?.toString() ?: "read") {
            "summary" -> {
                val header = rows.first()
                val sb = StringBuilder("${rows.size - 1} data rows, ${header.size} columns\n")
                header.forEachIndexed { i, h ->
                    val vals = rows.drop(1).mapNotNull { it.getOrNull(i) }
                    val nums = vals.mapNotNull { it.toDoubleOrNull() }
                    if (nums.size == vals.size && nums.isNotEmpty())
                        sb.append("• $h: numeric — min ${nums.min()}, max ${nums.max()}, avg %.2f\n"
                            .format(nums.average()))
                    else sb.append("• $h: text — ${vals.distinct().size} distinct values\n")
                }
                ToolResult(true, sb.toString())
            }
            else -> ToolResult(true, rows.joinToString("\n") { it.joinToString(", ") })
        }
    }

    companion object {
        fun parse(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var cur = StringBuilder(); var row = mutableListOf<String>(); var quoted = false
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                when {
                    quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i++ }
                    ch == '"' -> quoted = !quoted
                    ch == ',' && !quoted -> { row.add(cur.toString()); cur = StringBuilder() }
                    (ch == '\n' || ch == '\r') && !quoted -> {
                        if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(cur.toString()); cur = StringBuilder()
                        if (row.any { it.isNotBlank() }) rows.add(row)
                        row = mutableListOf()
                    }
                    else -> cur.append(ch)
                }
                i++
            }
            row.add(cur.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
            return rows
        }
    }
}
