/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.filmmakinesi

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Android-free helpers for FilmMakinesi Rapid/CloseLoad embeds. */
internal object FilmMakinesiCodec {
    data class Subtitle(val url: String, val label: String, val language: String?)

    fun extractRapidStream(html: String): String? {
        val unpacked = unpackPackerJs(html) ?: html
        return extractObfuscatedUrl(unpacked)
            ?: extractJsonLdStream(html)
            ?: extractDirectM3u8(html)
    }

    fun extractCloseLoadStream(html: String): String? {
        extractObfuscatedUrl(html)?.let { return it }
        extractParts(html)?.let { parts ->
            listOf(::decryptV1, ::decryptV2, ::decryptV3, ::decryptV4).forEach { decoder ->
                runCatching { decoder(parts) }.getOrNull()
                    ?.takeIf { it.startsWith("http") }
                    ?.let { return it }
            }
        }
        return extractJsonLdStream(html) ?: extractDirectM3u8(html)
    }

    fun extractSubtitles(html: String, baseUrl: String): List<Subtitle> {
        val tracks = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val objects = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(tracks).map { it.groupValues[1] }.toList()
        return objects.mapNotNull { raw ->
            val file = field(raw, "file")?.replace("\\/", "/") ?: return@mapNotNull null
            val label = field(raw, "label")?.trim().orEmpty()
            val code = field(raw, "language")?.trim()?.lowercase()
            val resolved = resolveUrl(baseUrl, file) ?: return@mapNotNull null
            val language = when {
                code == "tr" || label.contains("Turkish", true) || label.contains("Türk", true) -> "tr"
                code == "en" || label.contains("English", true) || label.contains("İngiliz", true) -> "en"
                code == "forced" || label.contains("Forced", true) -> "forced"
                else -> code
            }
            Subtitle(resolved, label.ifBlank { language ?: "Altyazı" }, language)
        }.distinctBy { it.url }
    }

