/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.filmmakinesi

import android.util.Log
import app.opentv.data.provider.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

class FilmMakinesiProvider(private val http: OkHttpClient) : Provider {
    override val id = PROVIDER_ID
    override val name = "FilmMakinesi"
    override val supportedMediaTypes = setOf(ProviderMediaType.MOVIE)

    private data class Section(val title: String, val path: String, val showOnHome: Boolean)

    internal data class PaginationConfig(
        val pages: Int,
        val action: String,
        val sort: String? = null,
        val search: String? = null,
    )

    private data class Playback(
        val streams: List<ProviderStream>,
        val subtitles: List<ProviderSubtitle>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fallbackSections = linkedMapOf(
        "latest" to Section("Film Arşivi", "/film-arsivi/", true),
        "popular" to Section("Popüler Filmler", "/populer-filmler/", true),
        "dubbed" to Section("Türkçe Dublaj", "/turkce-dublaj-filmler/", true),
        "subtitled" to Section("Türkçe Altyazılı", "/turkce-altyazili-filmler/", true),
        "action" to Section("Aksiyon", "/aksiyon-filmleri-hd-izle/", true),
        "family" to Section("Aile", "/aile-filmleri-hd-izle/", false),
        "animation" to Section("Animasyon", "/animasyon-filmleri-hd-izle/", false),
        "documentary" to Section("Belgesel", "/belgelsel-filmleri-hd-izle/", false),
        "scifi" to Section("Bilim Kurgu", "/bilim-kurgu-filmleri-hd-izle/", false),
        "drama" to Section("Dram", "/dram-filmleri-hd-izle/", false),
        "fantasy" to Section("Fantastik", "/fantastik-filmleri-hd-izle/", false),
        "thriller" to Section("Gerilim", "/gerilim-filmleri-hd-izle/", false),
        "mystery" to Section("Gizem", "/gizem-filmleri-hd-izle/", false),
        "horror" to Section("Korku", "/korku-filmleri-hd-izle/", false),
        "adventure" to Section("Macera", "/macera-filmleri-hd-izle/", false),
        "music" to Section("Müzik", "/muzik-filmleri-hd-izle/", false),
        "romance" to Section("Romantik", "/romantik-filmleri-hd-izle/", false),
        "war" to Section("Savaş", "/savas-filmleri-hd-izle/", false),
        "crime" to Section("Suç", "/suc-filmleri-hd-izle/", false),
        "history" to Section("Tarih", "/tarih-filmleri-hd-izle/", false),
        "western" to Section("Vahşi Batı", "/vahsi-bati-filmleri-hd-izle/", false),
        "local" to Section("Yerli Filmler", "/yerli-filmleri-hd-izle/", false),
        "indian" to Section("Hint Filmleri", "/hint-filmleri/", false),
    )
    @Volatile private var discoveredSections: Map<String, Section>? = null
    private val paginationBySection = ConcurrentHashMap<String, PaginationConfig>()
    private fun sections(): Map<String, Section> = discoveredSections ?: fallbackSections

    override val catalogSections: List<ProviderCatalogSection>
        get() = sections().map { (sectionId, section) ->
            ProviderCatalogSection(
                id = sectionId,
                title = section.title,
                mediaType = ProviderMediaType.MOVIE,
                showOnHome = section.showOnHome,
            )
        }

    override suspend fun discoverCatalogSections(
        mediaType: ProviderMediaType,
    ): ProviderResult<List<ProviderCatalogSection>> {
        if (mediaType != ProviderMediaType.MOVIE) return ProviderResult.Success(emptyList())
        discoveredSections?.let { return ProviderResult.Success(catalogSections) }
        return guarded("catalogSections") {
            val found = parseCategoryLinks(fetchDocument(MAIN_URL))
            if (found.size >= 4) discoveredSections = mergeSections(found)
            catalogSections
        }
    }

    override suspend fun catalog(request: ProviderCatalogRequest): ProviderResult<ProviderCatalogPage> =
        guarded("catalog") {
            val sectionId = request.sectionId ?: "latest"
            val section = sections()[sectionId] ?: fallbackSections.getValue("latest")
            val page = request.page.coerceAtLeast(1)
            val base = absolute(section.path)

            val config: PaginationConfig?
            val target: String
            if (page == 1) {
                target = base
                config = null
            } else {
                config = paginationBySection[sectionId]
                    ?: parsePagination(fetchDocument(base))?.also {
                        paginationBySection[sectionId] = it
                    }
                if (config == null || page > config.pages) {
                    return@guarded ProviderCatalogPage(
                        title = section.title,
                        items = emptyList(),
                        nextPage = null,
                    )
                }
                target = paginationUrl(base, page, config)
            }

            val doc = fetchDocument(target)
            val effectiveConfig = if (page == 1) {
                parsePagination(doc)?.also { paginationBySection[sectionId] = it }
            } else {
                config
            }
            val items = parseCards(doc)
            val nextPage = if (
                items.isNotEmpty() &&
                effectiveConfig != null &&
                page < effectiveConfig.pages
            ) {
                page + 1
            } else {
                null
            }

            Log.d(
                TAG,
                "catalog section=${section.title} page=$page items=${items.size} " +
                    "pages=${effectiveConfig?.pages ?: 1} url=$target",
            )
            ProviderCatalogPage(
                title = section.title,
                items = items,
                nextPage = nextPage,
            )
        }

    override suspend fun search(request: ProviderSearchRequest): ProviderResult<List<ProviderItem>> =
        guarded("search") {
            val query = request.query.trim()
            if (query.isBlank()) emptyList()
            else {
                val url = MAIN_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .build().toString()
                parseCards(fetchDocument(url))
            }
        }
    override suspend fun load(itemId: String): ProviderResult<ProviderDetails> =
        guarded("load") {
            val url = absolute(itemId)
            val doc = fetchDocument(url)
            val titleElement = doc.selectFirst("h1.section-title, h1.title")
                ?: throw IllegalArgumentException("Movie title not found")
            val title = titleElement.ownText().trim().removeSuffix(" izle").trim()
                .ifBlank { titleElement.text().replace(Regex("""\(\d{4}\)"""), "").trim().removeSuffix(" izle").trim() }
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: imageUrl(doc.selectFirst("#info--box .cover img"))
            val year = Regex("""\b(19|20)\d{2}\b""")
                .find(titleElement.text())?.value?.toIntOrNull()
            val rating = doc.selectFirst(".post-info-imdb-rating span, .imdb b")?.text()
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
            val genres = doc.select("a[href*='filmleri-hd-izle'], #info--box .content .type a")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".info-description")?.text()?.trim()
            val director = doc.selectFirst(".director a")?.text()?.trim()
            val cast = doc.select("#cast .cast-name")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            val durationSeconds = doc.selectFirst(".time")?.text()
                ?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()?.times(60)
            ProviderDetails(
                item = ProviderItem(
                    providerId = id,
                    id = url,
                    title = title,
                    mediaType = ProviderMediaType.MOVIE,
                    posterUrl = poster,
                    description = description,
                    year = year,
                    rating = rating,
                    genres = genres,
                ),
                description = description,
                cast = cast,
                director = director,
                durationSeconds = durationSeconds,
            )
        }

