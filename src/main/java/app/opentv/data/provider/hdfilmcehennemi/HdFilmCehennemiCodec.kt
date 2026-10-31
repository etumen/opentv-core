/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.hdfilmcehennemi

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/**
 * Pure parsing and cryptographic helpers for HDFilmCehennemi, Rapidrame, SetPlay, and FastPlay.
 */
internal object HdFilmCehennemiCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class FastPlayTrack(
        val file: String,
        val label: String? = null,
        val lang: String? = null,
        val kind: String? = null,
    )

    data class PaginationInfo(
        val maxPages: Int,
        val contentType: String,
        val termId: String,
    )

    data class PaginationActionInfo(
        val maxPages: Int,
        val pageAction: String,
    )

    /**
     * Extracts pagination info from HDFilmCehennemi new router pages (data-page-action and data-pages).
     */
    fun parsePaginationActionInfo(doc: Document): PaginationActionInfo? {
        val nav = doc.selectFirst("nav.pagination-container, [data-pages][data-page-action], .pagination-container")
            ?: return null
        val maxPages = nav.attr("data-pages").toIntOrNull() ?: 1
        val pageAction = nav.attr("data-page-action").trim()
        return if (pageAction.isNotEmpty()) PaginationActionInfo(maxPages, pageAction) else null
    }

    /**
     * Decrypts obfuscated stream URL in Rapidrame embed HTML.
     */
    fun decryptRapidrame(html: String, hostname: String = "hdfilmcehennemi.mobi"): String? {
        val usedVar = Regex("""sources:\s*\[\{\s*file:\s*([a-zA-Z0-9_]+)""").find(html)?.groupValues?.get(1)
        val targetRegex = if (!usedVar.isNullOrBlank()) {
            Regex("""var\s+${Regex.escape(usedVar)}\s*=\s*\w+\s*\(\s*["']([^"']+)["']\.split\(\s*["']([^"']+)["']\s*\)""")
        } else {
            Regex("""var\s+\w+\s*=\s*\w+\s*\(\s*["']([^"']+)["']\.split\(\s*["']([^"']+)["']\s*\)""")
        }
        val match = targetRegex.find(html)
            ?: Regex("""var\s+\w+\s*=\s*\w+\s*\(\s*["']([^"']+)["']\.split\(\s*["']([^"']+)["']\s*\)""").find(html)
            ?: return null

        val partsStr = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
        val sep = match.groupValues[2]
        val parts = partsStr.split(sep).toMutableList()
        if (parts.size < 10) return null

        return runCatching {
            val x2fv = parts.size - 2
            val nsb1 = x2fv % 7
            val gzk5z = 8 + (x2fv % 5)

            val o056 = parts.removeAt(gzk5z)
            val jq1o = parts.removeAt(nsb1) + hostname
            var b7xp1 = parts.joinToString("")

            if (jq1o.length > 4096) {
                b7xp1 = decodeBase64String(b7xp1)
            }

            var f1i = 0
            var h54 = 0
            for (idx in jq1o.indices) {
                val code = jq1o[idx].code
                f1i = (f1i * 37 + code) % 241
                h54 = (h54 + ((code shl 1) xor idx)) and 255
            }

            val p73 = (f1i * 3 + h54) % 256
            val ft6 = (h54 % 11) + 5
            var gnb0u = ((h54 * 251 + f1i) % 65519) + 1

            for (i in o056.length - 1 downTo 0) {
                val c = o056[i]
                b7xp1 = when (c) {
                    '7' -> decodeBase64String(b7xp1)
                    '3' -> b7xp1.reversed()
                    else -> {
                        val se20 = (26 - ((c.code - 96) % 26)) % 26
                        caesarShift(b7xp1, se20)
                    }
                }
            }

            if (o056.length > 2048) {
                b7xp1 = b7xp1.reversed()
            }

            val len = b7xp1.length
            if (len > 1) {
                val y1xgt = IntArray(len)
                for (idx in len - 1 downTo 1) {
                    gnb0u = (gnb0u * 97 + 41) % 65519
                    y1xgt[idx] = gnb0u % (idx + 1)
                }

                val mz47z = b7xp1.toCharArray()
                for (idx in 1 until len) {
                    val czm3 = y1xgt[idx]
                    val tmp = mz47z[idx]
                    mz47z[idx] = mz47z[czm3]
                    mz47z[czm3] = tmp
                }
                b7xp1 = String(mz47z)
            }

            val sb = StringBuilder(b7xp1.length)
            var a1tt = p73
            for (c in b7xp1) {
                val code = c.code
                a1tt = (a1tt * 5 + ft6) % 256
                sb.append((code xor a1tt).toChar())
                a1tt = (a1tt + code) % 256
            }
            sb.toString().trim().takeIf { it.startsWith("http") }
        }.onFailure { it.printStackTrace() }.getOrNull()
    }

    /**
     * Parses subtitle tracks from Rapidrame player embed.
     */
    fun parseRapidrameSubtitles(html: String): List<FastPlayTrack> {
        val tracksRaw = Regex("""tracks\s*:\s*(\[\s*\{[\s\S]*?\}\s*\])""").find(html)
            ?.groupValues?.getOrNull(1) ?: return emptyList()

        return runCatching {
            val array = json.parseToJsonElement(tracksRaw).jsonArray
            array.mapNotNull { element ->
                val obj = element.jsonObject
                val file = obj["file"]?.jsonPrimitive?.contentOrNull
                    ?.replace("\\/", "/")
                    ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val label = obj["label"]?.jsonPrimitive?.contentOrNull
                val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                val lang = when {
                    label?.contains("Turkish", true) == true || label?.contains("Türk", true) == true -> "tr"
                    label?.contains("English", true) == true || label?.contains("İngiliz", true) == true -> "en"
                    label?.contains("Forced", true) == true -> "forced"
                    else -> null
                }
                FastPlayTrack(file = file, label = label, lang = lang, kind = kind)
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeBase64String(value: String): String =
        String(decodeBase64(value), StandardCharsets.ISO_8859_1)

    private fun caesarShift(text: String, shift: Int): String =
        text.map { char ->
            when (char) {
                in 'A'..'Z' -> ((char.code - 65 + shift) % 26 + 65).toChar()
                in 'a'..'z' -> ((char.code - 97 + shift) % 26 + 97).toChar()
                else -> char
            }
        }.joinToString("")

    /**
     * 32-bit FNV-1a hash algorithm matching FastPlay player proof implementation.
     */
    fun fnv1a(input: String): String {
        var hash = -0x7ee3623b // 0x811c9dc5 as Int
        for (ch in input) {
            hash = (hash xor (ch.code and 0xff)) * 0x01000193
        }
        val unsigned = hash.toLong() and 0xffffffffL
        return unsigned.toString(16).padStart(8, '0')
    }

    /**
     * Generates a random alphanumeric base36 nonce string (similar to JS Math.random().toString(36)).
     */
    fun generateRandomBase36(length: Int = 6): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val rnd = ThreadLocalRandom.current()
        return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * Generates the Anti-Bot `X-Sp` header required by FastPlay manifest requests.
     * Format: `{spT}.{nonce}.{hash}` where `hash = fnv1a(sp + "|" + spT + "|" + nonce)`.
     */
    fun generateXSp(sp: String, spT: Long, nonce: String? = null): String {
        val r = nonce?.takeIf { it.isNotBlank() } ?: generateRandomBase36()
        val hash = fnv1a("$sp|$spT|$r")
        return "$spT.$r.$hash"
    }

    /**
     * Decodes SetPlay XOR encrypted payload.
     * `SPG.cerceve("b2", encBase64, keyBase64)`
     */
    fun xorDecode(encBase64: String, keyBase64: String): String {
        val enc = decodeBase64(encBase64)
        val key = decodeBase64(keyBase64)
        require(enc.isNotEmpty() && key.isNotEmpty()) { "Encrypted payload and key must not be empty" }
        val out = ByteArray(enc.size)
        for (i in enc.indices) {
            out[i] = (enc[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(out, StandardCharsets.UTF_8).trim()
    }

    fun decodeBase64(value: String): ByteArray {
        val clean = value.replace("\\/", "/").filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '-' || it == '_' }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { Base64.getUrlDecoder().decode(padded) }
    }

    /**
     * Extracts (enc, key) from SetPlay script `SPG.cerceve("...", "enc", "key")`.
     */
    fun parseSetPlayCerceve(html: String): Pair<String, String>? {
        val match = Regex(
            """SPG\.cerceve\s*\(\s*["'][^"']+["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)""",
        ).find(html) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    /**
     * Extracts stream path from FastPlay `window.FSP = { stream: "...", ... }`.
     */
    fun parseFastPlayStream(html: String): String? =
        Regex("""(?:stream|src)\s*:\s*["']([^"'\s\n]+)["']""")
            .find(html)?.groupValues?.getOrNull(1)

    /**
     * Extracts (sp, spT) from FastPlay `window.SPG_A = { ... }`.
     */
    fun parseFastPlaySp(html: String): Pair<String, Long>? {
        val spMatch = Regex(""""sp"\s*:\s*"([a-f0-9]+)"""", RegexOption.IGNORE_CASE).find(html)
        val spTMatch = Regex(""""spT"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(html)
        if (spMatch != null && spTMatch != null) {
            val sp = spMatch.groupValues[1]
            val spT = spTMatch.groupValues[1].toLongOrNull() ?: (System.currentTimeMillis() / 1000L)
            return Pair(sp, spT)
        }
        return null
    }

    /**
     * Parses subtitle tracks from FastPlay `window.FSP.tracks`.
     */
    fun parseFastPlayTracks(html: String): List<FastPlayTrack> {
        val tracksRaw = Regex("""tracks\s*:\s*(\[\s*\{[\s\S]*?\}\s*\])""").find(html)
            ?.groupValues?.getOrNull(1) ?: return emptyList()

        return runCatching {
            val array = json.parseToJsonElement(tracksRaw).jsonArray
            array.mapNotNull { element ->
                val obj = element.jsonObject
                val file = obj["file"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val label = obj["label"]?.jsonPrimitive?.contentOrNull
                val lang = obj["lang"]?.jsonPrimitive?.contentOrNull
                val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                FastPlayTrack(file = file, label = label, lang = lang, kind = kind)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Extracts AJAX nonce from HDFilmCehennemi page HTML.
     */
    fun parseVideoAjaxNonce(html: String): String? =
        Regex("""window\.videoAjax\s*=\s*\{[^}]*nonce\s*:\s*['"]([a-f0-9]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""['"]nonce['"]?\s*:\s*['"]([a-f0-9]+)['"]""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)

    /**
     * Extracts post ID from HDFilmCehennemi page HTML.
     */
    fun parseVideoAjaxPostId(html: String): String? =
        Regex("""data-post-id\s*=\s*['"](\d+)['"]""").find(html)?.groupValues?.getOrNull(1)

    /**
     * Extracts part key from HDFilmCehennemi page HTML.
     */
    fun parseVideoAjaxPartKey(html: String): String? =
        Regex("""data-part-key\s*=\s*['"]([^'"]*)['"]""").find(html)?.groupValues?.getOrNull(1)

    /**
     * Extracts list of available player names (e.g. SetPlay).
     */
    fun parseVideoAjaxPlayerNames(html: String): List<String> =
        Regex("""data-player-name\s*=\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .ifEmpty { listOf("SetPlay") }

    /**
     * Extracts legacy pagination info from HDFilmCehennemi category Document.
     */
    fun parsePaginationInfo(doc: Document): PaginationInfo? {
        val pageNumbersEl = doc.selectFirst(".page-numbers, [data-max-pages]")
        val maxPages = pageNumbersEl?.attr("data-max-pages")?.toIntOrNull()
            ?: Regex("""maxPages\s*=\s*(\d+)""").find(doc.html())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null

        val contentType = pageNumbersEl?.attr("data-type")?.takeIf { it.isNotBlank() }
            ?: Regex("""contentType\s*=\s*['"]([^'"]+)['"]""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: "movies"

        val termId = pageNumbersEl?.attr("data-term-id")?.takeIf { it.isNotBlank() }
            ?: Regex("""termId\s*=\s*['"]?(\d+)['"]?""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: "0"

        return PaginationInfo(maxPages = maxPages, contentType = contentType, termId = termId)
    }
}
