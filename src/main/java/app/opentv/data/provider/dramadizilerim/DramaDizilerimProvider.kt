/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.dramadizilerim

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Public Asian/short-drama provider.
 *
 * It intentionally does not participate in the default SERIES catalogue: callers opt in through
 * sectionId=asian so these vertical/short dramas stay in their own user-facing collection.
 */
class DramaDizilerimProvider(
    private val http: OkHttpClient,
) : Provider {
    override val id: String = PROVIDER_ID
    override val name: String = "DramaDizilerim"
    override val supportedMediaTypes: Set<ProviderMediaType> = setOf(ProviderMediaType.SERIES)

    override val catalogSections: List<ProviderCatalogSection> = listOf(
        ProviderCatalogSection(
            id = SECTION_ASIAN,
            title = "Asya / Drama",
            mediaType = ProviderMediaType.SERIES,
            showOnHome = true,
        ),
    )

    override suspend fun catalog(
        request: ProviderCatalogRequest,
    ): ProviderResult<ProviderCatalogPage> = guarded("catalog") {
        if (request.mediaType != ProviderMediaType.SERIES || request.sectionId != SECTION_ASIAN) {
            return@guarded ProviderCatalogPage(
                title = "Asya / Drama",
                items = emptyList(),
                nextPage = null,
            )
        }
        val page = request.page.coerceAtLeast(1)
        val target = MAIN_URL.toHttpUrl().newBuilder()
            .addPathSegment("dizi")
            .apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }
            .build()
            .toString()
        val items = parseCards(fetchDocument(target))
        ProviderCatalogPage(
            title = "Asya / Drama",
            items = items,
            nextPage = if (items.isEmpty()) null else page + 1,
        )
    }

    override suspend fun search(
        request: ProviderSearchRequest,
    ): ProviderResult<List<ProviderItem>> = guarded("search") {
        if (request.mediaType != null && request.mediaType != ProviderMediaType.SERIES) {
            return@guarded emptyList()
        }
        val query = request.query.trim()
        if (query.isBlank()) return@guarded emptyList()
        val target = MAIN_URL.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .build()
            .toString()
        parseCards(fetchDocument(target))
    }

    override suspend fun load(itemId: String): ProviderResult<ProviderDetails> = guarded("load") {
        val url = absolute(itemId)
        val doc = fetchDocument(url)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore('|').trim()
        if (title.isBlank()) throw IllegalArgumentException("Drama details title not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let(::absolute)
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".description, .ozet, .summary")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
        ProviderDetails(
            item = ProviderItem(
                providerId = id,
                id = url,
                title = title,
                mediaType = ProviderMediaType.SERIES,
                posterUrl = poster,
                description = description,
                genres = listOf("Asya / Drama"),
            ),
            description = description,
        )
    }

    override suspend fun episodes(
        seriesId: String,
    ): ProviderResult<List<ProviderEpisode>> = guarded("episodes") {
        parseEpisodes(fetchDocument(absolute(seriesId)), absolute(seriesId))
    }

    override suspend fun streams(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderStream>> {
        if (target !is ProviderPlaybackTarget.Episode || target.providerId != id) {
            return failure("streams", ProviderErrorCode.UNSUPPORTED, "Unsupported playback target")
        }
        return guarded("streams") {
            val watchUrl = absolute(target.episodeId)
            val watchDoc = fetchDocument(watchUrl)
            val episodeNumber = runCatching {
                watchUrl.toHttpUrl().queryParameter("e")?.toIntOrNull()
            }.getOrNull()

            val candidates = linkedSetOf<String>()
            watchDoc.select("[data-src], iframe[src]").forEach { element ->
                val raw = element.attr("data-src").ifBlank { element.attr("src") }
                val candidate = absoluteFrom(watchUrl, raw) ?: return@forEach
                if (!candidate.contains("embed", ignoreCase = true)) return@forEach
                candidates += candidate
            }
            val filtered = if (episodeNumber == null) {
                candidates.toList()
            } else {
                candidates.filter { candidate ->
                    runCatching {
                        candidate.toHttpUrl().queryParameter("episode")?.toIntOrNull() == episodeNumber
                    }.getOrDefault(false)
                }.ifEmpty { candidates.take(1) }
            }

            val streams = mutableListOf<ProviderStream>()
            for (embedUrl in filtered) {
                val html = fetchText(embedUrl, referer = watchUrl)
                val source = extractSource(html) ?: continue
                streams += ProviderStream(
                    providerId = id,
                    url = absoluteFrom(embedUrl, source) ?: source,
                    label = "Asya / Drama",
                    mimeType = if (source.contains(".m3u8", ignoreCase = true)) HLS_MIME else null,
                    headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to USER_AGENT,
                    ),
                )
            }
            streams.distinctBy { it.url }
        }
    }

    override suspend fun subtitles(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderSubtitle>> {
        if (target !is ProviderPlaybackTarget.Episode || target.providerId != id) {
            return ProviderResult.Success(emptyList())
        }
        return guarded("subtitles") {
            val watchUrl = absolute(target.episodeId)
            val watchDoc = fetchDocument(watchUrl)
            val episodeNumber = runCatching {
                watchUrl.toHttpUrl().queryParameter("e")?.toIntOrNull()
            }.getOrNull()
            val embedUrl = watchDoc.select("[data-src], iframe[src]")
                .asSequence()
                .mapNotNull { element ->
                    val raw = element.attr("data-src").ifBlank { element.attr("src") }
                    absoluteFrom(watchUrl, raw)
                }
                .filter { it.contains("embed", ignoreCase = true) }
                .firstOrNull { candidate ->
                    episodeNumber == null || runCatching {
                        candidate.toHttpUrl().queryParameter("episode")?.toIntOrNull() == episodeNumber
                    }.getOrDefault(false)
                } ?: return@guarded emptyList()

            val doc = Jsoup.parse(fetchText(embedUrl, referer = watchUrl), embedUrl)
            doc.select("track[src]").mapNotNull { track ->
                val url = absoluteFrom(embedUrl, track.attr("src")) ?: return@mapNotNull null
                ProviderSubtitle(
                    providerId = id,
                    url = url,
                    label = track.attr("label").ifBlank { "Türkçe" },
                    language = track.attr("srclang").ifBlank { "tr" },
                )
            }.distinctBy { it.url }
        }
    }

    internal fun parseCards(doc: Document): List<ProviderItem> =
        doc.select("a[href]").mapNotNull(::parseCard)
            .distinctBy { it.id }

    internal fun parseEpisodes(doc: Document, seriesId: String): List<ProviderEpisode> {
        val seen = linkedSetOf<String>()
        return doc.select("a[href*='/izle/']").mapNotNull { anchor ->
            val href = absoluteFrom(doc.baseUri().ifBlank { MAIN_URL }, anchor.attr("href"))
                ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            val parsed = runCatching { href.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
            val season = parsed.queryParameter("s")?.toIntOrNull() ?: 1
            val episode = parsed.queryParameter("e")?.toIntOrNull() ?: return@mapNotNull null
            val title = anchor.text().trim()
                .replace(Regex("""\s+"""), " ")
                .takeIf { it.isNotBlank() && !it.contains("Şimdi İzle", ignoreCase = true) }
                ?: "Bölüm $episode"
            ProviderEpisode(
                providerId = id,
                seriesId = seriesId,
                id = href,
                season = season,
                episodeNumber = episode,
                title = title,
            )
        }.sortedWith(compareBy<ProviderEpisode> { it.season }.thenBy { it.episodeNumber })
    }

    internal fun extractSource(html: String): String? =
        Regex("""let\s+source\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun parseCard(anchor: Element): ProviderItem? {
        val href = absoluteFrom(anchor.baseUri().ifBlank { MAIN_URL }, anchor.attr("href"))
            ?: return null
        if (!runCatching { href.toHttpUrl().encodedPath.contains("/dizi/") }.getOrDefault(false)) {
            return null
        }
        val image = anchor.selectFirst("img")
        val title = image?.attr("alt")?.trim()
            ?.removeSuffix(" İzle")
            ?.takeIf { it.isNotBlank() }
            ?: anchor.selectFirst(".title, h2, h3, h4")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = sequenceOf(
            image?.attr("src"),
            image?.attr("data-src"),
        ).filterNotNull()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { absoluteFrom(anchor.baseUri().ifBlank { MAIN_URL }, it) }

        return ProviderItem(
            providerId = id,
            id = href,
            title = title,
            mediaType = ProviderMediaType.SERIES,
            posterUrl = poster,
            genres = listOf("Asya / Drama"),
        )
    }

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchText(url, referer = MAIN_URL + "/"), url)

    private suspend fun fetchText(url: String, referer: String? = null): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.7")
                .apply {
                    if (!referer.isNullOrBlank()) header("Referer", referer)
                }
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("HTTP " + response.code + " for " + response.request.url)
                }
                body
            }
        }

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

    companion object {
        const val PROVIDER_ID = "dramadizilerim"
        const val SECTION_ASIAN = "asian"
        const val MAIN_URL = "https://dramadizilerim.com"
        private const val HLS_MIME = "application/x-mpegURL"
        private const val TAG = "DramaDizilerimProvider"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
