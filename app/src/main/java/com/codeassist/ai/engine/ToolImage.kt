package com.codeassist.ai.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * Image tools — Spec §7 tool categories ("images").
 *
 * Two parts:
 *  - ImageHeaders: pure-JVM format/dimension parser (PNG, JPEG, GIF, BMP,
 *    WEBP) — unit tested, no Android dependency, reads only a few KB.
 *  - ImageTool: sandboxed image operations (info / resize / compress /
 *    convert). Pixel work uses BitmapFactory on-device; header parsing is
 *    always available, even in JVM tests.
 */

object ImageHeaders {

    data class Info(val format: String, val width: Int, val height: Int)

    /** Sniff format + dimensions from the file header. Null when unknown. */
    fun sniff(f: File): Info? {
        if (!f.exists() || !f.isFile) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val head = ByteArray(64)
                val n = raf.read(head)
                if (n < 12) return null
                when {
                    // PNG: 8-byte signature, IHDR width/height big-endian at 16/20
                    head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                        head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() ->
                        Info("png", be32(head, 16), be32(head, 20))

                    // GIF: "GIF87a"/"GIF89a", width/height little-endian at 6/8
                    head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() ->
                        Info("gif", le16(head, 6), le16(head, 8))

                    // BMP: "BM", width/height little-endian int at 18/22
                    head[0] == 0x42.toByte() && head[1] == 0x4D.toByte() ->
                        Info("bmp", le32(head, 18), le32(head, 22))

                    // WEBP: "RIFF"...."WEBP", VP8/VP8L/VP8X variants
                    head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
                        head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
                        head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
                        head[10] == 0x42.toByte() && head[11] == 0x50.toByte() ->
                        webpDims(head, n)

                    // JPEG: walk SOF markers
                    head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() ->
                        jpegDims(raf)

                    else -> null
                }
            }
        } catch (_: Exception) { null }
    }

    private fun be32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF shl 24) or (b[off + 1].toInt() and 0xFF shl 16) or
            (b[off + 2].toInt() and 0xFF shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() and 0xFF shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() and 0xFF shl 8) or
            (b[off + 2].toInt() and 0xFF shl 16) or (b[off + 3].toInt() and 0xFF shl 24)

    private fun webpDims(head: ByteArray, n: Int): Info? {
        // Chunk fourcc at offset 12
        val fourcc = String(head, 12, 4, Charsets.US_ASCII)
        return when (fourcc) {
            "VP8 " -> if (n >= 30) Info("webp",
                le16(head, 26) and 0x3FFF, le16(head, 28) and 0x3FFF) else null
            "VP8L" -> if (n >= 25) {
                val b0 = head[21].toInt() and 0xFF; val b1 = head[22].toInt() and 0xFF
                val b2 = head[23].toInt() and 0xFF; val b3 = head[24].toInt() and 0xFF
                val w = ((b1 and 0x3F) shl 8 or b0) + 1
                val h = ((b3 and 0x0F) shl 10 or (b2 shl 2) or (b1 and 0xC0 shr 6)) + 1
                Info("webp", w, h)
            } else null
            "VP8X" -> if (n >= 30) {
                val w = (head[24].toInt() and 0xFF) or (head[25].toInt() and 0xFF shl 8) or
                    (head[26].toInt() and 0xFF shl 16)
                val h = (head[27].toInt() and 0xFF) or (head[28].toInt() and 0xFF shl 8) or
                    (head[29].toInt() and 0xFF shl 16)
                Info("webp", w + 1, h + 1)
            } else null
            else -> Info("webp", 0, 0)
        }
    }

    private fun jpegDims(raf: RandomAccessFile): Info? {
        raf.seek(2)
        var guard = 0
        while (guard++ < 64) {
            val markerPrefix = raf.read()
            if (markerPrefix != 0xFF) return null
            var marker = raf.read()
            while (marker == 0xFF) marker = raf.read()   // fill bytes
            // SOF0..SOF15 except DHT(0xC4), JPG(0xC8), DAC(0xCC)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                raf.skipBytes(3)                          // length(2) + precision(1)
                val h = raf.readShort().toInt() and 0xFFFF
                val w = raf.readShort().toInt() and 0xFFFF
                return Info("jpeg", w, h)
            }
            val len = raf.readShort().toInt() and 0xFFFF
            if (len < 2) return null
            raf.skipBytes(len - 2)
        }
        return null
    }
}

/**
 * image_tool — op-driven image operations, all sandboxed (Spec §27).
 *
 * ops:
 *  - info:     format + dimensions + size (pure header parse)
 *  - resize:   scale to width/height (keeps aspect when only one given)
 *  - compress: re-encode with quality (jpeg/webp)
 *  - convert:  change format (png|jpeg|webp)
 */
