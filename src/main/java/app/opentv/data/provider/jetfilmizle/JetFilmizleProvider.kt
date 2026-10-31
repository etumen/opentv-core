/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.jetfilmizle

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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Provider implementation for JetFilmizle.
 * Supports movie catalog, genres, search, details, and PlayerX / HLS stream resolution.
 */
class JetFilmizleProvider(
    private val http: OkHttpClient,
) : Provider {

    override val id: String = PROVIDER_ID
    override val name: String = "JetFilmizle"
    override val supportedMediaTypes: Set<ProviderMediaType> = setOf(ProviderMediaType.MOVIE)

    private data class Section(
        val title: String,
        val path: String,
        val showOnHome: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var activeDomain: String = MAIN_URL

    private val sections = linkedMapOf(
        "home" to Section("Son Eklenenler", "/filmler/", showOnHome = true),
        "popular" to Section("En Çok İzlenenler", "/filmler/en-cok-izlenenler", showOnHome = true),
        "imdb" to Section("IMDb 7+ Filmler", "/filmler/", showOnHome = true),
        "aksiyon" to Section("Aksiyon", "/tur/aksiyon", showOnHome = false),
        "animasyon" to Section("Animasyon", "/tur/animasyon", showOnHome = false),
        "bilim-kurgu" to Section("Bilim Kurgu", "/tur/bilim-kurgu", showOnHome = false),
        "komedi" to Section("Komedi", "/tur/komedi", showOnHome = false),
        "korku" to Section("Korku", "/tur/korku", showOnHome = false),
        "dram" to Section("Dram", "/tur/dram", showOnHome = false),
        "gerilim" to Section("Gerilim", "/tur/gerilim", showOnHome = false),
        "macera" to Section("Macera", "/tur/macera", showOnHome = false),
        "fantastik" to Section("Fantastik", "/tur/fantastik", showOnHome = false),
        "aile" to Section("Aile", "/tur/aile", showOnHome = false),
        "romantik" to Section("Romantik", "/tur/romantik", showOnHome = false),
        "suc" to Section("Suç", "/tur/suc", showOnHome = false),
    )

    override val catalogSections: List<ProviderCatalogSection>
        get() = sections.map { (sectionId, section) ->
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
        if (mediaType != ProviderMediaType.MOVIE) {
            return ProviderResult.Success(emptyList())
        }
        return ProviderResult.Success(catalogSections)
    }

    override suspend fun catalog(request: ProviderCatalogRequest): ProviderResult<ProviderCatalogPage> =
        guarded("catalog") {
            val sectionId = request.sectionId ?: "home"
            val section = sections[sectionId] ?: sections.getValue("home")
            val page = request.page.coerceAtLeast(1)

            val url = if (page == 1) {
                absolute(section.path)
            } else {
                val cleanPath = section.path.trimEnd('/')
                absolute("$cleanPath/sayfa-$page")
            }

            var effectivePage = page
            var effectiveUrl = url
            var doc = fetchDocument(effectiveUrl)
            var allItems = parseCards(doc)
            var items = if (sectionId == "imdb") allItems.filter { (it.rating ?: 0.0) >= 7.0 } else allItems
            var hasMore = parseHasNextPage(doc, effectivePage)

            // Filtering may leave one source page empty although later pages have rated titles.
            // Skip at most three such pages, preserving the actual source-page cursor.
            while (sectionId == "imdb" && items.isEmpty() && hasMore && effectivePage < page + 3) {
                effectivePage++
                effectiveUrl = absolute("${section.path.trimEnd('/')}/sayfa-$effectivePage")
                doc = fetchDocument(effectiveUrl)
                allItems = parseCards(doc)
                items = allItems.filter { (it.rating ?: 0.0) >= 7.0 }
                hasMore = parseHasNextPage(doc, effectivePage)
            }
            val nextPage = if (allItems.isNotEmpty() && hasMore) effectivePage + 1 else null

            Log.d(TAG, "catalog section=${section.title} page=$effectivePage items=${items.size} hasMore=$hasMore url=$effectiveUrl")
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

            val titleEl = doc.selectFirst("h1, .film-title")
                ?: throw IllegalArgumentException("Movie title not found: $url")
            val title = titleEl.text().trim().removeSuffix(" izle").removeSuffix(" İzle").trim()

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: parseImageUrl(doc.selectFirst("img.film-poster, .film-hero-backdrop, .film-card img"))

            val description = doc.selectFirst("div.film-desc, div.f-desc, meta[property=og:description]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }

            val year = Regex("""\b(19|20)\d{2}\b""").find(titleEl.text())?.value?.toIntOrNull()
                ?: doc.selectFirst("span.film-year, a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()

            val rating = doc.selectFirst("span.imdb-score, span.film-rating, .badge-warning")?.text()
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

            val genres = doc.select("a[href*='/tur/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val cast = doc.select(".film-actor, .film-cast a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val director = doc.selectFirst("a[href*='/yonetmen/'], .yonetmen a")?.text()?.trim()

            ProviderDetails(
                item = ProviderItem(
                    providerId = id,
                    id = url,
                    title = title,
                    mediaType = ProviderMediaType.MOVIE,
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
        failure("episodes", ProviderErrorCode.UNSUPPORTED, "JetFilmizle does not support series")

    override suspend fun streams(target: ProviderPlaybackTarget): ProviderResult<List<ProviderStream>> {
        val movie = target as? ProviderPlaybackTarget.Movie
            ?: return failure("streams", ProviderErrorCode.UNSUPPORTED, "Movie target required")
        return guarded("streams") {
            resolveStreams(absolute(movie.itemId))
        }
    }

    override suspend fun subtitles(target: ProviderPlaybackTarget): ProviderResult<List<ProviderSubtitle>> {
        return ProviderResult.Success(emptyList())
    }

    private suspend fun resolveStreams(movieUrl: String): List<ProviderStream> {
        val doc = fetchDocument(movieUrl)
        val filmId = doc.selectFirst("input[name=film_id]")?.attr("value")?.trim()
            ?: Regex("""film_id["\s:=]+(\d+)""").find(doc.html())?.groupValues?.get(1)

        val streams = mutableListOf<ProviderStream>()

        if (!filmId.isNullOrBlank()) {
            val buttons = doc.select("button.player-source-btn")
            val sourceIndex = buttons.firstOrNull()?.attr("data-source-index") ?: "0"
            val playerType = buttons.firstOrNull()?.attr("data-player-type") ?: "dublaj"

            val formBody = FormBody.Builder()
                .add("film_id", filmId)
                .add("source_index", sourceIndex)
                .add("player_type", playerType)
                .build()

            val jetPlayerReq = Request.Builder()
                .url(absolute("/jetplayer"))
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("Referer", movieUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val playerHtml = executeHttp(jetPlayerReq)
            val iframeSrc = Jsoup.parse(playerHtml).selectFirst("iframe")?.attr("src")?.trim()
                ?: Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(playerHtml)?.groupValues?.get(1)

            if (!iframeSrc.isNullOrBlank() && iframeSrc.contains("playerx.info")) {
                val playerxReq = Request.Builder()
                    .url(iframeSrc)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", activeDomain + "/")
                    .build()

                val playerxHtml = executeHttp(playerxReq)
                val playbackJson = Regex("""initialPlayback\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(playerxHtml)?.groupValues?.get(1)
                if (playbackJson != null) {
                    val root = json.parseToJsonElement(playbackJson).jsonObject
                    val sources = root["item"]?.jsonObject?.get("sources")?.jsonArray
                    val filePath = sources?.firstOrNull()?.jsonObject?.get("file")?.jsonPrimitive?.content

                    if (!filePath.isNullOrBlank()) {
                        val streamUrl = if (filePath.startsWith("http")) filePath else "https://playerx.info/$filePath"
                        streams.add(
                            ProviderStream(
                                providerId = id,
                                url = streamUrl,
                                label = "JetFilmizle 1080p",
                                quality = "1080p",
                                mimeType = "application/vnd.apple.mpegurl",
                                headers = mapOf(
                                    "Referer" to "https://playerx.info/",
                                    "User-Agent" to USER_AGENT,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        // Fallback: check direct iframe in page
        if (streams.isEmpty()) {
            val directIframe = doc.selectFirst("iframe[src*='player'], iframe[data-src*='player']")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }.trim()
            }
            if (!directIframe.isNullOrBlank()) {
                streams.add(
                    ProviderStream(
                        providerId = id,
                        url = directIframe,
                        label = "JetFilmizle Stream",
                        headers = mapOf(
                            "Referer" to movieUrl,
                            "User-Agent" to USER_AGENT,
                        ),
                    ),
                )
            }
        }

        return streams
    }

    internal fun parseCards(doc: Document): List<ProviderItem> {
        val items = mutableListOf<ProviderItem>()
        val elements = doc.select("div.film-card, div.jf-card")

        for (el in elements) {
            val a = el.selectFirst("a[href]") ?: continue
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") continue

            val title = el.selectFirst(".film-card-title, .fc-title, h2, h3")?.text()?.trim()
                ?.removeSuffix(" izle")?.removeSuffix(" İzle")?.trim()
                ?: a.attr("title").trim().removeSuffix(" izle").removeSuffix(" İzle").trim()

            if (title.isBlank()) continue

            val poster = parseImageUrl(el.selectFirst("img"))
            val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
                ?: el.selectFirst(".fc-year, span.year")?.text()?.trim()?.toIntOrNull()

            val rating = el.selectFirst(".fc-rating, .imdb, span.badge")?.text()
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

            items.add(
                ProviderItem(
                    providerId = id,
                    id = absolute(href),
                    title = title,
                    mediaType = ProviderMediaType.MOVIE,
                    posterUrl = poster,
                    year = year,
                    rating = rating,
                ),
            )
        }
        return items
    }

    internal fun parseHasNextPage(doc: Document, currentPage: Int): Boolean {
        val pageLinks = doc.select("ul.pagination a, .pagination a, div.pages a, a[href*='sayfa-']")
        for (link in pageLinks) {
            val href = link.attr("href")
            val pageNum = Regex("""sayfa-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: link.text().trim().toIntOrNull()
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
        const val PROVIDER_ID = "jetfilmizle"
        private const val TAG = "JetFilmizleProvider"
        private const val MAIN_URL = "https://jetfilmizle.app"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