    override suspend fun episodes(seriesId: String): ProviderResult<List<ProviderEpisode>> =
        failure("episodes", ProviderErrorCode.UNSUPPORTED, "FilmMakinesi series support is not enabled yet")

    override suspend fun streams(target: ProviderPlaybackTarget): ProviderResult<List<ProviderStream>> {
        val movie = target as? ProviderPlaybackTarget.Movie
            ?: return failure("streams", ProviderErrorCode.UNSUPPORTED, "Movie target required")
        return when (val result = resolvePlayback(absolute(movie.itemId))) {
            is ProviderResult.Success -> ProviderResult.Success(result.value.streams)
            is ProviderResult.Failure -> result
        }
    }

    override suspend fun subtitles(target: ProviderPlaybackTarget): ProviderResult<List<ProviderSubtitle>> {
        val movie = target as? ProviderPlaybackTarget.Movie
            ?: return failure("subtitles", ProviderErrorCode.UNSUPPORTED, "Movie target required")
        return when (val result = resolvePlayback(absolute(movie.itemId))) {
            is ProviderResult.Success -> ProviderResult.Success(result.value.subtitles)
            is ProviderResult.Failure -> result
        }
    }

    private suspend fun resolvePlayback(movieUrl: String): ProviderResult<Playback> =
        guarded("playback") {
            val doc = Jsoup.parse(fetchText(movieUrl, MAIN_URL), movieUrl)
            val embeds = buildList {
                addAll(
                    doc.select(".video-parts a[data-video_url]")
                        .map { it.attr("data-video_url").trim() }
                        .filter { it.isNotBlank() },
                )
                addAll(
                    doc.select("#cn-content iframe, .video-player-container-here iframe, .after-player iframe")
                        .map {
                            it.attr("data-src").ifBlank { it.attr("src") }.trim()
                        }
                        .filter { it.isNotBlank() },
                )
            }.mapNotNull { absoluteFrom(movieUrl, it) }.distinct()

            val streams = mutableListOf<ProviderStream>()
            val subtitles = mutableListOf<ProviderSubtitle>()

            embeds.forEachIndexed { index, embed ->
                val resolved = if (embed.contains("oynatloload", true)) {
                    resolveUltraEmbed(embed, movieUrl)
                } else {
                    resolveLegacyEmbed(embed, movieUrl, index)
                }
                streams += resolved.streams
                subtitles += resolved.subtitles
            }

            Playback(
                streams = streams.distinctBy { it.url },
                subtitles = subtitles.distinctBy { it.url },
            )
        }

