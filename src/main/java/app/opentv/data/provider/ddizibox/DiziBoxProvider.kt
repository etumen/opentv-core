/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.ddizibox

import android.util.Log
import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogPage
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
import app.opentv.data.provider.ProviderError
import app.opentv.data.provider.ProviderErrorCode
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderSearchRequest
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Native DiziBox series provider.
 *
 * Catalogue/detail pages are normal HTML. Episode pages expose an opaque player token which is
 * exchanged for the provider's own HLS URL and subtitle URLs. No browser/WebView runtime is
 * required by this provider.
 */
class DiziBoxProvider(
    private val http: OkHttpClient,
) : Provider {
    override val id: String = PROVIDER_ID
    override val name: String = "DiziBox"
    override val supportedMediaTypes: Set<ProviderMediaType> = setOf(ProviderMediaType.SERIES)

    private data class Section(
        val title: String,
        val path: String,
        val showOnHome: Boolean = true,
    )

    internal data class Playback(
        val streams: List<ProviderStream>,
        val subtitles: List<ProviderSubtitle>,
    )

    private data class CachedPlayback(
        val createdAtMillis: Long,
        val value: Playback,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val playbackCache = linkedMapOf<String, CachedPlayback>()

    private val sections = linkedMapOf(
        "latest" to Section("Diziler", "/diziler"),
        "action" to Section("Aksiyon", "/tur/aksiyon"),
        "crime" to Section("Suç", "/tur/suc", showOnHome = false),
        "drama" to Section("Dram", "/tur/dram", showOnHome = false),
        "netflix" to Section("Netflix", "/platform/netflix"),
        "prime" to Section("Amazon Prime", "/platform/amazon-prime"),
        "disney" to Section("Disney+", "/platform/disney"),
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
        val section = sections[request.sectionId ?: "latest"] ?: sections.getValue("latest")
        val page = request.page.coerceAtLeast(1)
        val base = absolute(section.path)
        val target = if (page == 1) {
            base
        } else {
            base.toHttpUrl().newBuilder()
                .setQueryParameter("page", page.toString())
                .build()
                .toString()
        }

        val doc = fetchDocument(target)
        val items = parseSeriesCards(doc)
        // /tur/* pages currently ignore ?page=N. Never advertise a second
        // genre page unless the website itself links to that next page.
        val genreNext = doc.select("a[href*='page=']").any { anchor ->
            Regex("""[?&]page=([0-9]+)""").find(anchor.attr("href"))
                ?.groupValues?.get(1)?.toIntOrNull() == page + 1
        }
        ProviderCatalogPage(
            title = section.title,
            items = items,
            nextPage = if (items.isNotEmpty() && (!section.path.startsWith("/tur/") || genreNext)) page + 1 else null,
        )
    }

    override suspend fun search(
        request: ProviderSearchRequest,
    ): ProviderResult<List<ProviderItem>> = guarded("search") {
        val query = request.query.trim()
        if (query.isBlank()) {
            emptyList()
        } else {
            val target = MAIN_URL.toHttpUrl().newBuilder()
                .addPathSegment("ara")
                .addQueryParameter("q", query)
                .build()
                .toString()
            parseSeriesCards(fetchDocument(target))
        }
    }

    override suspend fun load(
        itemId: String,
    ): ProviderResult<ProviderDetails> = guarded("load") {
        val url = absolute(itemId)
        parseDetails(fetchDocument(url), url)
            ?: throw IllegalArgumentException("DiziBox series details not found")
    }

    override suspend fun episodes(
        seriesId: String,
    ): ProviderResult<List<ProviderEpisode>> = guarded("episodes") {
        val url = absolute(seriesId)
        val firstDocument = fetchDocument(url)

        val seasonUrls = linkedMapOf<Int, String>()
        seasonUrls[1] = url
        firstDocument.select("a[href*='?sezon=']").forEach { anchor ->
            val season = Regex("""(?:\?|&)sezon=(\d+)""")
                .find(anchor.attr("href"))
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE)
                    .find(anchor.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            val resolved = absoluteFrom(url, anchor.attr("href").substringBefore('#'))
            if (season != null && resolved != null) seasonUrls[season] = resolved
        }

        seasonUrls.toSortedMap().flatMap { (season, seasonUrl) ->
            val document =
                if (season == 1 && seasonUrl.substringBefore('?') == url.substringBefore('?')) {
                    firstDocument
                } else {
                    fetchDocument(seasonUrl)
                }
            parseEpisodes(document, url)
        }.distinctBy { it.id }
            .sortedWith(compareBy<ProviderEpisode> { it.season }.thenBy { it.episodeNumber })
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

    internal fun parseSeriesCards(doc: Document): List<ProviderItem> =
        doc.select("a[href*='/dizi/']").mapNotNull { anchor ->
            val href = absoluteFrom(doc.baseUri().ifBlank { MAIN_URL }, anchor.attr("href"))
                ?: return@mapNotNull null
            val url = runCatching { href.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
            val path = url.encodedPath.trimEnd('/')
            if (!Regex("""^/dizi/[^/]+$""").matches(path)) return@mapNotNull null

            val title = anchor.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("img")?.attr("alt")
                    ?.cleanCardTitle()
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = imageUrl(anchor.selectFirst("img"), doc.baseUri())
            val year = Regex("""\b(19|20)\d{2}\b""")
                .find(anchor.text())
                ?.value
                ?.toIntOrNull()
            val rating = anchor.selectFirst(".badge-rating")?.text()
                ?.replace(Regex("""[^0-9,.]"""), "")
                ?.replace(',', '.')
                ?.toDoubleOrNull()

            ProviderItem(
                providerId = id,
                id = href,
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = poster,
                year = year,
                rating = rating,
            )
        }.distinctBy { it.id }

    internal fun parseDetails(
        doc: Document,
        seriesId: String,
    ): ProviderDetails? {
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = imageUrl(
            doc.selectFirst("main img[alt='$title'], main img[alt]"),
            doc.baseUri(),
        ) ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { absoluteFrom(doc.baseUri(), it) }
        val backdrop = doc.selectFirst("main section.relative > div.absolute img")?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { absoluteFrom(doc.baseUri(), it) }
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.select("main p").asSequence()
                .map { it.text().trim() }
                .firstOrNull { it.length > 80 }

        fun detailValue(label: String): String? =
            doc.select("dl > div").firstOrNull { row ->
                row.selectFirst("dt")?.text()?.trim()?.equals(label, ignoreCase = true) == true
            }?.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotBlank() }

        val year = detailValue("Yapım Yılı")?.toIntOrNull()
        val rating = detailValue("IMDB Puanı")
            ?.replace(Regex("""[^0-9,.]"""), "")
            ?.replace(',', '.')
            ?.toDoubleOrNull()
        val genres = doc.select("main a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return ProviderDetails(
            item = ProviderItem(
                providerId = id,
                id = seriesId,
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = poster,
                backdropUrl = backdrop,
                description = description,
                year = year,
                rating = rating,
                genres = genres,
            ),
            description = description,
        )
    }

    internal fun parseEpisodes(
        doc: Document,
        seriesId: String,
    ): List<ProviderEpisode> =
        doc.select("section#bolumler a[href*='/sezon-'][href*='/bolum-']")
            .mapNotNull { anchor ->
                val href = absoluteFrom(doc.baseUri().ifBlank { MAIN_URL }, anchor.attr("href"))
                    ?: return@mapNotNull null
                val match = Regex("""/sezon-(\d+)/bolum-(\d+)(?:[/?#].*)?$""")
                    .find(href)
                    ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val title = anchor.selectFirst("p.mt-2")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.select("p").firstOrNull()?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: "Bölüm $episode"
                val still = imageUrl(anchor.selectFirst("img"), doc.baseUri())

                ProviderEpisode(
                    providerId = id,
                    seriesId = seriesId,
                    id = href,
                    season = season,
                    episodeNumber = episode,
                    title = title,
                    stillUrl = still,
                )
            }.distinctBy { it.id }

    internal fun parsePlayerConfig(
        html: String,
        playerUrl: String,
    ): Playback {
        val raw = Regex(
            """window\.__PLAYER__\s*=\s*(\{.*?\})\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("DiziBox player config not found")

        val root = json.parseToJsonElement(raw).jsonObject
        val stream = root["stream"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("http") }
            ?: throw IllegalArgumentException("DiziBox HLS stream not found")

        val playerOrigin = runCatching {
            val u = playerUrl.toHttpUrl()
            u.scheme + "://" + u.host
        }.getOrDefault(PLAYER_ORIGIN)

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to playerUrl,
            "Origin" to playerOrigin,
            "Accept" to "*/*",
        )

        val streams = listOf(
            ProviderStream(
                providerId = id,
                url = stream,
                label = "DiziBox",
                mimeType = HLS_MIME,
                headers = headers,
            ),
        )

        val subtitles = root["subs"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val url = obj["src"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Altyazı"
            val language = obj["lang"]?.jsonPrimitive?.contentOrNull
            ProviderSubtitle(
                providerId = id,
                url = url,
                label = label,
                language = language,
                mimeType = "text/vtt",
            )
        }.distinctBy { it.url }

        return Playback(streams = streams, subtitles = subtitles)
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
            val episodeDoc = fetchDocument(episodeUrl)
            val token = episodeDoc.selectFirst("[data-pv]")?.attr("data-pv")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("DiziBox player token not found")
            val playerOrigin = resolvePlayerOrigin(episodeDoc)
            val playerUrl = playerOrigin.toHttpUrl().newBuilder()
                .addPathSegments("assets/js/s.php")
                .addQueryParameter("s", token)
                .build()
                .toString()
            val playerHtml = fetchText(playerUrl, referer = episodeUrl)
            parsePlayerConfig(playerHtml, playerUrl)
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

    internal fun resolvePlayerOrigin(doc: Document): String {
        val scriptUrl = doc.selectFirst(
            "script[src*='pilavyerplay'][src*='core.js'], " +
                "script[src*='pilavyerplay'][src*='/e/c.js']",
        )?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { absoluteFrom(doc.baseUri().ifBlank { MAIN_URL }, it) }

        return scriptUrl?.let { url ->
            runCatching {
                val parsed = url.toHttpUrl()
                parsed.scheme + "://" + parsed.host
            }.getOrNull()
        } ?: PLAYER_ORIGIN
    }

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchText(url, referer = MAIN_URL + "/"), url)

    private suspend fun fetchText(
        url: String,
        referer: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)

        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code + " for " + response.request.url)
            }
            body
        }
    }

    private fun imageUrl(element: Element?, base: String): String? {
        element ?: return null
        val raw = sequenceOf(
            element.attr("data-src"),
            element.attr("src"),
            element.attr("srcset").split(',').lastOrNull()?.trim()?.substringBefore(' '),
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        return raw?.let { absoluteFrom(base.ifBlank { MAIN_URL }, it) }
    }

    private fun String.cleanCardTitle(): String =
        replace(Regex("""\s+(?:Dizi\s+izle|izle)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun absolute(raw: String): String =
        absoluteFrom(MAIN_URL, raw) ?: raw

    private fun absoluteFrom(base: String, raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:" + value
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { base.toHttpUrl().resolve(value)?.toString() }.getOrNull()
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

    private fun <T> failure(
        operation: String,
        code: ProviderErrorCode,
        message: String,
        cause: Throwable? = null,
    ): ProviderResult<T> =
        ProviderResult.Failure(
            ProviderError(
                providerId = id,
                operation = operation,
                code = code,
                message = message,
                cause = cause,
            ),
        )

    private companion object {
        const val PROVIDER_ID = "ddizibox"
        const val MAIN_URL = "https://ddizibox.com"
        const val PLAYER_ORIGIN = "https://play2.pilavyerplay.top"
        const val HLS_MIME = "application/x-mpegURL"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val PLAYBACK_CACHE_MILLIS = 5 * 60 * 1000L
        const val PLAYBACK_CACHE_SIZE = 24
        const val TAG = "DiziBoxProvider"
    }
}
