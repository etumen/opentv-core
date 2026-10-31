/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.dizimom

import android.util.Log
import app.opentv.data.provider.*
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Provider implementation for DiziMom (TV series only).
 * Supports series catalog, pagination, search, season/episode resolution, and Pilavyer stream playback.
 */
class DiziMomProvider(
    private val http: OkHttpClient,
) : Provider {

    override val id: String = PROVIDER_ID
    override val name: String = "DiziMom"
    override val supportedMediaTypes: Set<ProviderMediaType> = setOf(ProviderMediaType.SERIES)

    private data class Section(
        val title: String,
        val basePath: String,
        val showOnHome: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var activeDomain: String = MAIN_URL

    private val sections = linkedMapOf(
        "series" to Section("Tüm Diziler", "/diziler", showOnHome = true),
        "popular" to Section("Popüler Diziler", "/trend", showOnHome = true),
        "episodes" to Section("Son Eklenen Bölümler", "/tum-bolumler", showOnHome = true),
        "aksiyon" to Section("Aksiyon", "/tur/aksiyon", showOnHome = false),
        "animasyon" to Section("Animasyon", "/tur/animasyon", showOnHome = false),
        "bilim-kurgu" to Section("Bilim Kurgu", "/tur/bilim-kurgu", showOnHome = false),
        "komedi" to Section("Komedi", "/tur/komedi", showOnHome = false),
        "korku" to Section("Korku", "/tur/korku", showOnHome = false),
        "dram" to Section("Dram", "/tur/dram", showOnHome = false),
        "gerilim" to Section("Gerilim", "/tur/gerilim", showOnHome = false),
        "suc" to Section("Suç", "/tur/suc", showOnHome = false),
        "romantik" to Section("Romantik", "/tur/romantik", showOnHome = false),
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

    override suspend fun discoverCatalogSections(
        mediaType: ProviderMediaType,
    ): ProviderResult<List<ProviderCatalogSection>> {
        if (mediaType != ProviderMediaType.SERIES) {
            return ProviderResult.Success(emptyList())
        }
        return ProviderResult.Success(catalogSections)
    }

    override suspend fun catalog(request: ProviderCatalogRequest): ProviderResult<ProviderCatalogPage> =
        guarded("catalog") {
            val sectionId = request.sectionId ?: "series"
            val section = sections[sectionId] ?: sections.getValue("series")
            if (sectionId == "episodes") {
                val items = parseLatestEpisodeSeries(fetchDocument(absolute("/")))
                return@guarded ProviderCatalogPage(
                    title = section.title,
                    items = items,
                    nextPage = null,
                )
            }
            val page = request.page.coerceAtLeast(1)

            val delimiter = if (section.basePath.contains("?")) "&" else "?"
            val url = absolute("${section.basePath}${delimiter}page=$page")

            val doc = fetchDocument(url)
            val items = parseCards(doc)
            // /tur/* ignores page=N and repeats the first 24 titles.
            // Only explicit page navigation can unlock further genre pages.
            val hasMore = parseHasNextPage(doc, page) ||
                (!section.basePath.startsWith("/tur/") && items.size >= 12 && page < 50)
            val nextPage = if (items.isNotEmpty() && hasMore) page + 1 else null

            Log.d(TAG, "catalog section=${section.title} page=$page items=${items.size} hasMore=$hasMore url=$url")
            ProviderCatalogPage(
                title = section.title,
                items = items,
                nextPage = nextPage,
            )
        }

    override suspend fun search(request: ProviderSearchRequest): ProviderResult<List<ProviderItem>> =
        guarded("search") {
            val query = request.query.trim()
            if (query.isBlank()) return@guarded emptyList()
            val url = absolute("/?s=" + URLEncoder.encode(query, "UTF-8"))
            val doc = fetchDocument(url)
            parseCards(doc)
        }

    override suspend fun load(itemId: String): ProviderResult<ProviderDetails> =
        guarded("load") {
            val url = absolute(itemId)
            val doc = fetchDocument(url)

            val titleEl = doc.selectFirst("h1")
                ?: throw IllegalArgumentException("Series title not found: $url")
            val title = titleEl.text().trim()
                .removeSuffix(" izle").removeSuffix(" İzle")
                .replace(Regex("""\(\d{4}\)"""), "")
                .trim()

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: parseImageUrl(doc.selectFirst("img[src*='tmdb'], img[data-src*='tmdb'], .cover img, img"))

            val description = doc.selectFirst("div.overview, div.summary, meta[property=og:description]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }

            val year = Regex("""\b(19|20)\d{2}\b""").find(titleEl.text())?.value?.toIntOrNull()
                ?: Regex("""\b(19|20)\d{2}\b""").find(doc.text())?.value?.toIntOrNull()

            val rating = doc.selectFirst("span.rating, .score, .imdb")?.text()
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

            val genres = doc.select("a[href*='/tur/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val cast = doc.select("a[href*='/oyuncu/'], .oyuncular a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val director = doc.selectFirst("a[href*='/yonetmen/'], .yonetmen a")?.text()?.trim()

            ProviderDetails(
                item = ProviderItem(
                    providerId = id,
                    id = url,
                    title = title,
                    mediaType = ProviderMediaType.SERIES,
                    posterUrl = poster,
                    backdropUrl = poster,
                    description = description,
                    year = year,
                    rating = rating,
                    genres = genres,
                ),
                description = description,
                cast = cast,
                director = director,
            )
        }

    override suspend fun episodes(seriesId: String): ProviderResult<List<ProviderEpisode>> =
        guarded("episodes") {
            val url = absolute(seriesId)
            val doc = fetchDocument(url)

            val episodesList = mutableListOf<ProviderEpisode>()
            val episodeLinks = doc.select("a[href*='/sezon-']")

            for (link in episodeLinks) {
                val epHref = link.attr("href").trim()
                if (epHref.isBlank()) continue

                val epUrl = absolute(epHref)
                val m = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(epUrl)
                val season = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = m?.groupValues?.get(2)?.toIntOrNull() ?: 1

                val rawText = link.text().trim()
                val epTitle = if (rawText.isNotBlank()) rawText else "$season. Sezon $epNum. Bölüm"

                episodesList.add(
                    ProviderEpisode(
                        providerId = id,
                        seriesId = url,
                        id = epUrl,
                        season = season,
                        episodeNumber = epNum,
                        title = epTitle,
                    ),
                )
            }

            // Deduplicate by season & episodeNumber and sort
            episodesList.distinctBy { "${it.season}x${it.episodeNumber}" }
                .sortedWith(compareBy({ it.season }, { it.episodeNumber }))
        }

    override suspend fun streams(target: ProviderPlaybackTarget): ProviderResult<List<ProviderStream>> {
        val episode = target as? ProviderPlaybackTarget.Episode
            ?: return failure("streams", ProviderErrorCode.UNSUPPORTED, "Episode target required for DiziMom")

        return guarded("streams") {
            resolveStreams(absolute(episode.episodeId))
        }
    }

    override suspend fun subtitles(target: ProviderPlaybackTarget): ProviderResult<List<ProviderSubtitle>> {
        val episode = target as? ProviderPlaybackTarget.Episode
            ?: return failure("subtitles", ProviderErrorCode.UNSUPPORTED, "Episode target required for DiziMom")

        return guarded("subtitles") {
            resolveSubtitles(absolute(episode.episodeId))
        }
    }

    private suspend fun resolveStreams(episodeUrl: String): List<ProviderStream> {
        val doc = fetchDocument(episodeUrl)
        val dataPv = doc.selectFirst("[data-pv]")?.attr("data-pv")?.trim()
            ?: Regex("""data-pv=["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)

        if (dataPv.isNullOrBlank()) {
            return emptyList()
        }

        // Script domain
        val playerDomain = "https://play2.pilavyerplay.top"
        val playerUrl = "$playerDomain/assets/js/s.php?s=${URLEncoder.encode(dataPv, "UTF-8")}"

        val playerReq = Request.Builder()
            .url(playerUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", activeDomain + "/")
            .build()

        val playerHtml = executeHttp(playerReq)
        val playerJson = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(playerHtml)?.groupValues?.get(1)

        val streams = mutableListOf<ProviderStream>()
        if (playerJson != null) {
            val root = json.parseToJsonElement(playerJson).jsonObject
            val streamUrl = root["stream"]?.jsonPrimitive?.content
            if (!streamUrl.isNullOrBlank()) {
                streams.add(
                    ProviderStream(
                        providerId = id,
                        url = streamUrl,
                        label = "DiziMom 1080p (HLS)",
                        quality = "1080p",
                        mimeType = "application/vnd.apple.mpegurl",
                        headers = mapOf(
                            "Referer" to playerUrl,
                            "Origin" to activeDomain,
                            "User-Agent" to USER_AGENT,
                        ),
                    ),
                )
            }
        }

        return streams
    }

    private suspend fun resolveSubtitles(episodeUrl: String): List<ProviderSubtitle> {
        val doc = fetchDocument(episodeUrl)
        val dataPv = doc.selectFirst("[data-pv]")?.attr("data-pv")?.trim()
            ?: Regex("""data-pv=["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)

        if (dataPv.isNullOrBlank()) return emptyList()

        val playerDomain = "https://play2.pilavyerplay.top"
        val playerUrl = "$playerDomain/assets/js/s.php?s=${URLEncoder.encode(dataPv, "UTF-8")}"

        val playerReq = Request.Builder()
            .url(playerUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", activeDomain + "/")
            .build()

        val playerHtml = executeHttp(playerReq)
        val playerJson = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(playerHtml)?.groupValues?.get(1)
            ?: return emptyList()

        val subs = mutableListOf<ProviderSubtitle>()
        val root = json.parseToJsonElement(playerJson).jsonObject
        val subsArray = root["subs"]?.jsonArray
        if (subsArray != null) {
            for (subElem in subsArray) {
                val subObj = subElem.jsonObject
                val subSrc = subObj["src"]?.jsonPrimitive?.content ?: continue
                val lang = subObj["lang"]?.jsonPrimitive?.content ?: "tr"
                val label = subObj["label"]?.jsonPrimitive?.content ?: lang

                subs.add(
                    ProviderSubtitle(
                        providerId = id,
                        url = subSrc,
                        label = label,
                        language = lang,
                    ),
                )
            }
        }

        return subs
    }

    /** Latest episodes are displayed on the live home page, not /tum-bolumler. */
    internal fun parseLatestEpisodeSeries(doc: Document): List<ProviderItem> =
        doc.select("a[href*='/dizi/'][href*='/sezon-'][href*='/bolum-']").mapNotNull { anchor ->
            val source = absolute(anchor.attr("href"))
            val slug = Regex("""/dizi/([^/]+)/sezon-\d+/bolum-\d+""")
                .find(source)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val title = image?.attr("alt")?.trim()
                ?.replace(Regex("""\s+\d+\.\s*Sezon\s+\d+\.\s*Bölüm.*$""", RegexOption.IGNORE_CASE), "")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProviderItem(
                providerId = id,
                id = absolute("/dizi/$slug"),
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = parseImageUrl(image),
            )
        }.distinctBy { it.id }

    internal fun parseCards(doc: Document): List<ProviderItem> {
        val items = mutableListOf<ProviderItem>()
        val links = doc.select("a[href*='/dizi/']")

        for (a in links) {
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") continue

            // Must match /dizi/{slug} and not be an episode /sezon- link or generic navigation
            val fullUrl = absolute(href)
            val path = fullUrl.substringAfter("https://").substringAfter("/")
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.size != 2 || parts[0] != "dizi") continue

            val title = a.selectFirst(".title, h2, h3, h4, span.font-semibold")?.text()?.trim()
                ?: a.text().split("\n").firstOrNull { it.trim().length > 2 && !it.contains("HD") && !it.contains("Dizi") }?.trim()
                ?: parts[1].replace("-", " ").replaceFirstChar { it.uppercase() }

            if (title.isBlank()) continue

            val poster = parseImageUrl(a.selectFirst("img"))
            val rating = a.selectFirst("span.rating, .score, span:containsOwn('.')")?.text()
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

            items.add(
                ProviderItem(
                    providerId = id,
                    id = fullUrl,
                    title = title,
                    mediaType = ProviderMediaType.SERIES,
                    posterUrl = poster,
                    rating = rating,
                ),
            )
        }
        return items.distinctBy { it.id }
    }

    internal fun parseHasNextPage(doc: Document, currentPage: Int): Boolean {
        val pageLinks = doc.select("a[href*='page=']")
        for (link in pageLinks) {
            val pageNum = Regex("""page=(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            if (pageNum != null && pageNum > currentPage) {
                return true
            }
        }
        return false
    }

    private fun parseImageUrl(img: Element?): String? {
        if (img == null) return null
        val dataSrc = img.attr("data-src").trim()
        if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:")) {
            return absolute(dataSrc)
        }
        val src = img.attr("src").trim()
        if (src.isNotBlank() && !src.startsWith("data:")) {
            return absolute(src)
        }
        return null
    }

    private fun absolute(path: String): String {
        val trimmed = path.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val base = activeDomain.trimEnd('/')
        return if (trimmed.startsWith("/")) "$base$trimmed" else "$base/$trimmed"
    }

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchText(url), url)

    private suspend fun fetchText(url: String): String =
        executeHttp(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", activeDomain + "/")
                .build(),
        )

    private suspend fun executeHttp(request: Request): String =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for ${request.url}")
                }
                response.body?.string().orEmpty()
            }
        }

    private inline fun <T> guarded(operation: String, block: () -> T): ProviderResult<T> =
        try {
            ProviderResult.Success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "[$id] $operation failed: ${e.message}", e)
            failure(operation, ProviderErrorCode.NETWORK, e.message ?: "Unknown error", e)
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
        const val PROVIDER_ID = "dizimom"
        private const val TAG = "DiziMomProvider"
        private const val MAIN_URL = "https://ddizimom.net"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