    private suspend fun resolveUltraEmbed(embed: String, movieUrl: String): Playback {
        val html = fetchText(embed, movieUrl)
        val videoId = Regex("""/embed/(\d+)""").find(embed)?.groupValues?.getOrNull(1)
            ?: return Playback(emptyList(), emptyList())
        val eauth = Regex(
            """window\.__ULTRA_EAUTH\s*=\s*["']([^"']+)["']""",
        ).find(html)?.groupValues?.getOrNull(1)
            ?: return Playback(emptyList(), emptyList())

        val parsed = embed.toHttpUrl()
        val apiUrl = parsed.newBuilder()
            .encodedPath("/api/video-bilgi/$videoId")
            .query(null)
            .addQueryParameter("eauth", eauth)
            .build()
            .toString()
        val payload = json.parseToJsonElement(
            fetchText(apiUrl, embed, accept = "application/json"),
        ).jsonObject

        val candidates = mutableListOf<Pair<String, String>>()
        payload["src"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("http") }
            ?.let { candidates += "Otomatik" to it }

        payload["sources"]?.jsonArray?.forEach { entry ->
            val obj = entry.jsonObject
            val url = obj["file"]?.jsonPrimitive?.contentOrNull
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "Kalite"
            if (!url.isNullOrBlank() && url.startsWith("http")) {
                candidates += label to url
            }
        }

        val headers = mapOf(
            "Referer" to embed,
            "Origin" to (parsed.scheme + "://" + parsed.host),
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
        )
        val streams = candidates.distinctBy { it.second }.map { (label, url) ->
            ProviderStream(
                providerId = id,
                url = url,
                label = "FilmMakinesi · $label",
                quality = label.takeIf { it != "Otomatik" },
                mimeType = HLS_MIME,
                headers = headers,
            )
        }

        val subtitles = mutableListOf<ProviderSubtitle>()
        payload["subtitle"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("http") }
            ?.let { url ->
                subtitles += ProviderSubtitle(
                    providerId = id,
                    url = url,
                    label = "Türkçe",
                    language = "tr",
                    mimeType = if (url.contains(".vtt", true)) "text/vtt" else null,
                )
            }

        return Playback(streams, subtitles)
    }