class ImageTool : Tool {
    override val name = "image_tool"
    override val description = "Processing image"
    override val baseRisk = RiskTier.MEDIUM
    override val argSpec = mapOf(
        "op" to Tool.ArgRule("enum", allowed = listOf("info", "resize", "compress", "convert")),
        "path" to Tool.ArgRule("string"),
        "output" to Tool.ArgRule("string", required = false),
        "width" to Tool.ArgRule("int", required = false),
        "height" to Tool.ArgRule("int", required = false),
        "quality" to Tool.ArgRule("int", required = false),
        "format" to Tool.ArgRule("enum", required = false, allowed = listOf("png", "jpeg", "webp"))
    )

    override fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val op = call.args["op"].toString()
        val f = Sandbox.authorize(call.args["path"].toString(), write = false)
        if (!f.exists()) return ToolResult(false, "", "Missing file: no such image ${f.name}",
            ErrorClass.MISSING_FILE)

        if (op == "info") {
            val info = ImageHeaders.sniff(f)
                ?: return ToolResult(false, "", "Unsupported or corrupt image — ${f.name}",
                    ErrorClass.INVALID_TOOL_ARGS)
            val dim = if (info.width > 0) " • ${info.width}×${info.height}" else ""
            return ToolResult(true, "${f.name}: ${info.format}$dim • ${f.length() / 1024} KB",
                metadata = mapOf(
                    "format" to info.format,
                    "width" to info.width.toString(),
                    "height" to info.height.toString(),
                    "bytes" to f.length().toString()))
        }

        // Pixel ops need Android BitmapFactory (device-only); header parsing above
        // keeps the tool partially functional in JVM tests.
        val outPath = call.args["output"]?.toString()
            ?: f.nameWithoutExtension + "-edited." +
                (call.args["format"]?.toString() ?: f.extension.ifBlank { "png" })
        val out = Sandbox.authorize(
            if (File(outPath).isAbsolute) outPath
            else File(f.parentFile, outPath).absolutePath, write = true)

        return try {
            val cls = Class.forName("android.graphics.BitmapFactory")
            val decode = cls.getMethod("decodeFile", String::class.java)
            val src = decode.invoke(null, f.absolutePath)
                ?: return ToolResult(false, "", "Could not decode ${f.name} — unsupported or corrupt",
                    ErrorClass.INVALID_TOOL_ARGS)

            val info = ImageHeaders.sniff(f)
            val reqW = call.args["width"]?.toString()?.toIntOrNull() ?: 0
            val reqH = call.args["height"]?.toString()?.toIntOrNull() ?: 0
            var bmp = src
            var newW = info?.width ?: 0
            var newH = info?.height ?: 0
            if (op == "resize" && (reqW > 0 || reqH > 0)) {
                val (tw, th) = when {
                    reqW > 0 && reqH > 0 -> reqW to reqH
                    reqW > 0 && info != null && info.width > 0 ->
                        reqW to (info.height.toLong() * reqW / info.width).toInt().coerceAtLeast(1)
                    reqH > 0 && info != null && info.height > 0 ->
                        (info.width.toLong() * reqH / info.height).toInt().coerceAtLeast(1) to reqH
                    else -> return ToolResult(false, "", "resize needs width and/or height",
                        ErrorClass.INVALID_TOOL_ARGS)
                }
                val scaled = Class.forName("android.graphics.Bitmap")
                    .getMethod("createScaledBitmap",
                        Class.forName("android.graphics.Bitmap"),
                        Int::class.java, Int::class.java, Boolean::class.java)
                    .invoke(null, src, tw, th, true)
                bmp = scaled
                newW = tw; newH = th
            }

            val formatName = call.args["format"]?.toString()
                ?: when (f.extension.lowercase()) {
                    "jpg", "jpeg" -> "jpeg"
                    "webp" -> "webp"
                    else -> "png"
                }
            val quality = (call.args["quality"]?.toString()?.toIntOrNull() ?: 90).coerceIn(1, 100)
            val compressFormat = Class.forName("android.graphics.Bitmap\$CompressFormat")
                .getField(if (formatName == "jpeg") "JPEG" else formatName.uppercase()).get(null)
            out.parentFile?.mkdirs()
            out.outputStream().use { os ->
                bmp.javaClass.getMethod("compress", compressFormat.javaClass,
                    Int::class.java, java.io.OutputStream::class.java)
                    .invoke(bmp, compressFormat, quality, os)
            }

            EventBus.emit(call.runId, EventType.FILE_CHANGED, out.name, EventStatus.DONE,
                detail = "$op • ${newW}×${newH} • ${out.length() / 1024} KB",
                files = listOf(Sandbox.displayPath(out)))
            ToolResult(true,
                "${f.name} → ${out.name} ($op • ${newW}×${newH} • ${out.length() / 1024} KB)",
                filesChanged = listOf(out.absolutePath),
                metadata = mapOf("op" to op, "format" to formatName))
        } catch (e: ClassNotFoundException) {
            ToolResult(false, "", "image decode unavailable in this environment",
                ErrorClass.UNKNOWN)
        }
    }
}
