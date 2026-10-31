/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.sinemacx

import android.util.Log
import app.opentv.data.provider.*
import app.opentv.data.provider.hdfilmcehennemi.HdFilmCehennemiCodec
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Provider implementation for SinemaCX / Sinema CC.
 * Supports movie catalog, genres, search, details, and video stream extraction.
 */
class SinemaCxProvider(
    private val http: OkHttpClient,
) : Provider {

    override val id: String = PROVIDER_ID
    override val name: String = "SinemaCX"
    override val supportedMediaTypes: Set<ProviderMediaType> = setOf(ProviderMediaType.MOVIE)

    private data class Section(
        val title: String,
        val path: String,
        val showOnHome: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefetchedHomePages = ConcurrentHashMap<Int, List<ProviderItem>>()

    @Volatile
    private var activeDomain: String = MAIN_URL

    private val sections = linkedMapOf(
        "home" to Section("Son Eklenenler", "/", showOnHome = true),
        "popular" to Section("Popüler Filmler", "/populer-filmler/", showOnHome = true),
        "dublaj" to Section("Türkçe Dublaj", "/dil/turkce-dublaj/", showOnHome = true),
        "altyazi" to Section("Türkçe Altyazı", "/dil/turkce-altyazi/", showOnHome = true),
        "aksiyon" to Section("Aksiyon", "/tur/aksiyon-filmleri/", showOnHome = false),
        "animasyon" to Section("Animasyon", "/tur/animasyon-filmleri/", showOnHome = false),
        "bilim-kurgu" to Section("Bilim Kurgu", "/tur/bilim-kurgu-filmleri/", showOnHome = false),
        "komedi" to Section("Komedi", "/tur/komedi-filmleri/", showOnHome = false),
        "korku" to Section("Korku", "/tur/korku-filmleri/", showOnHome = false),
        "dram" to Section("Dram", "/tur/dram-filmleri/", showOnHome = false),
        "suc" to Section("Suç", "/tur/suc-filmleri/", showOnHome = false),
        "romantik" to Section("Romantik", "/tur/romantik-filmler/", showOnHome = false),
        "macera" to Section("Macera", "/tur/macera-filmleri/", showOnHome = false),
        "fantastik" to Section("Fantastik", "/tur/fantastik-filmler/", showOnHome = false),
        "gerilim" to Section("Gerilim", "/tur/gerilim-filmleri/", showOnHome = false),
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
                if (cleanPath.isEmpty()) {
                    absolute("/page/$page/")
                } else {
                    absolute("$cleanPath/page/$page/")
                }
            }

            // The current home page omits pagination links, despite exposing /page/N/ pages.
            // Probe one page ahead and reuse that response instead of downloading it twice.
            val cached = if (sectionId == "home" && page > 1) prefetchedHomePages.remove(page) else null
            val doc = if (cached == null) fetchDocument(url) else null
            val items = cached ?: parseCards(checkNotNull(doc))
            val hasMore = if (sectionId == "home" && items.isNotEmpty()) {
                val nextItems = try {
                    parseCards(fetchDocument(absolute("/page/${page + 1}/")))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    emptyList()
                }
                val knownIds = items.map { it.id }.toHashSet()
                nextItems.any { it.id !in knownIds }.also { available ->
                    if (available) prefetchedHomePages[page + 1] = nextItems
                }
            } else {
                doc?.let { parseHasNextPage(it, page) } ?: false
            }
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

            val titleEl = doc.selectFirst("h1.baslik, h1")
                ?: throw IllegalArgumentException("Movie title not found: $url")
            val title = titleEl.text().trim().removeSuffix(" izle").removeSuffix(" İzle").trim()

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: parseImageUrl(doc.selectFirst("div.f-poster img, img.cover, .film_kutusu img"))

            val description = doc.selectFirst("div.f-bilgi div.ackl, div.ozet, meta[property=og:description]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }

            val year = Regex("""\b(19|20)\d{2}\b""").find(titleEl.text())?.value?.toIntOrNull()
                ?: Regex("""Yapım Yılı\s*:\s*(\d{4})""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

            val rating = doc.selectFirst("b#puandegistir, span.puan, .imdb")?.text()
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

            val durationSeconds = Regex("""(\d+)\s*Dakika""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()?.times(60)

            val genres = doc.select("div.f-bilgi div.tur a, a[href*='/tur/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.contains("Film", ignoreCase = true) }
                .distinct()

            val cast = doc.select("li.oyuncu-k span.isim, .oyuncular a")
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
                durationSeconds = durationSeconds,
            )
        }

    override suspend fun episodes(seriesId: String): ProviderResult<List<ProviderEpisode>> =
        failure("episodes", ProviderErrorCode.UNSUPPORTED, "SinemaCX does not support series")

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
        val iframeEl = doc.selectFirst("iframe#video_playeriframe, iframe[data-vsrc], iframe[src*='player']")
        val dataVsrc = iframeEl?.attr("data-vsrc")?.trim() ?: ""

        var playerUrl = if (dataVsrc.isNotBlank()) {
            decodeBase64Url(dataVsrc)
        } else {
            iframeEl?.attr("src")?.trim() ?: ""
        }

        if (playerUrl.isBlank()) {
            return emptyList()
        }

        playerUrl = absolute(playerUrl)
        val streams = mutableListOf<ProviderStream>()

        if (playerUrl.contains("player.filmizle.in")) {
            val videoId = Regex("""/video/([a-zA-Z0-9]+)""").find(playerUrl)?.groupValues?.get(1)
            if (videoId != null) {
                // Fetch player page to capture session cookie
                val playerReq = Request.Builder()
                    .url(playerUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", activeDomain + "/")
                    .build()
                val cookie = withContext(Dispatchers.IO) {
                    runCatching {
                        http.newCall(playerReq).execute().use { it.header("Set-Cookie")?.substringBefore(";")?.trim().orEmpty() }
                    }.getOrDefault("")
                }

                val postUrl = "https://player.filmizle.in/player/index.php?data=$videoId&do=getVideo"
                val formBody = FormBody.Builder()
                    .add("hash", videoId)
                    .add("r", activeDomain + "/")
                    .build()

                val request = Request.Builder()
                    .url(postUrl)
                    .post(formBody)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", playerUrl)
                    .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                val respJson = executeHttp(request)
                if (respJson.isNotBlank()) {
                    val root = json.parseToJsonElement(respJson).jsonObject
                    val securedLink = root["securedLink"]?.jsonPrimitive?.content
                        ?: root["videoSource"]?.jsonPrimitive?.content

                    if (!securedLink.isNullOrBlank()) {
                        streams.add(
                            ProviderStream(
                                providerId = id,
                                url = securedLink,
                                label = "SinemaCX 1080p",
                                quality = "1080p",
                                mimeType = "application/vnd.apple.mpegurl",
                                headers = buildMap {
                                    put("Referer", playerUrl)
                                    put("User-Agent", USER_AGENT)
                                    if (cookie.isNotBlank()) put("Cookie", cookie)
                                },
                            ),
                        )
                    }
                }
            }
        } else if (playerUrl.contains("panel.sinema.cx")) {
            val videoId = playerUrl.split("/").lastOrNull()?.substringBefore("?")
            if (!videoId.isNullOrBlank()) {
                val postUrl = "https://panel.sinema.cx/player/index.php?data=$videoId&do=getVideo"
                val request = Request.Builder()
                    .url(postUrl)
                    .post(FormBody.Builder().build())
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", activeDomain + "/")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                val respJson = executeHttp(request)
                if (respJson.isNotBlank()) {
                    val root = json.parseToJsonElement(respJson).jsonObject
                    val securedLink = root["securedLink"]?.jsonPrimitive?.content
                    if (!securedLink.isNullOrBlank()) {
                        streams.add(
                            ProviderStream(
                                providerId = id,
                                url = securedLink,
                                label = "SinemaCX Stream",
                                mimeType = "application/vnd.apple.mpegurl",
                                headers = mapOf(
                                    "Referer" to playerUrl,
                                    "User-Agent" to USER_AGENT,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        return streams
    }

    internal fun parseCards(doc: Document): List<ProviderItem> {
        val items = mutableListOf<ProviderItem>()
        val elements = doc.select("div.film_kutusu")

        for (el in elements) {
            val a = el.selectFirst("a[href]") ?: continue
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") continue

            val title = a.attr("title").trim().ifBlank {
                el.selectFirst(".baslik, h2, h3")?.text()?.trim() ?: ""
            }.removeSuffix(" izle").removeSuffix(" İzle").trim()

            if (title.isBlank()) continue

            val poster = parseImageUrl(el.selectFirst("img"))
            val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
                ?: el.selectFirst(".yil")?.text()?.trim()?.toIntOrNull()

            val rating = el.selectFirst("span.puan, .imdb")?.text()
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
        val pageLinks = doc.select("div.sayfala a, ul.pagination a, .pagination a")
        for (link in pageLinks) {
            val href = link.attr("href")
            val pageNum = Regex("""/page/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
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

    private fun decodeBase64Url(input: String): String {
        return try {
            val clean = input.replace("\\/", "/").trim()
            val decoded = HdFilmCehennemiCodec.decodeBase64(clean)
            String(decoded, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }
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
        const val PROVIDER_ID = "sinemacx"
        private const val TAG = "SinemaCxProvider"
        private const val MAIN_URL = "https://sinemacc.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