    private suspend fun resolveLegacyEmbed(
        embed: String,
        movieUrl: String,
        index: Int,
    ): Playback {
        val html = runCatching { fetchText(embed, movieUrl) }.getOrNull()
            ?: return Playback(emptyList(), emptyList())
        val streamUrl = when {
            embed.contains("rapid.filmmakinesi", true) ->
                FilmMakinesiCodec.extractRapidStream(html)
            embed.contains("closeload.filmmakinesi", true) ->
                FilmMakinesiCodec.extractCloseLoadStream(html)
            else -> FilmMakinesiCodec.extractRapidStream(html)
                ?: FilmMakinesiCodec.extractCloseLoadStream(html)
        } ?: return Playback(emptyList(), emptyList())

        val parsed = embed.toHttpUrl()
        val headers = mapOf(
            "Referer" to embed,
            "Origin" to (parsed.scheme + "://" + parsed.host),
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
        )
        val stream = ProviderStream(
            providerId = id,
            url = streamUrl,
            label = if (index == 0) "FilmMakinesi" else "FilmMakinesi " + (index + 1),
            mimeType = HLS_MIME,
            headers = headers,
        )
        val subs = FilmMakinesiCodec.extractSubtitles(html, embed).map { sub ->
            ProviderSubtitle(
                providerId = id,
                url = sub.url,
                label = sub.label,
                language = sub.language,
                mimeType = if (sub.url.contains(".vtt", true)) "text/vtt" else null,
            )
        }
        return Playback(listOf(stream), subs)
    }

    internal fun parseCards(doc: Document): List<ProviderItem> =
        doc.select("a.poster, a.item").mapNotNull { element ->
            val href = absoluteFrom(doc.baseUri().ifBlank { MAIN_URL }, element.attr("href"))
                ?: return@mapNotNull null
            if (href.contains("/dizi/", true)) return@mapNotNull null

            val title = element.selectFirst(".poster-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: element.attr("data-title").trim().takeIf { it.isNotBlank() }
                ?: element.selectFirst(".item-footer .title")?.text()?.trim()
                ?: element.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null

            val poster = imageUrl(
                element.selectFirst(".poster-wrapper img, .thumbnail-outer img, img"),
            )
            val metaText = element.selectFirst(".poster-meta")?.text().orEmpty()
            val yearPattern = Regex("""\b(?:19|20)\d{2}\b""")
            val year = yearPattern.find(metaText)?.value?.toIntOrNull()
                ?: yearPattern.find(element.text())?.value?.toIntOrNull()
                ?: yearPattern.find(element.html())?.value?.toIntOrNull()
                ?: element.selectFirst(".item-footer .info span")?.text()?.trim()?.toIntOrNull()
            val rating = element.selectFirst(".poster-meta .imdb")?.text()
                ?.replace(',', '.')
                ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value }
                ?.toDoubleOrNull()
                ?: element.attr("data-score").replace(',', '.').toDoubleOrNull()

            ProviderItem(
                providerId = id,
                id = href,
                title = title,
                mediaType = ProviderMediaType.MOVIE,
                posterUrl = poster,
                year = year,
                rating = rating?.takeIf { it in 0.0..10.0 },
            )
        }.distinctBy { it.id }

    internal fun parsePagination(doc: Document): PaginationConfig? {
        val nav = doc.selectFirst(".pagination-container") ?: return null
        val pages = nav.attr("data-pages").toIntOrNull()?.takeIf { it > 1 } ?: return null
        val action = nav.attr("data-page-action").trim().ifBlank { "category" }
        val sort = nav.attr("data-sort").trim().takeIf { it.isNotBlank() }
        val search = nav.attr("data-search").trim().takeIf { it.isNotBlank() }
        return PaginationConfig(
            pages = pages,
            action = action,
            sort = sort,
            search = search,
        )
    }

