/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.dizipal

import android.content.Context
import android.util.Log
import app.opentv.data.provider.*
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziPalProvider(
    context: Context?,
    private val http: OkHttpClient,
) : Provider {
    internal constructor(http: OkHttpClient) : this(null, http)
    override val id = PROVIDER_ID
    override val name = "DiziPal"
    override val supportedMediaTypes = setOf(ProviderMediaType.SERIES)

    private val browser = context?.let(::DiziPalBrowserSession)
    fun browserSession(): DiziPalBrowserSession? = browser
    private val domainPrefs = context?.applicationContext?.getSharedPreferences(
        DOMAIN_PREFS,
        Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var resolvedBaseUrl: String? = null
    @Volatile private var resolvedAtMillis: Long = 0L

    private data class Section(
        val title: String,
        val path: String,
        val latestEpisodes: Boolean = false,
        val showOnHome: Boolean = true,
    )

    private data class SiteResponse(
        val body: String,
        val headers: Map<String, List<String>>,
    )

    private data class Playback(
        val streams: List<ProviderStream>,
        val subtitles: List<ProviderSubtitle>,
    )

    private data class ResolvedHls(
        val url: String,
        val referer: String,
        val cookie: String = "",
    )

    private data class CachedPlayback(
        val createdAtMillis: Long,
        val value: Playback,
    )

    private val playbackCache = linkedMapOf<String, CachedPlayback>()

    private val sections = linkedMapOf(
        "latest" to Section("Diziler", "/yabanci-dizi-izle"),
        "episodes" to Section("Son Bölümler", "/yeni-eklenen-dizi-bolumler", latestEpisodes = true),
        "netflix" to Section("Netflix", "/kanal/netflix"),
        "exxen" to Section("Exxen", "/kanal/exxen"),
        "disney" to Section("Disney+", "/kanal/disney"),
        "prime" to Section("Prime Video", "/kanal/prime-video"),
        "apple" to Section("Apple TV+", "/kanal/apple-tv", showOnHome = false),
        "max" to Section("Max", "/kanal/max", showOnHome = false),
        "hulu" to Section("Hulu", "/kanal/hulu", showOnHome = false),
        "tod" to Section("TOD", "/kanal/tod", showOnHome = false),
        "tabii" to Section("tabii", "/kanal/tabii", showOnHome = false),
    )

    override val catalogSections: List<ProviderCatalogSection>
        get() = sections.map { (sectionId, section) ->
            ProviderCatalogSection(
                id = sectionId,
                title = section.title,
                mediaType = ProviderMediaType.SERIES,
                showOnHome = section.showOnHome,
            )
        }

    override suspend fun catalog(
        request: ProviderCatalogRequest,
    ): ProviderResult<ProviderCatalogPage> = guarded("catalog") {
        val base = baseUrl()
        val section = sections[request.sectionId ?: "latest"] ?: sections.getValue("latest")
        val page = request.page.coerceAtLeast(1)

        val items = when {
            section.latestEpisodes -> {
                val target = if (page == 1) {
                    base + section.path
                } else {
                    base + section.path + "?page=$page"
                }
                parseLatestEpisodeCards(fetchDocument(target))
            }

            section.path.startsWith("/kanal/") -> {
                loadChannelPage(base, section, page)
            }

            else -> {
                val target = listingPageUrl(base, section.path, page)
                parseSeriesCards(fetchDocument(target))
            }
        }

        Log.d(TAG, "catalog section=${section.title} page=$page items=${items.size}")
        ProviderCatalogPage(
            title = section.title,
            items = items,
            nextPage = if (items.isEmpty()) null else page + 1,
        )
    }

    /** Next.js catalogue uses `page`, not the legacy `sayfa` query parameter. */
    internal fun listingPageUrl(base: String, path: String, page: Int): String {
        if (page <= 1) return base + path
        val separator = if ('?' in path) "&" else "?"
        return base + path + separator + "page=$page"
    }

    private suspend fun loadChannelPage(
        base: String,
        section: Section,
        page: Int,
    ): List<ProviderItem> {
        val pageUrl = if (page == 1) {
            base + section.path
        } else {
            base + section.path + "?page=$page"
        }
        val doc = fetchDocument(pageUrl)
        val renderedItems = parseChannelSeriesCards(doc)
        if (renderedItems.isNotEmpty()) return renderedItems

        val session = browser ?: return emptyList()
        val channelId = extractChannelId(doc) ?: return emptyList()
        val slug = section.path.substringAfterLast('/').ifBlank { "netflix" }

        val response = runCatching {
            session.postForm(
                url = base + "/bg/getserielistbychannel",
                data = listOf(
                    "cKey" to CHANNEL_API_KEY,
                    "cValue" to CHANNEL_API_VALUE,
                    "curPage" to page.toString(),
                    "channelId" to channelId,
                    "languageId" to "2,3,4",
                    "slug" to slug,
                ),
            )
        }.onFailure {
            Log.w(
                TAG,
                "Legacy channel API unavailable for " + section.title,
                it,
            )
        }.getOrNull() ?: return emptyList()

        val html = runCatching {
            json.parseToJsonElement(response)
                .jsonObject["data"]
                ?.jsonObject
                ?.get("html")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull().orEmpty()

        return if (html.isBlank()) {
            emptyList()
        } else {
            parseSeriesCards(Jsoup.parse(html, pageUrl)).distinctBy { it.id }
        }
    }

    override suspend fun search(
        request: ProviderSearchRequest,
    ): ProviderResult<List<ProviderItem>> = guarded("search") {
        val query = request.query.trim()
        if (query.isBlank()) return@guarded emptyList()

        val base = baseUrl()
        val rendered = runCatching { searchRenderedPage(base, query) }
            .onFailure { Log.w(TAG, "Rendered search failed, trying legacy API", it) }
            .getOrDefault(emptyList())
        if (rendered.isNotEmpty()) return@guarded rendered

        val legacyUrl = base + "/ajax-search?q=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val legacy = runCatching {
            val response = siteRequest(
                url = legacyUrl,
                referer = base + "/",
                ajax = true,
            )
            parseSearch(response.body)
        }.onFailure {
            Log.w(TAG, "Legacy DiziPal search unavailable", it)
        }.getOrDefault(emptyList())

        legacy
    }

    private suspend fun searchRenderedPage(
        base: String,
        query: String,
    ): List<ProviderItem> {
        val url = base + "/arama-yap?s=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())

        val direct = directPageRequest(url, base + "/")
        if (direct != null) {
            val parsed = parseSearchPage(Jsoup.parse(direct, url))
            if (parsed.isNotEmpty() || direct.contains("arama-yap", ignoreCase = true)) {
                return parsed
            }
        }

        val response = siteRequest(
            url = url,
            referer = base + "/",
            ajax = false,
            noCache = true,
        )
        return parseSearchPage(Jsoup.parse(response.body, url))
    }
    override suspend fun load(itemId: String): ProviderResult<ProviderDetails> =
        guarded("load") {
            val doc = fetchDocument(absolute(itemId))
            parseDetails(doc, absolute(itemId))
                ?: throw IllegalArgumentException("Series details not found")
        }

    override suspend fun episodes(
        seriesId: String,
    ): ProviderResult<List<ProviderEpisode>> = guarded("episodes") {
        val absoluteId = absolute(seriesId)
        val doc = fetchDocument(absoluteId)
        parseEpisodes(doc, absoluteId)
    }

    override suspend fun streams(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderStream>> {
        val episode = target as? ProviderPlaybackTarget.Episode
            ?: return failure("streams", ProviderErrorCode.UNSUPPORTED, "Episode target required")
        return when (val result = playback(episode)) {
            is ProviderResult.Success -> ProviderResult.Success(result.value.streams)
            is ProviderResult.Failure -> result
        }
    }

    override suspend fun subtitles(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderSubtitle>> {
        val episode = target as? ProviderPlaybackTarget.Episode
            ?: return failure("subtitles", ProviderErrorCode.UNSUPPORTED, "Episode target required")
        return when (val result = playback(episode)) {
            is ProviderResult.Success -> ProviderResult.Success(result.value.subtitles)
            is ProviderResult.Failure -> result
        }
    }

    internal fun parseSeriesCards(doc: Document): List<ProviderItem> {
        val legacy = doc.select("ul.content-grid > li").mapNotNull { element ->
            val anchor = element.selectFirst("a[href*='/series/'], a[href*='/dizi/']")
                ?: element.selectFirst("a")
                ?: return@mapNotNull null
            parseSeriesAnchor(anchor, element, doc)
        }
        val current = doc.select("a[href*='/series/'], a[href*='/dizi/']").mapNotNull { anchor ->
            parseSeriesAnchor(anchor, anchor, doc)
        }
        val nextPayload = parseNextSeriesCards(doc)

        return (legacy + current + nextPayload)
            .distinctBy { it.id }
    }

    internal fun parseChannelSeriesCards(doc: Document): List<ProviderItem> {
        val channelItems = extractNextChannelItemsPayload(doc)
            ?.let { parseNextSeriesObjects(it, doc) }
            .orEmpty()
        return channelItems.ifEmpty { parseSeriesCards(doc) }
    }

    internal fun extractChannelId(doc: Document): String? {
        doc.selectFirst("input[name=channelId]")?.attr("value")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex("""channelId\s*[:=]\s*(\d+)""")
            .find(doc.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        return Regex(
            """"action":"getSerieListByChannel","params":\[(\d+),""",
        ).find(normalizedNextPayload(doc))
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun normalizedNextPayload(doc: Document): String =
        doc.html()
            .replace("\\\"", "\"")
            .replace("\\/", "/")

    internal fun extractEncryptedPlayerPayload(doc: Document): String? {
        val payload = normalizedNextPayload(doc)
        val encMarker = payload.indexOf("\"enc\":")
        if (encMarker < 0) return null

        val objectStart = payload.indexOf('{', encMarker)
        if (objectStart < 0) return null
        val objectEnd = findJsonObjectEnd(payload, objectStart) ?: return null
        val candidate = payload.substring(objectStart, objectEnd + 1)

        return candidate.takeIf {
            it.contains("\"ciphertext\":") &&
                it.contains("\"iv\":") &&
                it.contains("\"salt\":")
        }
    }

    private fun findJsonObjectEnd(value: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun extractNextChannelItemsPayload(doc: Document): String? {
        val payload = normalizedNextPayload(doc)
        val marker = payload.indexOf("\"action\":\"getSerieListByChannel\"")
        if (marker < 0) return null

        val key = payload.lastIndexOf("\"items\":[", marker)
        if (key < 0) return null
        val arrayStart = payload.indexOf('[', key)
        if (arrayStart < 0 || arrayStart >= marker) return null

        val arrayEnd = findJsonArrayEnd(payload, arrayStart) ?: return null
        if (arrayEnd > marker) return null
        return payload.substring(arrayStart + 1, arrayEnd)
    }

    private fun findJsonArrayEnd(value: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun parseNextSeriesCards(doc: Document): List<ProviderItem> =
        parseNextSeriesObjects(normalizedNextPayload(doc), doc)

    private fun parseNextSeriesObjects(
        payload: String,
        doc: Document,
    ): List<ProviderItem> {
        val objects = Regex(
            """\{[^{}]{0,5000}"href":"(/series/[^"]+)"[^{}]{0,5000}\}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(payload)

        return objects.mapNotNull { match ->
            val raw = match.value
            val href = Regex(""""href":"([^"]+)"""")
                .find(raw)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            val title = Regex(""""t":"([^"]+)"""")
                .find(raw)?.groupValues?.getOrNull(1)
                ?.decodeJsonString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = Regex(""""img":"([^"]*)"""")
                .find(raw)?.groupValues?.getOrNull(1)
                ?.decodeJsonString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val year = Regex(""""year":"((?:19|20)\d{2})"""")
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val rating = Regex(""""(?:r|imdb)":"([0-9]+(?:\.[0-9]+)?)"""")
                .find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val genres = Regex(""""genres":"([^"]*)"""")
                .find(raw)?.groupValues?.getOrNull(1)
                ?.decodeJsonString()
                ?.split('·', ',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            ProviderItem(
                providerId = id,
                id = absoluteFrom(doc.baseUri().ifBlank { FALLBACK_BASE_URL }, href)
                    ?: return@mapNotNull null,
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = poster?.let {
                    absoluteFrom(doc.baseUri().ifBlank { FALLBACK_BASE_URL }, it) ?: it
                },
                year = year,
                rating = rating,
                genres = genres,
            )
        }.distinctBy { it.id }.toList()
    }

    private fun String.decodeJsonString(): String =
        replace("""\u0026""", "&")
            .replace("""\u003c""", "<")
            .replace("""\u003e""", ">")
            .replace("""\n""", " ")
            .replace("""\t""", " ")
            .replace("""\\\"""", "\"")
            .replace("""\\\\""", "\\")

    private fun parseSeriesAnchor(
        anchor: Element,
        scope: Element,
        doc: Document,
    ): ProviderItem? {
        val href = absoluteFrom(
            doc.baseUri().ifBlank { FALLBACK_BASE_URL },
            anchor.attr("href"),
        ) ?: return null
        if (!href.contains("/series/") && !href.contains("/dizi/")) return null

        val title = scope.selectFirst("div.card-info h3, .card-title, .poster-title, .tt, h3, h2")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("aria-label").trim().takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: anchor.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = imageUrl(
            scope.selectFirst("img") ?: anchor.selectFirst("img"),
            doc.baseUri(),
        )
        val year = sequenceOf(
            anchor.attr("aria-label"),
            scope.attr("aria-label"),
            scope.text(),
        ).mapNotNull { value ->
            Regex("""\b(?:19|20)\d{2}\b""").find(value)?.value?.toIntOrNull()
        }.firstOrNull()
        return ProviderItem(
            providerId = id,
            id = href,
            title = title,
            mediaType = ProviderMediaType.SERIES,
            posterUrl = poster,
            year = year,
        )
    }

    internal fun parseLatestEpisodeCards(doc: Document): List<ProviderItem> {
        val candidates = doc.select("div.episodes-list-grid > a.episode-list-item")
            .ifEmpty { doc.select("a[href*='/bolum/']") }

        return candidates.mapNotNull { element ->
            val title = element.selectFirst(".ep-title, .episode-title, .card-title")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: element.attr("aria-label").trim().takeIf { it.isNotBlank() }
                ?: element.attr("title").trim().takeIf { it.isNotBlank() }
                ?: element.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: element.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val href = absoluteFrom(
                doc.baseUri().ifBlank { FALLBACK_BASE_URL },
                element.attr("href"),
            ) ?: return@mapNotNull null
            if (!href.contains("/bolum/")) return@mapNotNull null

            val currentMatch = Regex(
                """/bolum/(.+?)-(\d+)x(\d+)(?:[/?#].*)?$""",
            ).find(href)
            val seriesUrl = if (currentMatch != null) {
                val base = href.substringBefore("/bolum/")
                base + "/series/" + currentMatch.groupValues[1]
            } else {
                href
                    .replace(Regex("""-\d+-sezon-\d+-bolum.*$"""), "")
                    .replace("/bolum/", "/series/")
            }
            val poster = imageUrl(element.selectFirst("img"), doc.baseUri())
            ProviderItem(
                providerId = id,
                id = seriesUrl,
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = poster,
            )
        }.distinctBy { it.id }
    }

    internal fun parseDetails(
        doc: Document,
        seriesId: String,
    ): ProviderDetails? {
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
        val title = doc.selectFirst("h1.series-title, h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: ogTitle
                ?.substringBefore(" izle", missingDelimiterValue = ogTitle)
                ?.substringBefore(" - ", missingDelimiterValue = ogTitle)
                ?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { absoluteFrom(doc.baseUri(), it) }
            ?: imageUrl(
                doc.selectFirst(".series-poster img, .detail-poster img, img.poster"),
                doc.baseUri(),
            )

        val year = doc.selectFirst("div.info-row:contains(Yıl) span.info-value")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(?:19|20)\d{2}\b""")
                .find(doc.selectFirst("main, #router-view, body")?.text().orEmpty())
                ?.value?.toIntOrNull()

        val description = doc.selectFirst("p.series-description")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        val genres = (
            doc.select("div.info-row:contains(Kategoriler) span.info-value.categories a") +
                doc.select("a[href*='/kategori/']")
            )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val imdbId = doc.selectFirst("a[href*='imdb.com/title/tt']")
            ?.attr("href")
            ?.let { Regex("""tt\d{5,12}""", RegexOption.IGNORE_CASE).find(it)?.value }
            ?.lowercase()
            ?: Regex(
                """imdb\.com/title/(tt\d{5,12})""",
                RegexOption.IGNORE_CASE,
            ).find(doc.html())?.groupValues?.getOrNull(1)?.lowercase()
        val externalIds = buildMap {
            imdbId?.let { put("imdb", it) }
        }

        return ProviderDetails(
            item = ProviderItem(
                providerId = id,
                id = seriesId,
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = poster,
                description = description,
                year = year,
                genres = genres,
                externalIds = externalIds,
            ),
            description = description,
            externalIds = externalIds,
        )
    }

    internal fun parseEpisodes(
        doc: Document,
        seriesId: String,
    ): List<ProviderEpisode> {
        val legacy = doc.select("div.detail-episode-item-wrap a.detail-episode-item")
        val anchors = if (legacy.isNotEmpty()) {
            legacy
        } else {
            doc.select("a[href*='/bolum/']")
        }

        val linkedEpisodes = anchors.mapNotNull { anchor ->
            val href = absoluteFrom(doc.baseUri(), anchor.attr("href"))
                ?: return@mapNotNull null
            if (!href.contains("/bolum/")) return@mapNotNull null

            val subtitle = anchor.selectFirst("div.detail-episode-subtitle, .ep-info")
                ?.text()?.trim().orEmpty()
            val text = anchor.text().trim()

            val textMatch = Regex(
                """(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""",
            ).find(subtitle.ifBlank { text })

            val hrefMatch = Regex(
                """-(\d+)x(\d+)(?:[-/?#].*)?$""",
            ).find(href)

            val season = textMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: hrefMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 1
            val episode = textMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: hrefMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: return@mapNotNull null

            val title = anchor.selectFirst(
                "div.detail-episode-title, .ep-title, .episode-title",
            )?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: "Bölüm $episode"

            ProviderEpisode(
                providerId = id,
                seriesId = seriesId,
                id = href,
                season = season,
                episodeNumber = episode,
                title = title,
            )
        }

        val structuredEpisodes = parseJsonLdEpisodes(doc, seriesId)
        return (linkedEpisodes + structuredEpisodes)
            .distinctBy { it.season to it.episodeNumber }
            .sortedWith(compareBy<ProviderEpisode> { it.season }.thenBy { it.episodeNumber })
    }

    internal fun parseJsonLdEpisodes(doc: Document, seriesId: String): List<ProviderEpisode> {
        val result = mutableListOf<ProviderEpisode>()
        doc.select("script[type=application/ld+json]").forEach { script ->
            val root = runCatching { json.parseToJsonElement(script.data()).jsonObject }
                .getOrNull() ?: return@forEach
            val type = root["@type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!type.equals("TVSeries", ignoreCase = true)) return@forEach
            jsonObjectList(root["containsSeason"]).forEach { seasonObject ->
                val seasonUrl = seasonObject["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val seasonNumber = seasonObject["seasonNumber"]?.jsonPrimitive?.intOrNull
                    ?: Regex("""(?:sezon-|/season/)(\d+)""")
                        .find(seasonUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                jsonObjectList(seasonObject["episode"]).forEach { episodeObject ->
                    val rawUrl = episodeObject["url"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: return@forEach
                    val urlMatch = Regex("""-(\d+)x(\d+)(?:[-/?#].*)?$""").find(rawUrl)
                    val season = seasonNumber
                        ?: urlMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: return@forEach
                    val episodeNumber = episodeObject["episodeNumber"]?.jsonPrimitive?.intOrNull
                        ?: urlMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                        ?: return@forEach
                    val href = normalizeDiziPalEpisodeUrl(doc.baseUri(), rawUrl)
                        ?: return@forEach
                    val rawName = episodeObject["name"]?.jsonPrimitive?.contentOrNull
                        ?.trim().orEmpty()
                    val title = rawName
                        .takeUnless { it.matches(Regex("""\d+\.\s*Bölüm""", RegexOption.IGNORE_CASE)) }
                        ?.takeIf { it.isNotBlank() } ?: "Bölüm $episodeNumber"
                    val description = episodeObject["description"]?.jsonPrimitive?.contentOrNull
                        ?.trim()?.takeIf { it.isNotBlank() }
                    result += ProviderEpisode(
                        providerId = id,
                        seriesId = seriesId,
                        id = href,
                        season = season,
                        episodeNumber = episodeNumber,
                        title = title,
                        description = description,
                    )
                }
            }
        }
        return result
    }

    private fun jsonObjectList(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonObject -> listOf(element)
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    private fun normalizeDiziPalEpisodeUrl(base: String, raw: String): String? {
        val absolute = absoluteFrom(base.ifBlank { FALLBACK_BASE_URL }, raw) ?: return null
        val parsed = runCatching { absolute.toHttpUrl() }.getOrNull() ?: return absolute
        if (!parsed.host.startsWith("dizipal", ignoreCase = true)) return absolute
        val active = runCatching { base.toHttpUrl() }.getOrNull()
        return parsed.newBuilder()
            .scheme(active?.scheme ?: "https")
            .host(active?.host ?: parsed.host)
            .build()
            .toString()
    }

    internal fun parseSearchPage(doc: Document): List<ProviderItem> {
        val currentCards = doc.select(
            "a.tc[href*='/series/'], a.tc[href*='/dizi/']",
        ).mapNotNull { anchor ->
            parseSeriesAnchor(anchor, anchor, doc)
        }.distinctBy { it.id }
        if (currentCards.isNotEmpty()) return currentCards

        val legacyCards = doc.select("ul.content-grid > li").mapNotNull { element ->
            val anchor = element.selectFirst("a[href*='/series/'], a[href*='/dizi/']")
                ?: return@mapNotNull null
            parseSeriesAnchor(anchor, element, doc)
        }.distinctBy { it.id }
        if (legacyCards.isNotEmpty()) return legacyCards

        return emptyList()
    }

    internal fun parseSearch(body: String): List<ProviderItem> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val results = root["results"]?.jsonArray ?: return emptyList()

        return results.mapNotNull { element ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!type.equals("Dizi", ignoreCase = true)) return@mapNotNull null

            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val url = absoluteFrom(FALLBACK_BASE_URL, rawUrl) ?: return@mapNotNull null
            val poster = obj["poster"]?.jsonPrimitive?.contentOrNull
                ?.let { absoluteFrom(FALLBACK_BASE_URL, it) }
            val year = obj["year"]?.jsonPrimitive?.intOrNull

            ProviderItem(
                providerId = id,
                id = url,
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = poster,
                year = year,
            )
        }.distinctBy { it.id }
    }

    private suspend fun playback(
        target: ProviderPlaybackTarget.Episode,
    ): ProviderResult<Playback> {
        synchronized(playbackCache) {
            playbackCache[target.episodeId]
                ?.takeIf { System.currentTimeMillis() - it.createdAtMillis < PLAYBACK_CACHE_MILLIS }
                ?.let { return ProviderResult.Success(it.value) }
        }

        val result = guarded("playback") {
            val episodeUrl = absolute(target.episodeId)
            val page = siteRequest(
                url = episodeUrl,
                referer = baseUrl() + "/",
                noCache = true,
            )
            val doc = Jsoup.parse(page.body, episodeUrl)
            val encryptedPayload = doc.selectFirst("div[data-rm-k=true]")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: extractEncryptedPlayerPayload(doc)

            val currentEmbed = encryptedPayload?.let(DiziPalCodec::decryptRmk)
            if (currentEmbed != null) {
                Log.d(TAG, "Resolved encrypted embed host: " + currentEmbed.toHttpUrl().host)
                resolveDplayer(currentEmbed, episodeUrl)
            } else {
                val token = doc.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Video player configuration not found")

                val decoded = decodeConfigToken(token)
                val rawEmbed = Regex(
                    """"v"\s*:\s*"([^"]+)"""",
                ).find(decoded)?.groupValues?.getOrNull(1)
                    ?.replace("\\/", "/")
                    ?: throw IllegalArgumentException("Embed URL not found")
                val embedUrl = normalizeExternalUrl(rawEmbed)

                if (embedUrl.contains("imagestoo", ignoreCase = true)) {
                    resolveImagestoo(embedUrl)
                } else {
                    resolveEmbed(embedUrl, episodeUrl)
                }
            }
        }

        if (result is ProviderResult.Success) {
            synchronized(playbackCache) {
                playbackCache[target.episodeId] =
                    CachedPlayback(System.currentTimeMillis(), result.value)
                while (playbackCache.size > PLAYBACK_CACHE_SIZE) {
                    playbackCache.remove(playbackCache.keys.first())
                }
            }
        }
        return result
    }

    private suspend fun resolveDplayer(
        embedUrl: String,
        episodeUrl: String,
    ): Playback {
        Log.d(TAG, "DPlayer embed url=" + embedUrl)
        val embedRequest = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", EXTERNAL_USER_AGENT)
            .header("Referer", episodeUrl)
            .build()
        val embedResponse = execute(embedRequest)
        val source = embedResponse.body

        extractDplayerNpConfig(source)?.let { config ->
            return resolveDplayerNpConfig(
                config = config,
                embedUrl = embedUrl,
            )
        }

        val subtitles = Regex(
            """"file"\s*:\s*"((?:\\\"|[^"])+)"\s*,\s*"label"\s*:\s*"((?:\\\"|[^"])+)"""",
            RegexOption.IGNORE_CASE,
        ).findAll(source).mapNotNull { match ->
            val rawUrl = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\", "")
            if (!rawUrl.contains(".vtt", true) && !rawUrl.contains(".srt", true)) {
                return@mapNotNull null
            }
            val label = match.groupValues[2]
                .replace("\\u0131", "ı")
                .replace("\\u0130", "İ")
                .replace("\\u00fc", "ü")
                .replace("\\u00e7", "ç")
                .replace("\\u011f", "ğ")
                .replace("\\u015f", "ş")

            ProviderSubtitle(
                providerId = id,
                url = normalizeExternalUrl(rawUrl),
                label = label,
                language = when {
                    label.contains("Türk", true) -> "tr"
                    label.contains("English", true) || label.contains("İngiliz", true) -> "en"
                    else -> null
                },
                mimeType = if (rawUrl.contains(".vtt", true)) "text/vtt" else null,
            )
        }.distinctBy { it.url }.toList()

        val parsedEmbed = embedUrl.toHttpUrl()
        val playlistId = Regex(
            """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE,
        ).find(source)?.groupValues?.getOrNull(1)
            ?: parsedEmbed.queryParameter("v")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("DPlayer playlist id not found")
        val origin = parsedEmbed.scheme + "://" + parsedEmbed.host
        val apiUrl = origin.trimEnd('/') + "/source2.php?v=" +
            URLEncoder.encode(playlistId, StandardCharsets.UTF_8.name())

        val apiRequest = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", EXTERNAL_USER_AGENT)
            .header("Referer", embedUrl)
            .header("Accept", "*/*")
            .build()
        val apiResponse = execute(apiRequest)
        val streams = mutableListOf<ProviderStream>()
        val matches = Regex(
            """"file"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).findAll(apiResponse.body).toList()

        for (match in matches) {
            val raw = match.groupValues[1].replace("\\/", "/")
            if (raw.contains(".vtt", true) || raw.contains(".srt", true)) continue

            var remoteUrl = normalizeExternalUrl(raw)
            if (remoteUrl.contains("m.php")) {
                remoteUrl = remoteUrl.replace("m.php", "master.m3u8")
            }
            if (!remoteUrl.contains(".m3u8", true) && !remoteUrl.contains("master", true)) {
                continue
            }

            val resolved = resolveDplayerInlinePlaylist(
                remoteUrl = remoteUrl,
                embedUrl = embedUrl,
                origin = origin,
            ) ?: ResolvedHls(
                url = remoteUrl,
                referer = embedUrl,
            )

            streams += ProviderStream(
                providerId = id,
                url = resolved.url,
                label = "DiziPal · DPlayer",
                mimeType = HLS_MIME,
                headers = buildMap {
                    put("Origin", origin)
                    put("Referer", resolved.referer)
                    put("User-Agent", EXTERNAL_USER_AGENT)
                    put("Accept", "*/*")
                    if (resolved.cookie.isNotBlank()) put("Cookie", resolved.cookie)
                },
            )
        }

        val distinctStreams = streams.distinctBy { it.url }

        if (distinctStreams.isEmpty()) {
            throw IllegalArgumentException("DPlayer stream not found")
        }

        Log.d(TAG, "DPlayer streams resolved: " + distinctStreams.size)
        return Playback(streams = distinctStreams, subtitles = subtitles)
    }

    internal fun extractDplayerNpConfig(source: String): String? {
        val marker = "window.NP_CONFIG"
        val assignment = source.indexOf(marker)
        if (assignment < 0) return null
        val objectStart = source.indexOf('{', assignment + marker.length)
        if (objectStart < 0) return null
        val objectEnd = findJsonObjectEnd(source, objectStart) ?: return null
        return source.substring(objectStart, objectEnd + 1)
    }

    private suspend fun resolveDplayerNpConfig(
        config: String,
        embedUrl: String,
    ): Playback {
        val root = json.parseToJsonElement(config).jsonObject
        val parsedEmbed = embedUrl.toHttpUrl()
        val origin = parsedEmbed.scheme + "://" + parsedEmbed.host

        val subtitles = root["subtitles"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Altyazı"
            val language = obj["lang"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ProviderSubtitle(
                providerId = id,
                url = normalizeExternalUrl(url),
                label = label,
                language = language,
                mimeType = null,
            )
        }.distinctBy { it.url }

        val streams = mutableListOf<ProviderStream>()
        root["sources"]?.jsonArray.orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val rawMaster = obj["master"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val remoteUrl = normalizeExternalUrl(rawMaster)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "DPlayer"

            val resolved = resolveDplayerInlinePlaylist(
                remoteUrl = remoteUrl,
                embedUrl = embedUrl,
                origin = origin,
            ) ?: ResolvedHls(
                url = remoteUrl,
                referer = embedUrl,
            )

            streams += ProviderStream(
                providerId = id,
                url = resolved.url,
                label = "DiziPal · $title",
                mimeType = HLS_MIME,
                headers = buildMap {
                    put("Origin", origin)
                    put("Referer", resolved.referer)
                    put("User-Agent", EXTERNAL_USER_AGENT)
                    put("Accept", "*/*")
                    if (resolved.cookie.isNotBlank()) put("Cookie", resolved.cookie)
                },
            )
        }

        val distinctStreams = streams.distinctBy { it.url }
        if (distinctStreams.isEmpty()) {
            throw IllegalArgumentException("DPlayer NP_CONFIG stream not found")
        }

        Log.d(TAG, "DPlayer NP_CONFIG streams resolved: " + distinctStreams.size)
        return Playback(
            streams = distinctStreams,
            subtitles = subtitles,
        )
    }

    private suspend fun resolveDplayerInlinePlaylist(
        remoteUrl: String,
        embedUrl: String,
        origin: String,
    ): ResolvedHls? {
        suspend fun request(url: String, referer: String = embedUrl): SiteResponse {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", EXTERNAL_USER_AGENT)
                .header("Referer", referer)
                .header("Origin", origin)
                .header("Accept", "*/*")
                .build()
            return execute(builder)
        }

        val first = try {
            request(remoteUrl)
        } catch (error: Exception) {
            Log.d(TAG, "DPlayer inline first fetch failed: " + error.message)
            return null
        }
        val directPlaylist = first.body.trimStart()
        if (directPlaylist.startsWith("#EXTM3U")) {
            // Let Media3 consume a master playlist from its real HTTP URL. In particular, DPlayer
            // can expose video and dubbed audio as separate HLS renditions. Turning that master
            // into a data: URI (or collapsing it to one video variant) can detach the AUDIO group,
            // which is exactly the "video plays but no audio track exists" failure.
            if (isMasterHls(first.body)) {
                Log.d(
                    TAG,
                    "DPlayer master HLS kept remote for native audio/video rendition handling" +
                        if (hasExternalAudioRendition(first.body)) " (external audio present)" else "",
                )
                return ResolvedHls(
                    url = remoteUrl,
                    referer = embedUrl,
                    cookie = responseCookieHeader(first),
                )
            }

            val mediaPlaylist = absolutizeHlsReferences(first.body, remoteUrl)
            val segments = mediaPlaylist.lineSequence().count {
                it.trimStart().startsWith("http://") || it.trimStart().startsWith("https://")
            }
            Log.d(TAG, "DPlayer direct media playlist inlined, segments=" + segments)
            return ResolvedHls(
                url = toInlineHls(mediaPlaylist),
                referer = remoteUrl,
                cookie = responseCookieHeader(first),
            )
        }

        val lPhpUrl = Regex(
            """https?://[^"'\s<>]+/l\.php\?v=[A-Za-z0-9+/=_%-]+""",
            RegexOption.IGNORE_CASE,
        ).find(first.body)?.value
            ?.replace("&amp;", "&")
            ?: return null

        val lPhpResponse = try {
            request(lPhpUrl, referer = remoteUrl)
        } catch (error: Exception) {
            Log.d(TAG, "DPlayer l.php fetch failed: " + error.message)
            return null
        }
        val playlist = lPhpResponse.body
        if (!playlist.trimStart().startsWith("#EXTM3U")) return null

        val cookie = responseCookieHeader(first, lPhpResponse)
        if (isMasterHls(playlist)) {
            // l.php itself was fetched with remoteUrl as its Referer. Replaying l.php directly in
            // Media3 would need one Referer for the master request and another for its audio/video
            // children. Inline only this master after making every child URI absolute; the child
            // requests can then consistently use l.php as their Referer.
            val master = absolutizeHlsReferences(playlist, lPhpUrl)
            Log.d(
                TAG,
                "DPlayer l.php master HLS inlined with absolute audio/video rendition URIs",
            )
            return ResolvedHls(
                url = toInlineHls(master),
                referer = lPhpUrl,
                cookie = cookie,
            )
        }

        val mediaPlaylist = absolutizeHlsReferences(playlist, lPhpUrl)
        val segments = mediaPlaylist.lineSequence().count {
            it.trimStart().startsWith("http://") || it.trimStart().startsWith("https://")
        }
        Log.d(TAG, "DPlayer inline media HLS resolved, segments=" + segments)
        return ResolvedHls(
            url = toInlineHls(mediaPlaylist),
            referer = lPhpUrl,
            cookie = cookie,
        )
    }

    private fun responseCookieHeader(vararg responses: SiteResponse): String {
        val cookies = linkedMapOf<String, String>()
        responses.forEach { response ->
            response.headers.entries
                .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { (_, values) -> values }
                .forEach { raw ->
                    val pair = raw.substringBefore(';').trim()
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotBlank() && pair.contains('=')) cookies[name] = pair
                }
        }
        return cookies.values.joinToString("; ")
    }

    internal fun isMasterHls(playlist: String): Boolean =
        playlist.lineSequence().any { raw ->
            val line = raw.trimStart()
            line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)
        }

    internal fun hasExternalAudioRendition(master: String): Boolean =
        master.lineSequence().any { raw ->
            val line = raw.trimStart()
            line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                Regex("""\bTYPE\s*=\s*["']?AUDIO["']?\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line)
        }

    internal fun absolutizeHlsReferences(
        playlist: String,
        baseUrl: String,
    ): String {
        val base = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return playlist
        val uriAttribute = Regex(
            """URI\s*=\s*(?:"([^"]+)"|'([^']+)'|([^,\s]+))""",
            RegexOption.IGNORE_CASE,
        )

        return playlist.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                line
            } else if (line.trimStart().startsWith("#")) {
                uriAttribute.replace(line) { match ->
                    val rawUri = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
                    val absolute = base.resolve(rawUri)?.toString() ?: rawUri
                    """URI="$absolute""""
                }
            } else {
                val leading = line.takeWhile { it.isWhitespace() }
                val uri = line.trim()
                leading + (base.resolve(uri)?.toString() ?: uri)
            }
        }
    }

    private fun selectBestDplayerVariant(master: String): String? {
        val lines = master.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        var bestUrl: String? = null
        var bestBandwidth = -1L
        var pendingBandwidth: Long? = null

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pendingBandwidth = Regex(
                    """BANDWIDTH=(\d+)""",
                    RegexOption.IGNORE_CASE,
                ).find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                continue
            }

            val bandwidth = pendingBandwidth ?: continue
            if (line.startsWith("http://") || line.startsWith("https://")) {
                if (bandwidth >= bestBandwidth) {
                    bestBandwidth = bandwidth
                    bestUrl = line
                }
                pendingBandwidth = null
            }
        }

        return bestUrl
    }

    private fun toInlineHls(playlist: String): String {
        val encoded = Base64.getEncoder().encodeToString(
            playlist.toByteArray(StandardCharsets.UTF_8),
        )
        return "data:application/vnd.apple.mpegurl;base64,$encoded"
    }

    private suspend fun resolveImagestoo(embedUrl: String): Playback {
        val videoId = embedUrl.trimEnd('/').substringAfterLast('/')
        val apiUrl = "https://imagestoo.com/player/index.php?data=" +
            URLEncoder.encode(videoId, StandardCharsets.UTF_8.name()) +
            "&do=getVideo"

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", EXTERNAL_USER_AGENT)
            .header("Referer", embedUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val response = execute(request)
        Log.d(TAG, "Imagestoo API response received, bytes=" + response.body.length)
        val securedLink = Regex(
            """"securedLink"\s*:\s*"([^"]+)"""",
        ).find(response.body)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?: throw IllegalArgumentException("Imagestoo stream not found")

        val playerCookie = response.headers["Set-Cookie"].orEmpty()
            .flatMap { it.split(',') }
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("fireplayer_player=") }
            .orEmpty()

        return Playback(
            streams = listOf(
                ProviderStream(
                    providerId = id,
                    url = normalizeExternalUrl(securedLink),
                    label = "DiziPal · Imagestoo",
                    mimeType = HLS_MIME,
                    headers = buildMap {
                        put("Referer", embedUrl)
                        put("User-Agent", EXTERNAL_USER_AGENT)
                        if (playerCookie.isNotBlank()) put("Cookie", playerCookie)
                    },
                ),
            ),
            subtitles = emptyList(),
        )
    }
    private suspend fun resolveEmbed(
        embedUrl: String,
        episodeUrl: String,
    ): Playback {
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", EXTERNAL_USER_AGENT)
            .header("Referer", episodeUrl)
            .build()
        val response = execute(request)
        val source = response.body
        Log.d(TAG, "Embed response received, bytes=" + source.length)

        val direct = Regex(
            """sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""",
            RegexOption.IGNORE_CASE,
        ).find(source)?.groupValues?.getOrNull(1)

        val htmlEmbed = if (direct == null) {
            Regex(
                """v\s*:\s*["']([^"']+\.html.*?)["']""",
                RegexOption.IGNORE_CASE,
            ).find(source)?.groupValues?.getOrNull(1)
        } else {
            null
        }

        val streamUrl = when {
            direct != null -> normalizeExternalUrl(direct).also {
                Log.d(TAG, "Direct HLS found: " + it)
            }
            htmlEmbed != null -> {
                val mediaId = Regex("""embed-([^.]+)\.html""")
                    .find(htmlEmbed)?.groupValues?.getOrNull(1)
                    ?: throw IllegalArgumentException("Embedded media id not found")
                "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/" +
                    mediaId + "_,n,h,.urlset/master.m3u8"
            }
            else -> throw IllegalArgumentException("Stream URL not found")
        }

        val subtitles = parseTracks(source).map { (label, url) ->
            ProviderSubtitle(
                providerId = id,
                url = normalizeExternalUrl(url),
                label = label,
                language = when {
                    label.contains("Türk", true) -> "tr"
                    label.contains("English", true) || label.contains("İngiliz", true) -> "en"
                    else -> null
                },
                mimeType = if (url.contains(".vtt", true)) "text/vtt" else null,
            )
        }

        return Playback(
            streams = listOf(
                ProviderStream(
                    providerId = id,
                    url = streamUrl,
                    label = "DiziPal",
                    mimeType = HLS_MIME,
                    headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to EXTERNAL_USER_AGENT,
                    ),
                ),
            ),
            subtitles = subtitles,
        )
    }

    internal fun parseTracks(source: String): List<Pair<String, String>> {
        val block = Regex(
            """tracks\s*:\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.groupValues?.getOrNull(1) ?: return emptyList()

        return Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .mapNotNull { match ->
                val raw = match.groupValues[1]
                val file = Regex("""file\s*:\s*["']([^"']+)["']""")
                    .find(raw)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                if (!file.contains(".vtt", true) && !file.contains(".srt", true)) {
                    return@mapNotNull null
                }
                val label = Regex("""label\s*:\s*["']([^"']+)["']""")
                    .find(raw)?.groupValues?.getOrNull(1) ?: "Altyazı"
                label to file
            }
            .distinctBy { it.second }
            .toList()
    }

    private fun decodeConfigToken(token: String): String {
        val clean = token.filterNot(Char::isWhitespace)
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(siteRequest(url, baseUrl() + "/").body, url)

    private suspend fun baseUrl(): String {
        app.opentv.data.provider.ProviderRuntimeRules.domain(id)?.let { return it }
        val now = System.currentTimeMillis()
        resolvedBaseUrl
            ?.takeIf { now - resolvedAtMillis < DOMAIN_CACHE_MILLIS }
            ?.let { return it }

        val source = runCatching { resolveDomainSource() }.getOrDefault("")
        val stored = domainPrefs?.getString(PREF_CURRENT_DOMAIN, null).orEmpty()
        val storedNext = domainPrefs?.getString(PREF_NEXT_DOMAIN, null).orEmpty()
        val seeds = listOf(source, stored, storedNext, FALLBACK_BASE_URL)
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()

        var softFallback: String? = null
        var verified: String? = null
        for (seed in seeds) {
            val redirected = resolveRedirectedBase(seed) ?: seed
            if (softFallback == null) softFallback = redirected
            val working = verifyDiziPalBase(redirected)
            if (working != null) {
                verified = working
                break
            }
        }

        val value = verified
            ?: probeForwardDomains(seeds)
            ?: softFallback
            ?: FALLBACK_BASE_URL

        rememberDomain(value)
        refreshAdvertisedNextDomain(value)
        Log.d(TAG, "Resolved DiziPal base: " + value)
        resolvedBaseUrl = value
        resolvedAtMillis = now
        return value
    }

    private suspend fun resolveDomainSource(): String {
        val request = Request.Builder()
            .url(DOMAIN_SOURCE)
            .header("User-Agent", "OpenTV/0.1 (Android)")
            .build()
        val body = execute(request).body
        return Regex(
            """(?m)^\|DiziPalOrijinal:(https?://[^\s]+)\s*$""",
        ).find(body)?.groupValues?.getOrNull(1)?.trimEnd('/').orEmpty()
    }

    private suspend fun verifyDiziPalBase(seed: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(seed)
                    .header("User-Agent", SITE_USER_AGENT)
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    val looksReal = body.contains("Dizipal", ignoreCase = true) &&
                        (body.contains("/series/", ignoreCase = true) ||
                            body.contains("arama-yap", ignoreCase = true) ||
                            body.contains("/_next/static/", ignoreCase = true))
                    if (!looksReal) return@use null
                    response.request.url.newBuilder()
                        .encodedPath("/")
                        .query(null)
                        .fragment(null)
                        .build()
                        .toString()
                        .trimEnd('/')
                }
            }.getOrNull()
        }

    private suspend fun probeForwardDomains(seeds: List<String>): String? {
        val highest = seeds.mapNotNull(::domainNumber).maxOrNull() ?: return null
        for (offset in 1..MAX_DOMAIN_PROBE_STEPS) {
            val candidate = "https://dizipal" + (highest + offset) + ".com"
            verifyDiziPalBase(candidate)?.let { return it }
        }
        return null
    }

    private fun domainNumber(url: String): Int? =
        Regex("""dizipal(\d+)\.com""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun rememberDomain(value: String) {
        domainPrefs?.edit()?.putString(PREF_CURRENT_DOMAIN, value)?.apply()
    }

    private suspend fun refreshAdvertisedNextDomain(base: String) {
        val body = directPageRequest(base + "/", base + "/") ?: return
        val next = extractAdvertisedNextDomain(body, domainNumber(base)) ?: return
        domainPrefs?.edit()?.putString(PREF_NEXT_DOMAIN, next)?.apply()
    }

    internal fun extractAdvertisedNextDomain(
        body: String,
        currentNumber: Int? = null,
    ): String? {
        val candidates = Regex("""(?:https?://)?dizipal(\d+)\.com""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()
            }
            .filter { currentNumber == null || it > currentNumber }
            .distinct()
            .sortedDescending()
            .toList()
        return candidates.firstOrNull()?.let { "https://dizipal$it.com" }
    }

    private suspend fun resolveRedirectedBase(seed: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(seed)
                    .header("User-Agent", SITE_USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url
                    finalUrl.newBuilder()
                        .encodedPath("/")
                        .query(null)
                        .fragment(null)
                        .build()
                        .toString()
                        .trimEnd('/')
                }
            }.getOrNull()
        }

    private suspend fun directPageRequest(
        url: String,
        referer: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SITE_USER_AGENT)
                .header("Referer", referer)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                )
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private suspend fun siteRequest(
        url: String,
        referer: String,
        ajax: Boolean = false,
        noCache: Boolean = false,
    ): SiteResponse {
        val session = browser
            ?: throw IOException("DiziPal browser session is unavailable")
        val body = session.get(
            url = url,
            ajax = ajax,
            noCache = noCache,
        )
        return SiteResponse(
            body = body,
            headers = emptyMap(),
        )
    }

    private suspend fun execute(request: Request): SiteResponse =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val headers = response.headers.names().associateWith { name ->
                    response.headers.values(name)
                }
                if (!response.isSuccessful) {
                    if (response.code == 403 &&
                        response.header("Cf-Mitigated").equals("challenge", ignoreCase = true)
                    ) {
                        return@use SiteResponse(body, headers)
                    }
                    throw IOException("HTTP " + response.code + " for " + request.url)
                }
                SiteResponse(body, headers)
            }
        }

    private fun imageUrl(element: Element?, base: String): String? {
        element ?: return null

        fun usable(raw: String): String? =
            raw.trim()
                .takeIf { it.isNotBlank() }
                ?.takeUnless {
                    it.startsWith("data:image", ignoreCase = true) ||
                        it.equals("about:blank", ignoreCase = true) ||
                        it.contains("transparent.gif", ignoreCase = true) ||
                        it.contains("placeholder", ignoreCase = true)
                }

        fun fromSrcset(raw: String): String? =
            raw.split(',')
                .mapNotNull { candidate ->
                    usable(candidate.trim().split(Regex("""\s+"""), limit = 2).firstOrNull().orEmpty())
                }
                .lastOrNull()

        val picture = element.closest("picture")
        val raw = sequenceOf(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-original-src"),
            element.attr("data-image"),
            element.attr("data-url"),
            fromSrcset(element.attr("data-srcset")),
            fromSrcset(element.attr("srcset")),
            picture?.select("source")?.asSequence()
                ?.mapNotNull { source -> fromSrcset(source.attr("data-srcset")) ?: fromSrcset(source.attr("srcset")) }
                ?.lastOrNull(),
            element.attr("src"),
        ).mapNotNull { it?.let(::usable) }.firstOrNull()

        return raw?.let { absoluteFrom(base.ifBlank { FALLBACK_BASE_URL }, it) }
    }

    private suspend fun absolute(raw: String): String =
        absoluteFrom(baseUrl(), raw) ?: raw

    private fun absoluteFrom(base: String, raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:" + value
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { base.toHttpUrl().resolve(value)?.toString() }.getOrNull()
    }

    private fun normalizeExternalUrl(raw: String): String {
        val value = raw.replace("\\/", "/").trim()
        return when {
            value.startsWith("//") -> "https:" + value
            value.startsWith("://") -> "https" + value
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> "https://" + value.trimStart('/')
        }
    }

    private suspend fun <T> guarded(
        operation: String,
        block: suspend () -> T,
    ): ProviderResult<T> =
        try {
            ProviderResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.e(TAG, operation + " network failure: " + error.message, error)
            failure(operation, ProviderErrorCode.NETWORK, error.message ?: "Network error", error)
        } catch (error: Exception) {
            Log.e(TAG, operation + " parse failure: " + error.message, error)
            failure(operation, ProviderErrorCode.PARSE, error.message ?: "Parse error", error)
        }

    private fun failure(
        operation: String,
        code: ProviderErrorCode,
        message: String,
        cause: Throwable? = null,
    ): ProviderResult.Failure =
        ProviderResult.Failure(
            ProviderError(
                providerId = id,
                operation = operation,
                code = code,
                message = message,
                cause = cause,
            ),
        )

    companion object {
        private const val TAG = "DiziPalProvider"
        const val PROVIDER_ID = "dizipal"
        const val FALLBACK_BASE_URL = "https://dizipal1592.com"
        private const val DOMAIN_SOURCE =
            "https://raw.githubusercontent.com/aytzey/cs-kraptor/master/doms/eklenti_domainleri.txt"
        private const val HLS_MIME = "application/x-mpegURL"
        const val SITE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; SM-S938B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private const val EXTERNAL_USER_AGENT = SITE_USER_AGENT
        private const val DOMAIN_PREFS = "dizipal_provider"
        private const val PREF_CURRENT_DOMAIN = "current_domain"
        private const val PREF_NEXT_DOMAIN = "next_domain"
        private const val MAX_DOMAIN_PROBE_STEPS = 4
        private const val DOMAIN_CACHE_MILLIS = 30 * 60 * 1000L
        private const val PLAYBACK_CACHE_MILLIS = 90_000L
        private const val PLAYBACK_CACHE_SIZE = 8
        private const val CHANNEL_API_KEY = "c61f91c5141d178450934fe81c0a2029"
        private const val CHANNEL_API_VALUE =
            "MTc4NDQwNzIwMDhkMzJhNTc1YzUwOGU1ZjQwMjdjMjIyOWVjOGVhMTcwNGQyM2FjODM2YTI4YTU0NjUyMjI2ZmVjMzFkYzBkMWQyMWY4YzdiNA=="
    }
}