    private fun field(raw: String, name: String): String? =
        Regex("""["']?$name["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)

    private fun extractJsonLdStream(html: String): String? =
        Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace(".txt", ".m3u8")
            ?.takeIf { it.startsWith("http") }

    private fun extractDirectM3u8(html: String): String? =
        Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.replace("\\/", "/")
    private fun extractObfuscatedUrl(js: String): String? {
        val match = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*(\w+)\s*\(\s*\[(.*?)]\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(js) ?: return null
        val functionName = match.groupValues[1]
        val parts = Regex(""""([^"]*)"""").findAll(match.groupValues[2])
            .map { it.groupValues[1].replace("\\/", "/").replace("\\\"", "\"") }
            .toList()
        if (parts.isEmpty()) return null
        val body = extractFunctionBody(js, functionName) ?: return null
        return executeDecoder(body, parts)
    }

    private fun extractParts(js: String): List<String>? {
        val match = Regex(
            """var\s+\w+\s*=\s*\w+\s*\(\s*\[(.*?)]\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(js) ?: return null
        return Regex(""""([^"]*)"""").findAll(match.groupValues[1])
            .map { it.groupValues[1].replace("\\/", "/").replace("\\\"", "\"") }
            .toList().takeIf { it.isNotEmpty() }
    }

    internal fun executeDecoder(functionBody: String, parts: List<String>): String? =
        runCatching {
            val seedMatch = Regex(
                """var\s+\w+\s*=\s*"([^"]+)"\s*;\s*var\s+\w+\s*=\s*"([^"]+)"""",
            ).find(functionBody) ?: return null
            val seed = seedMatch.groupValues[1]
            val ops = seedMatch.groupValues[2]

            var value = parts.joinToString("")
            var hash = 0
            var xor = 0
            seed.forEachIndexed { index, char ->
                hash = (hash * 31 + char.code) % 251
                xor = (xor xor (char.code + index)) and 255
            }
            val start = (hash + xor) % 256
            val step = (hash % 13) + 3
            var prng = ((hash * 256 + xor) % 65521) + 1

            for (i in ops.length - 1 downTo 0) {
                val op = ops[i]
                value = when (op) {
                    'b' -> decodeBase64String(value)
                    'v' -> value.reversed()
                    else -> caesarShift(value, (26 - ((op.code - 64) % 26)) % 26)
                }
            }
            if (ops.length > 4096) value = value.reversed()

            if (value.length > 1) {
                val swaps = IntArray(value.length)
                for (i in value.length - 1 downTo 1) {
                    prng = (prng * 75 + 74) % 65537
                    swaps[i] = prng % (i + 1)
                }
                val chars = value.toCharArray()
                for (i in 1 until chars.size) {
                    val j = swaps[i]
                    val tmp = chars[i]
                    chars[i] = chars[j]
                    chars[j] = tmp
                }
                value = String(chars)
            }
            val decoded = StringBuilder(value.length)
            var acc = start
            value.forEach { char ->
                val byte = char.code
                acc = (acc + step) % 256
                decoded.append((byte xor acc).toChar())
                acc = (acc + byte) % 256
            }
            decoded.toString().trim().takeIf { it.startsWith("http") }
        }.getOrNull()

    internal fun unpackPackerJs(html: String): String? =
        runCatching {
            val startMarker = "eval(function(p,a,c,k,e,d){"
            val endMarker = ",0,{}))"
            val start = html.indexOf(startMarker)
            if (start < 0) return null
            val end = html.indexOf(endMarker, start + startMarker.length)
            if (end < 0) return null
            val block = html.substring(start, end + endMarker.length)
            val packedStartMarker = block.indexOf("}('")
            if (packedStartMarker < 0) return null
            val packedStart = packedStartMarker + 3
            val packedEnd = block.indexOf("',", packedStart)
            if (packedEnd < 0) return null
            val packed = block.substring(packedStart, packedEnd)

            val afterPacked = block.substring(packedEnd + 2)
            val baseEnd = afterPacked.indexOf(',')
            if (baseEnd < 0) return null
            val base = afterPacked.substring(0, baseEnd).trim().toInt()
            val afterBase = afterPacked.substring(baseEnd + 1)
            val countEnd = afterBase.indexOf(',')
            if (countEnd < 0) return null
            val count = afterBase.substring(0, countEnd).trim().toInt()
            val dictStartMarker = afterBase.indexOf('\'')
            if (dictStartMarker < 0) return null
            val dictStart = dictStartMarker + 1
            val dictEnd = afterBase.indexOf("'.split", dictStart)
            if (dictEnd < 0) return null
            val dictionary = afterBase.substring(dictStart, dictEnd).split('|')

            val lookup = buildMap {
                for (index in count - 1 downTo 0) {
                    val key = packerEncode(index, base)
                    put(key, dictionary.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: key)
                }
            }
            var result = packed
            lookup.keys.sortedByDescending { it.length }.forEach { key ->
                result = result.replace(
                    Regex("\\b" + Regex.escape(key) + "\\b"),
                    lookup.getValue(key),
                )
            }
            result
        }.getOrNull()

    private fun extractFunctionBody(js: String, name: String): String? {
        val start = js.indexOf("function $name")
        if (start < 0) return null
        val brace = js.indexOf('{', start)
        if (brace < 0) return null
        var depth = 1
        var index = brace + 1
        while (depth > 0 && index < js.length) {
            when (js[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        return if (depth == 0) js.substring(brace + 1, index - 1) else null
    }

    private fun packerEncode(number: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (number == 0) return "0"
        var value = number
        return buildString {
            while (value > 0) {
                insert(0, digits[value % base])
                value /= base
            }
        }
    }
    private fun decryptV1(parts: List<String>): String? {
        var value = parts.joinToString("")
        value = caesarShift(value, 9)
        value = caesarShift(value, 16).reversed()
        var decoded = decodeBase64String(value)
        decoded = decodeBase64String(decoded)
        return xorUnmix(decoded, 241, 11)
    }

    private fun decryptV2(parts: List<String>): String? {
        val value = caesarShift(parts.joinToString("").reversed(), 15)
        var decoded = decodeBase64String(value).reversed()
        decoded = decodeBase64String(decoded)
        return xorUnmix(decoded, 185, 12)
    }

    private fun decryptV3(parts: List<String>): String? {
        var decoded = decodeBase64String(parts.joinToString(""))
        decoded = decodeBase64String(decoded).reversed()
        decoded = caesarShift(decoded, 25)
        decoded = decodeBase64String(decoded)
        return xorUnmix(decoded, 77, 9)
    }

    private fun decryptV4(parts: List<String>): String? {
        var decoded = decodeBase64String(parts.joinToString("")).reversed()
        decoded = decodeBase64String(decoded)
        return xorUnmix(decoded, 130, 10)
    }

    private fun xorUnmix(text: String, start: Int, step: Int): String {
        var acc = start
        return buildString(text.length) {
            text.forEach { char ->
                val byte = char.code
                acc = (acc + step) % 256
                append((byte xor acc).toChar())
                acc = (acc + byte) % 256
            }
        }
    }

    private fun caesarShift(text: String, shift: Int): String =
        text.map { char ->
            when (char) {
                in 'A'..'Z' -> ((char.code - 65 + shift) % 26 + 65).toChar()
                in 'a'..'z' -> ((char.code - 97 + shift) % 26 + 97).toChar()
                else -> char
            }
        }.joinToString("")

    private fun decodeBase64String(value: String): String =
        String(decodeBase64(value), StandardCharsets.ISO_8859_1)

    private fun decodeBase64(value: String): ByteArray {
        val clean = value.filterNot(Char::isWhitespace)
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { Base64.getUrlDecoder().decode(padded) }
    }

    private fun resolveUrl(base: String, raw: String): String? {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val root = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.getOrNull(1)
            ?: return null
        return root + if (raw.startsWith('/')) raw else "/$raw"
    }
}