    internal fun paginationUrl(
        base: String,
        page: Int,
        config: PaginationConfig,
    ): String {
        val builder = when (config.action.lowercase()) {
            "home" -> MAIN_URL.toHttpUrl().newBuilder()
            else -> base.toHttpUrl().newBuilder()
        }
        builder.addQueryParameter("knl_page_action", config.action)
        builder.addQueryParameter("knl_paged", page.toString())
        when (config.action.lowercase()) {
            "archive" -> config.sort?.let { builder.addQueryParameter("knl_sort", it) }
            "search" -> config.search?.let { builder.addQueryParameter("s", it) }
        }
        return builder.build().toString()
    }

    internal fun parseCategoryLinks(doc: Document): List<Pair<String, String>> {
        val genreSection = doc.select("section.common-section").firstOrNull { section ->
            section.selectFirst(".section-title")?.text()
                ?.contains("Türlerine Göre Filmler", ignoreCase = true) == true
        }
        val links = genreSection?.select("a.nav-link[href]")
            ?.takeIf { it.isNotEmpty() }
            ?: doc.select(
                "a[href*='filmleri-hd-izle'], " +
                    "a[href*='/hint-filmleri/'], " +
                    "a[href*='/yerli-filmleri-hd-izle/'], " +
                    "a[href*='/tur/'][href*='/film/']",
            )

        return links.mapNotNull { link ->
            val title = link.text().trim()
            val href = absoluteFrom(doc.baseUri().ifBlank { MAIN_URL }, link.attr("href"))
                ?: return@mapNotNull null
            val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull()
                ?: return@mapNotNull null
            if (title.isBlank() || path == "/") return@mapNotNull null
            title to path
        }.distinctBy { it.second.lowercase() }
    }
    private fun mergeSections(found: List<Pair<String, String>>): Map<String, Section> {
        val merged = LinkedHashMap(fallbackSections)
        found.forEach { (title, path) ->
            val existing = merged.entries.firstOrNull {
                normalize(it.value.title) == normalize(title)
            }
            if (existing != null) merged[existing.key] = existing.value.copy(path = path)
            else merged["site:" + path.trim('/').replace('/', '-')] = Section(title, path, false)
        }
        return merged
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "").trim()

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchText(url, MAIN_URL), url)

    private suspend fun fetchText(
        url: String,
        referer: String,
        accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    ): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6")
                .header("Referer", referer)
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP " + response.code + " for " + url)
                }
                response.body?.string() ?: throw IOException("Empty response body for " + url)
            }
        }

    private fun imageUrl(element: Element?): String? {
        element ?: return null
        val raw = element.attr("data-src")
            .ifBlank { element.attr("data-lazy-src") }
            .ifBlank { element.attr("src") }
        return absoluteFrom(element.baseUri().ifBlank { MAIN_URL }, raw)
    }

    private fun absolute(raw: String): String = absoluteFrom(MAIN_URL, raw) ?: raw

    private fun absoluteFrom(base: String, raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:$value"
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
            failure(operation, ProviderErrorCode.NETWORK, error.message ?: "Network error", error)
        } catch (error: Exception) {
            failure(operation, ProviderErrorCode.PARSE, error.message ?: "Parse error", error)
        }

    private fun failure(
        operation: String,
        code: ProviderErrorCode,
        message: String,
        cause: Throwable? = null,
    ): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError(id, operation, code, message, cause))

    companion object {
        private const val TAG = "FilmMakinesiProvider"
        const val PROVIDER_ID = "filmmakinesi"
        const val MAIN_URL = "https://filmmakinesi.co"
        private const val HLS_MIME = "application/x-mpegURL"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; SM-S938B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
