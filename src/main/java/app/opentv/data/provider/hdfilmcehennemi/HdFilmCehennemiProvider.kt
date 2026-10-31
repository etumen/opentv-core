/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.hdfilmcehennemi

import android.util.Log
import app.opentv.data.provider.*
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
 * Native HDFilmCehennemi movie and series provider.
 *
 * Implements unified catalog, search, details, season/episode hierarchy, and end-to-end
 * player resolution (Rapidrame / CloseLoad embeds with native decryption).
 */
class HdFilmCehennemiProvider(
    private val http: OkHttpClient,
) : Provider {

    override val id: String = PROVIDER_ID
    override val name: String = "HDFilmCehennemi"
    override val supportedMediaTypes: Set<ProviderMediaType> = setOf(
        ProviderMediaType.MOVIE,
        ProviderMediaType.SERIES,
    )

    private data class Section(
        val title: String,
        val path: String,
        val action: String,
        val mediaType: ProviderMediaType,
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
    private val playbackCache = ConcurrentHashMap<String, CachedPlayback>()
    private val paginationBySection = ConcurrentHashMap<String, HdFilmCehennemiCodec.PaginationInfo>()
    private val paginationActionBySection = ConcurrentHashMap<String, HdFilmCehennemiCodec.PaginationActionInfo>()

    @Volatile
    private var activeDomain: String = MAIN_URL

    private val fallbackSections = linkedMapOf(
        "home" to Section("Yeni Eklenenler", "/", "home", ProviderMediaType.MOVIE, showOnHome = true),
        "recommended" to Section("Tavsiye Filmler", "/category/tavsiye-filmler-izle2/", "categories/tavsiye-filmler-izle3", ProviderMediaType.MOVIE, showOnHome = true),
        "nette_ilk" to Section("Nette İlk", "/category/nette-ilk-filmler-1/", "categories/nette-ilk-filmler-1", ProviderMediaType.MOVIE, showOnHome = true),
        "imdb7" to Section("IMDb 7+ Filmler", "/imdb-7-puan-uzeri-filmler-2/", "imdb7", ProviderMediaType.MOVIE, showOnHome = true),
        "all_movies" to Section("Filmler", "/category/film-izle-2/", "categories/film-izle-2", ProviderMediaType.MOVIE, showOnHome = true),
        "series" to Section("Diziler", "/yabancidiziizle-7/", "home-series", ProviderMediaType.SERIES, showOnHome = true),
        "most_commented" to Section("En Çok Yorumlananlar", "/en-cok-yorumlananlar-3/", "mostCommented", ProviderMediaType.MOVIE, showOnHome = false),
        "most_liked" to Section("En Çok Beğenilenler", "/en-cok-begenilen-filmleri-izle-5/", "mostLiked", ProviderMediaType.MOVIE, showOnHome = false),
        "action" to Section("Aksiyon", "/tur/aksiyon-filmleri-izleyin-9/", "genres/aksiyon-filmleri-izleyin-9", ProviderMediaType.MOVIE, showOnHome = false),
        "animation" to Section("Animasyon", "/tur/animasyon-filmlerini-izleyin-5/", "genres/animasyon-filmlerini-izleyin-5", ProviderMediaType.MOVIE, showOnHome = false),
        "scifi" to Section("Bilim Kurgu", "/tur/bilim-kurgu-filmlerini-izleyin-6/", "genres/bilim-kurgu-filmlerini-izleyin-6", ProviderMediaType.MOVIE, showOnHome = false),
        "comedy" to Section("Komedi", "/tur/komedi-filmlerini-izleyin-2/", "genres/komedi-filmleri-izleyin-2", ProviderMediaType.MOVIE, showOnHome = false),
        "horror" to Section("Korku", "/tur/korku-filmlerini-izle-9/", "genres/korku-filmlerini-izle-9", ProviderMediaType.MOVIE, showOnHome = false),
        "drama" to Section("Dram", "/tur/dram-filmlerini-izle-2/", "genres/dram-filmlerini-izle-2", ProviderMediaType.MOVIE, showOnHome = false),
        "thriller" to Section("Gerilim", "/tur/gerilim-filmlerini-izle-5/", "genres/gerilim-filmlerini-izle-5", ProviderMediaType.MOVIE, showOnHome = false),
        "adventure" to Section("Macera", "/tur/macera-filmlerini-izleyin-6/", "genres/macera-filmlerini-izleyin-6", ProviderMediaType.MOVIE, showOnHome = false),
        "fantasy" to Section("Fantastik", "/tur/fantastik-filmlerini-izleyin-5/", "genres/fantastik-filmlerini-izleyin-5", ProviderMediaType.MOVIE, showOnHome = false),
        "crime" to Section("Suç", "/tur/suc-filmleri-izle-3/", "genres/suc-filmleri-izle-3", ProviderMediaType.MOVIE, showOnHome = false),
        "romance" to Section("Romantik", "/tur/romantik-filmleri-izle-3/", "genres/romantik-filmleri-izle-3", ProviderMediaType.MOVIE, showOnHome = false),
        "documentary" to Section("Belgesel", "/tur/belgesel-filmlerini-izle-2/", "genres/belgesel-filmlerini-izle-2", ProviderMediaType.MOVIE, showOnHome = false),
        "family" to Section("Aile", "/tur/aile-filmleri-izleyin-7/", "genres/aile-filmleri-izleyin-7", ProviderMediaType.MOVIE, showOnHome = false),
        "dubbed" to Section("Türkçe Dublaj", "/dil/turkce-dublajli-film-izleyin-6/", "languages/turkce-dublajli-film-izleyin-6", ProviderMediaType.MOVIE, showOnHome = false),
        "subtitled" to Section("Türkçe Altyazılı", "/dil/turkce-altyazili-filmleri-izleme-sitesi-3/", "languages/turkce-altyazili-filmleri-izleme-sitesi-3", ProviderMediaType.MOVIE, showOnHome = false),
        "y2025" to Section("2025 Filmleri", "/yil/2025-filmleri-izle-3/", "years/2025-filmleri-izle-3", ProviderMediaType.MOVIE, showOnHome = false),
        "y2024" to Section("2024 Filmleri", "/yil/2024-2/", "years/2024-2", ProviderMediaType.MOVIE, showOnHome = false),
    )

    override val catalogSections: List<ProviderCatalogSection>
        get() = fallbackSections.map { (sectionId, section) ->
            ProviderCatalogSection(
                id = sectionId,
                title = section.title,
                mediaType = section.mediaType,
                showOnHome = section.showOnHome,
            )
        }

    override suspend fun discoverCatalogSections(
        mediaType: ProviderMediaType,
    ): ProviderResult<List<ProviderCatalogSection>> =
        ProviderResult.Success(catalogSections.filter { it.mediaType == mediaType })

    override suspend fun catalog(
        request: ProviderCatalogRequest,
    ): ProviderResult<ProviderCatalogPage> = guarded("catalog") {
        val defaultSectionId = if (request.mediaType == ProviderMediaType.SERIES) "series" else "home"
        val sectionId = request.sectionId ?: defaultSectionId
        val section = fallbackSections[sectionId] ?: fallbackSections.getValue(defaultSectionId)
        val page = request.page.coerceAtLeast(1)
        val base = absolute(section.path)

        if (page == 1) {
            val doc = fetchDocument(base)
            val actionInfo = HdFilmCehennemiCodec.parsePaginationActionInfo(doc)
            if (actionInfo != null) {
                paginationActionBySection[sectionId] = actionInfo
            } else {
                HdFilmCehennemiCodec.parsePaginationInfo(doc)?.let {
                    paginationBySection[sectionId] = it
                }
            }

            val items = parseCards(doc, section.mediaType)
            val maxPages = actionInfo?.maxPages ?: paginationBySection[sectionId]?.maxPages ?: 1
            val nextPage = if (items.isNotEmpty() && page < maxPages) page + 1 else null

            ProviderCatalogPage(
                title = section.title,
                items = items,
                nextPage = nextPage,
            )
        } else {
            val cachedAction = paginationActionBySection[sectionId]
            if (cachedAction != null) {
                if (page > cachedAction.maxPages) {
                    return@guarded ProviderCatalogPage(
                        title = section.title,
                        items = emptyList(),
                        nextPage = null,
                    )
                }

                val cleanAction = cachedAction.pageAction.trim('/')
                val pageUrl = "$activeDomain/load/page/$page/$cleanAction/"
                val jsonText = fetchWithHeaders(pageUrl, mapOf(
                    "X-Requested-With" to "fetch",
                    "Referer" to "$activeDomain/",
                ))
                val fragmentHtml = runCatching {
                    json.parseToJsonElement(jsonText).jsonObject["html"]?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty()

                val fragmentDoc = Jsoup.parseBodyFragment(fragmentHtml, activeDomain)
                val items = parseCards(fragmentDoc, section.mediaType)
                val nextPage = if (items.isNotEmpty() && page < cachedAction.maxPages) page + 1 else null

                return@guarded ProviderCatalogPage(
                    title = section.title,
                    items = items,
                    nextPage = nextPage,
                )
            }

            // Fallback for legacy WordPress pagination if applicable
            val pagination = paginationBySection[sectionId] ?: run {
                val firstDoc = fetchDocument(base)
                HdFilmCehennemiCodec.parsePaginationInfo(firstDoc)?.also {
                    paginationBySection[sectionId] = it
                }
            }

            val maxPages = pagination?.maxPages ?: 1
            if (page > maxPages) {
                return@guarded ProviderCatalogPage(
                    title = section.title,
                    items = emptyList(),
                    nextPage = null,
                )
            }

            val contentType = pagination?.contentType
                ?: if (section.mediaType == ProviderMediaType.SERIES) "tvshows" else "movies"
            val termId = pagination?.termId ?: "0"

            val ajaxUrl = "$activeDomain/wp-admin/admin-ajax.php"
            val ajaxBody = postAjax(
                ajaxUrl,
                mapOf(
                    "action" to "load_page_content",
                    "page" to page.toString(),
                    "content_type" to contentType,
                    "term_id" to termId,
                ),
            )

            val fragmentDoc = Jsoup.parseBodyFragment(ajaxBody, activeDomain)
            val items = parseCards(fragmentDoc, section.mediaType)
            val nextPage = if (items.isNotEmpty() && page < maxPages) page + 1 else null

            ProviderCatalogPage(
                title = section.title,
                items = items,
                nextPage = nextPage,
            )
        }
    }

    override suspend fun search(
        request: ProviderSearchRequest,
    ): ProviderResult<List<ProviderItem>> = guarded("search") {
        val query = request.query.trim()
        if (query.isEmpty()) return@guarded emptyList()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchApiUrl = "$activeDomain/search?q=$encodedQuery"

        val searchResult = runCatching {
            val jsonText = fetchWithHeaders(searchApiUrl, mapOf(
                "X-Requested-With" to "fetch",
                "Content-Type" to "application/json",
                "Referer" to "$activeDomain/",
            ))
            val jsonRoot = json.parseToJsonElement(jsonText).jsonObject
            val resultsArray = jsonRoot["results"]?.jsonArray
            val items = mutableListOf<ProviderItem>()

            resultsArray?.forEach { resEl ->
                val htmlSnippet = resEl.jsonPrimitive.contentOrNull ?: return@forEach
                val fragment = Jsoup.parseBodyFragment(htmlSnippet, activeDomain)
                val linkEl = fragment.selectFirst("a[href]") ?: return@forEach
                val href = linkEl.attr("href").trim()
                if (href.isBlank()) return@forEach

                val text = fragment.text().trim()
                val isSeries = text.contains("Dizi", ignoreCase = true) || href.contains("/dizi/")
                val mediaType = if (isSeries) ProviderMediaType.SERIES else ProviderMediaType.MOVIE

                if (request.mediaType != null && request.mediaType != mediaType) {
                    return@forEach
                }

                val title = linkEl.selectFirst("h2, h3, .poster-title, .title")?.text()?.trim()
                    ?: linkEl.attr("title").takeIf { it.isNotBlank() }
                    ?: text.replace(Regex("""^\d{4}\s+[\d.]+\s+(Film|Dizi)\s+"""), "").trim()

                val imgEl = fragment.selectFirst("img")
                val rawPosterUrl = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: imgEl?.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                val posterUrl = rawPosterUrl?.let { absolute(it) }

                val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
                val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

                val ratingMatch = Regex("""\b([1-9]\.\d)\b""").find(text)
                val rating = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

                val url = linkEl.absUrl("href").ifBlank { absolute(href) }

                items.add(
                    ProviderItem(
                        providerId = id,
                        id = url,
                        title = title.ifBlank { "İsimsiz İçerik" },
                        mediaType = mediaType,
                        posterUrl = posterUrl,
                        year = year,
                        rating = rating,
                    ),
                )
            }
            items
        }.getOrNull()

        if (!searchResult.isNullOrEmpty()) {
            return@guarded searchResult
        }

        // Fallback to HTML search
        val target = "$activeDomain/?s=$encodedQuery"
        val doc = fetchDocument(target)
        val allItems = parseCards(doc, request.mediaType ?: ProviderMediaType.MOVIE)
        if (request.mediaType != null) {
            allItems.filter { it.mediaType == request.mediaType }
        } else {
            allItems
        }
    }

    override suspend fun load(itemId: String): ProviderResult<ProviderDetails> = guarded("load") {
        val url = absolute(itemId)
        val doc = fetchDocument(url)
        parseDetails(doc, url) ?: throw IOException("Could not parse details for $url")
    }

    internal fun parseDetails(doc: Document, url: String): ProviderDetails? {
        val titleEl = doc.selectFirst("h1, .flbaslik, .movie-title, .entry-title")
            ?: return null
        val title = titleEl.text().trim()
            .replace(Regex("""\s+izle.*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { return null }

        val originalTitle = doc.selectFirst(".or-title, .original-title, .alternative-title")?.text()?.trim()
        val description = doc.selectFirst("article.post-info-content, .overview, .description, .entry-content, .contenido p, [itemprop=description]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }

        val posterEl = doc.selectFirst("aside.post-info-poster img")?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                ?: it.attr("src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                ?: it.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
        } ?: doc.selectFirst(".poster img, .movie-poster img, [itemprop=image]")?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                ?: it.attr("src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val poster = posterEl?.let { absolute(it) }

        val backdropEl = doc.selectFirst("div.play-that-video img, .backdrop img, .cover img, [data-backdrop]")?.let {
            it.attr("src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                ?: it.attr("data-src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                ?: it.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
        }
        val backdrop = backdropEl?.let { absolute(it) }

        val yearText = doc.select(".extra, .year, .release-date, [itemprop=dateCreated], .anayil, .meta").text()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(yearText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val ratingText = doc.select(".imdb, #repimdb, .rating, .imdb-score, [itemprop=ratingValue]").text()
        val rating = Regex("""\b([1-9]\.\d)\b""").find(ratingText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        val genres = doc.select("a[href*=/tur/], .sgeneros a, .genres a, .genre a, [rel=category tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val director = doc.selectFirst("a[href*=/yonetmen/], .director a")?.text()?.trim()
        val cast = doc.select("a[href*=/oyuncu/], .cast a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val durationMinutes = Regex("""(\d+)\s*Dak""").find(doc.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val durationSeconds = durationMinutes?.times(60)

        val imdbId = Regex("""(tt\d{6,})""").find(doc.html())?.groupValues?.getOrNull(1)
        val externalIds = if (imdbId != null) mapOf("imdb" to imdbId) else emptyMap()

        val isSeries = url.contains("/dizi/") || doc.selectFirst("#seasons, .seasons, .episodios, a.mini-poster, [data-season]") != null
        val mediaType = if (isSeries) ProviderMediaType.SERIES else ProviderMediaType.MOVIE

        return ProviderDetails(
            item = ProviderItem(
                providerId = id,
                id = url,
                title = title,
                mediaType = mediaType,
                posterUrl = poster,
                backdropUrl = backdrop,
                description = description,
                year = year,
                rating = rating,
                genres = genres,
                externalIds = externalIds,
            ),
            description = description,
            cast = cast,
            director = director,
            durationSeconds = durationSeconds,
            externalIds = externalIds,
        )
    }

    override suspend fun episodes(seriesId: String): ProviderResult<List<ProviderEpisode>> = guarded("episodes") {
        val url = absolute(seriesId)
        val doc = fetchDocument(url)
        parseEpisodes(doc, url)
    }

    override suspend fun streams(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderStream>> {
        val targetUrl = when (target) {
            is ProviderPlaybackTarget.Movie -> absolute(target.itemId)
            is ProviderPlaybackTarget.Episode -> absolute(target.episodeId)
        }
        return when (val result = resolvePlayback(targetUrl)) {
            is ProviderResult.Success -> ProviderResult.Success(result.value.streams)
            is ProviderResult.Failure -> result
        }
    }

    override suspend fun subtitles(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderSubtitle>> {
        val targetUrl = when (target) {
            is ProviderPlaybackTarget.Movie -> absolute(target.itemId)
            is ProviderPlaybackTarget.Episode -> absolute(target.episodeId)
        }
        return when (val result = resolvePlayback(targetUrl)) {
            is ProviderResult.Success -> ProviderResult.Success(result.value.subtitles)
            is ProviderResult.Failure -> result
        }
    }

    internal suspend fun resolvePlayback(pageUrl: String): ProviderResult<Playback> = guarded("playback") {
        val cacheKey = pageUrl
        val cached = playbackCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.createdAtMillis < PLAYBACK_TTL_MILLIS) {
            return@guarded cached.value
        }

        val doc = fetchDocument(pageUrl)
        val docHtml = doc.html()

        // 0. Look for SetPlay AJAX endpoint (modern HDFilmCehennemi)
        val postId = doc.selectFirst("[data-post-id]")?.attr("data-post-id")?.trim()
            ?: Regex("""post_id["\s:=]+(\d+)""").find(docHtml)?.groupValues?.get(1)
        val nonce = Regex("""window\.videoAjax\s*=\s*\{[^}]*nonce:\s*['"]([a-zA-Z0-9]+)['"]""").find(docHtml)?.groupValues?.get(1)
            ?: Regex("""nonce["\s:=]+['"]([a-zA-Z0-9]+)['"]""").find(docHtml)?.groupValues?.get(1)

        if (!postId.isNullOrBlank() && !nonce.isNullOrBlank()) {
            val playerName = doc.selectFirst("[data-player-name]")?.attr("data-player-name")?.ifBlank { "SetPlay" } ?: "SetPlay"
            val formBody = FormBody.Builder()
                .add("action", "get_video_url")
                .add("nonce", nonce)
                .add("post_id", postId)
                .add("player_name", playerName)
                .build()
            val ajaxReq = Request.Builder()
                .url("$activeDomain/wp-admin/admin-ajax.php")
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("Referer", pageUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val ajaxResp = runCatching { executeHttp(ajaxReq) }.getOrNull().orEmpty()
            val setPlayUrl = runCatching {
                json.parseToJsonElement(ajaxResp).jsonObject["data"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            }.getOrNull()

            if (!setPlayUrl.isNullOrBlank()) {
                val setPlayHtml = fetchWithHeaders(setPlayUrl, mapOf("Referer" to "$activeDomain/"))
                val setPlayPair = HdFilmCehennemiCodec.parseSetPlayCerceve(setPlayHtml)
                if (setPlayPair != null) {
                    val fastPlayUrl = HdFilmCehennemiCodec.xorDecode(setPlayPair.first, setPlayPair.second)
                    val fastPlayHtml = fetchDocument(fastPlayUrl).html()

                    val streamPath = HdFilmCehennemiCodec.parseFastPlayStream(fastPlayHtml)
                    val spPair = HdFilmCehennemiCodec.parseFastPlaySp(fastPlayHtml)

                    if (streamPath != null && spPair != null) {
                        val fastPlayUri = fastPlayUrl.toHttpUrl()
                        val manifestUrl = "${fastPlayUri.scheme}://${fastPlayUri.host}$streamPath"
                        val xSp = HdFilmCehennemiCodec.generateXSp(spPair.first, spPair.second)

                        val fastPlayTracks = HdFilmCehennemiCodec.parseFastPlayTracks(fastPlayHtml)
                        val subtitles = fastPlayTracks.map { track ->
                            ProviderSubtitle(
                                providerId = id,
                                url = track.file,
                                label = track.label ?: "Altyazı",
                                language = track.lang ?: "tr",
                            )
                        }

                        val stream = ProviderStream(
                            providerId = id,
                            url = manifestUrl,
                            label = "HDFilmCehennemi (HLS)",
                            mimeType = "application/vnd.apple.mpegurl",
                            quality = "1080p",
                            headers = mapOf(
                                "Referer" to fastPlayUrl,
                                "Origin" to "${fastPlayUri.scheme}://${fastPlayUri.host}",
                                "X-Sp" to xSp,
                                "User-Agent" to USER_AGENT,
                            ),
                        )

                        val result = Playback(listOf(stream), subtitles)
                        playbackCache[cacheKey] = CachedPlayback(System.currentTimeMillis(), result)
                        return@guarded result
                    }
                }
            }
        }

        // 1. Look for direct iframe on the page
        var iframeSrc = doc.select("iframe").mapNotNull {
            it.attr("src").takeIf { s -> s.startsWith("http") }
                ?: it.attr("data-src").takeIf { s -> s.startsWith("http") }
        }.firstOrNull { it.contains("rapidrame") || it.contains("close") || it.contains("video/embed") }

        // 2. If no iframe or if player buttons exist, fetch video from data-video endpoint
        if (iframeSrc == null) {
            val videoButtons = doc.select("[data-video], .alternative-link")
            val videoId = videoButtons.firstNotNullOfOrNull { it.attr("data-video").takeIf { id -> id.isNotBlank() } }

            if (videoId != null) {
                val videoEndpoint = "$activeDomain/video/$videoId/"
                val videoJsonText = runCatching {
                    fetchWithHeaders(videoEndpoint, mapOf(
                        "X-Requested-With" to "fetch",
                        "Content-Type" to "application/json",
                        "Referer" to pageUrl,
                    ))
                }.getOrNull()

                if (!videoJsonText.isNullOrBlank()) {
                    val htmlSnippet = runCatching {
                        json.parseToJsonElement(videoJsonText).jsonObject["data"]
                            ?.jsonObject?.get("html")?.jsonPrimitive?.contentOrNull
                    }.getOrNull().orEmpty()

                    val iframeEl = Jsoup.parseBodyFragment(htmlSnippet).selectFirst("iframe")
                    iframeSrc = iframeEl?.attr("src")?.takeIf { it.startsWith("http") }
                        ?: iframeEl?.attr("data-src")?.takeIf { it.startsWith("http") }
                }
            }
        }

        // 3. Resolve iframe embed to HLS stream URL
        if (iframeSrc != null) {
            val iframeHtml = fetchWithHeaders(iframeSrc, mapOf("Referer" to "$activeDomain/"))
            val iframeHost = runCatching { iframeSrc.toHttpUrl().host }.getOrDefault("hdfilmcehennemi.mobi")

            val streamUrl = HdFilmCehennemiCodec.decryptRapidrame(iframeHtml, iframeHost)
            if (!streamUrl.isNullOrBlank()) {
                val subTracks = HdFilmCehennemiCodec.parseRapidrameSubtitles(iframeHtml)
                val subtitles = subTracks.map { track ->
                    ProviderSubtitle(
                        providerId = id,
                        url = track.file,
                        label = track.label ?: "Altyazı",
                        language = track.lang ?: "tr",
                    )
                }

                val stream = ProviderStream(
                    providerId = id,
                    url = streamUrl,
                    label = "HDFilmCehennemi (1080p)",
                    mimeType = "application/x-mpegURL",
                    quality = "1080p",
                    headers = mapOf(
                        "Referer" to iframeSrc,
                        "Origin" to "https://$iframeHost",
                        "User-Agent" to USER_AGENT,
                    ),
                )

                val result = Playback(listOf(stream), subtitles)
                playbackCache[cacheKey] = CachedPlayback(System.currentTimeMillis(), result)
                return@guarded result
            }
        }

        // 4. Fallback to SetPlay / FastPlay decryption if present
        val setPlayPair = HdFilmCehennemiCodec.parseSetPlayCerceve(docHtml)
        if (setPlayPair != null) {
            val fastPlayUrl = HdFilmCehennemiCodec.xorDecode(setPlayPair.first, setPlayPair.second)
            val fastPlayHtml = fetchDocument(fastPlayUrl).html()

            val streamPath = HdFilmCehennemiCodec.parseFastPlayStream(fastPlayHtml)
                ?: throw IOException("FastPlay stream path not found")
            val spPair = HdFilmCehennemiCodec.parseFastPlaySp(fastPlayHtml)
                ?: throw IOException("FastPlay sp token not found")

            val fastPlayUri = fastPlayUrl.toHttpUrl()
            val manifestUrl = "${fastPlayUri.scheme}://${fastPlayUri.host}$streamPath"
            val xSp = HdFilmCehennemiCodec.generateXSp(spPair.first, spPair.second)

            val fastPlayTracks = HdFilmCehennemiCodec.parseFastPlayTracks(fastPlayHtml)
            val subtitles = fastPlayTracks.map { track ->
                ProviderSubtitle(
                    providerId = id,
                    url = track.file,
                    label = track.label ?: "Altyazı",
                    language = track.lang ?: "tr",
                )
            }

            val stream = ProviderStream(
                providerId = id,
                url = manifestUrl,
                label = "HDFilmCehennemi (HLS)",
                mimeType = "application/x-mpegURL",
                quality = "1080p",
                headers = mapOf(
                    "Referer" to fastPlayUrl,
                    "Origin" to "${fastPlayUri.scheme}://${fastPlayUri.host}",
                    "X-Sp" to xSp,
                    "User-Agent" to USER_AGENT,
                ),
            )

            val result = Playback(listOf(stream), subtitles)
            playbackCache[cacheKey] = CachedPlayback(System.currentTimeMillis(), result)
            return@guarded result
        }

        throw IOException("No playable stream found for target")
    }

    internal fun parseCards(container: Element, fallbackMediaType: ProviderMediaType): List<ProviderItem> {
        val items = mutableListOf<ProviderItem>()
        val elements = container.select("a.poster, article, div.poster, .movie-card, .film-item, a.search-result")

        for (el in elements) {
            val link = el.selectFirst("a[href]") ?: el.takeIf { it.tagName() == "a" && it.hasAttr("href") } ?: continue
            val href = link.attr("href").trim()
            if (href.isBlank()) continue

            // Skip episode URLs from becoming movie items
            if (href.contains("/bolum/") || href.contains("/sezon-") && href.contains("bolum-")) {
                continue
            }

            val rawTitle = el.selectFirst(".flbaslik, .title a, .poster-title, .title, h2, h3, h4")?.text()?.trim()
                ?: el.attr("title").takeIf { it.isNotBlank() }
                ?: link.attr("title").takeIf { it.isNotBlank() }
                ?: href.trim('/').substringAfterLast('/').replace('-', ' ')

            val cleanTitle = rawTitle.substringBefore(" - ").replace(Regex("""\s+izle.*""", RegexOption.IGNORE_CASE), "").trim()

            val img = el.selectFirst("img")
            val rawPosterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            val posterUrl = rawPosterUrl?.let { absolute(it) }

            val metaText = el.select(".anayil, .anadil, .poster-meta, .meta, .film-details, .poster-info").text()
            val year = Regex("""\b(19\d\d|20\d\d)\b""").find(metaText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val rating = Regex("""\b([1-9]\.\d)\b""").find(metaText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?: Regex("""IMDb\s*([1-9]\.\d)""").find(el.text())?.groupValues?.getOrNull(1)?.toDoubleOrNull()

            val description = el.selectFirst(".contenido p, .description, p")?.text()?.trim()

            val isSeries = href.contains("/dizi/") || el.text().contains("Dizi", ignoreCase = true)
            val mediaType = if (isSeries) ProviderMediaType.SERIES else fallbackMediaType
            val url = link.absUrl("href").ifBlank { absolute(href) }

            items.add(
                ProviderItem(
                    providerId = id,
                    id = url,
                    title = cleanTitle.ifBlank { "İsimsiz İçerik" },
                    mediaType = mediaType,
                    posterUrl = posterUrl,
                    description = description,
                    year = year,
                    rating = rating,
                ),
            )
        }
        return items.distinctBy { it.id }
    }

    internal fun parseEpisodes(doc: Document, seriesUrl: String): List<ProviderEpisode> {
        val episodes = mutableListOf<ProviderEpisode>()

        // 1. Try legacy/WordPress seasons structure (div#seasons -> div.se-c -> ul.episodios -> li)
        val seasonContainers = doc.select("#seasons .se-c, .seasons .se-c")
        if (seasonContainers.isNotEmpty()) {
            for (seC in seasonContainers) {
                val seasonNum = seC.selectFirst(".se-q .se-t, .se-t")?.text()?.trim()?.toIntOrNull() ?: 1
                for (li in seC.select("ul.episodios li")) {
                    val link = li.selectFirst("a[href]") ?: continue
                    val href = link.attr("href").trim()
                    if (href.isBlank()) continue

                    val epNum = Regex("""\b\d+\s*-\s*(\d+)\b""").find(li.select(".numerando").text())
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""bolum-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: (episodes.size + 1)

                    val epTitle = li.selectFirst(".episodiotitle a, .episodiotitle")?.text()?.trim()
                        ?: link.text().trim()

                    val still = li.selectFirst("img")?.let { img ->
                        img.absUrl("data-src").ifBlank { img.absUrl("src") }
                            .ifBlank { absolute(img.attr("data-src").ifBlank { img.attr("src") }) }
                    }?.takeIf { it.isNotBlank() }

                    episodes.add(
                        ProviderEpisode(
                            providerId = id,
                            seriesId = seriesUrl,
                            id = link.absUrl("href").ifBlank { absolute(href) },
                            season = seasonNum,
                            episodeNumber = epNum,
                            title = epTitle.ifBlank { "Bölüm $epNum" },
                            stillUrl = still,
                        ),
                    )
                }
            }
        }

        // 2. Try ul.episodios flat list fallback
        if (episodes.isEmpty()) {
            for (li in doc.select("ul.episodios li")) {
                val link = li.selectFirst("a[href]") ?: continue
                val href = link.attr("href").trim()
                if (href.isBlank()) continue

                val sNum = Regex("""(\d+)-sezon""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val eNum = Regex("""(\d+)-bolum""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (episodes.size + 1)
                val title = link.text().trim()

                episodes.add(
                    ProviderEpisode(
                        providerId = id,
                        seriesId = seriesUrl,
                        id = link.absUrl("href").ifBlank { absolute(href) },
                        season = sNum,
                        episodeNumber = eNum,
                        title = title.ifBlank { "Bölüm $eNum" },
                    ),
                )
            }
        }

        // 3. Try modern router layout (a.mini-poster, etc.)
        if (episodes.isEmpty()) {
            val epLinks = doc.select("a.mini-poster, a[href*='/sezon-'], a[href*='bolum-'], a[href*='episode']")
            for (link in epLinks) {
                val href = link.attr("href").trim()
                if (href.isBlank()) continue

                val text = link.text().trim()
                val titleAttr = link.attr("title").trim()
                val label = text.ifBlank { titleAttr }

                val seasonRegex = Regex("""sezon-(\d+)""", RegexOption.IGNORE_CASE)
                val episodeRegex = Regex("""bolum-(\d+)""", RegexOption.IGNORE_CASE)

                var sNum = seasonRegex.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                var eNum = episodeRegex.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                if (sNum == null || eNum == null) {
                    val labelMatch = Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(label)
                    if (labelMatch != null) {
                        sNum = labelMatch.groupValues[1].toIntOrNull()
                        eNum = labelMatch.groupValues[2].toIntOrNull()
                    }
                }

                val finalSeason = sNum ?: 1
                val finalEpisode = eNum ?: (episodes.size + 1)
                val epUrl = link.absUrl("href").ifBlank { absolute(href) }

                episodes.add(
                    ProviderEpisode(
                        providerId = id,
                        seriesId = seriesUrl,
                        id = epUrl,
                        season = finalSeason,
                        episodeNumber = finalEpisode,
                        title = label.ifBlank { "Bölüm $finalEpisode" },
                    ),
                )
            }
        }

        return episodes.distinctBy { it.id }.sortedWith(
            compareBy<ProviderEpisode> { it.season }.thenBy { it.episodeNumber }
        )
    }

    private suspend fun executeHttp(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}")
            }
            response.body?.string().orEmpty()
        }
    }

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$activeDomain/")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} while fetching $url")
            }
            val body = resp.body?.string().orEmpty()
            Jsoup.parse(body, url)
        }
    }

    private suspend fun fetchWithHeaders(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        builder.header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }

        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} while fetching $url")
            }
            resp.body?.string().orEmpty()
        }
    }

    private suspend fun postAjax(url: String, formParams: Map<String, String>): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply {
            formParams.forEach { (k, v) -> add(k, v) }
        }.build()

        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$activeDomain/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} for POST $url")
            }
            resp.body?.string().orEmpty()
        }
    }

    private fun absolute(path: String): String =
        when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith('/') -> "$activeDomain$path"
            else -> "$activeDomain/$path"
        }

    private inline fun <T> guarded(operation: String, block: () -> T): ProviderResult<T> =
        try {
            ProviderResult.Success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (ioe: IOException) {
            logE("HdFilmCehennemiProvider", "[$operation] IO error: ${ioe.message}")
            failure(operation, ProviderErrorCode.NETWORK, ioe.message ?: "Network error during $operation", ioe)
        } catch (t: Throwable) {
            logE("HdFilmCehennemiProvider", "[$operation] unexpected failure: ${t.message}")
            failure(operation, ProviderErrorCode.PARSE, t.message ?: "Error during $operation", t)
        }

    private fun failure(
        operation: String,
        code: ProviderErrorCode,
        message: String,
        cause: Throwable? = null,
    ): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError(id, operation, code, message, cause))

    private fun logE(tag: String, msg: String) {
        runCatching { Log.e(tag, msg) }
    }

    private fun logW(tag: String, msg: String) {
        runCatching { Log.w(tag, msg) }
    }

    companion object {
        const val PROVIDER_ID = "hdfilmcehennemi"
        const val MAIN_URL = "https://www.hdfilmcehennemi.nl"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PLAYBACK_TTL_MILLIS = 10 * 60 * 1000L // 10 minutes
    }
}
