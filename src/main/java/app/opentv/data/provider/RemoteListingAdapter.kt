package app.opentv.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI

/** Strict CSS-only catalogue adapter; opt-in per section, no scripts or arbitrary code. */
class RemoteListingAdapter(private val http: OkHttpClient) {
    suspend fun catalog(
        providerId: String,
        request: ProviderCatalogRequest,
        rule: RemoteListingRule,
    ): ProviderResult<ProviderCatalogPage> = withContext(Dispatchers.IO) {
        try {
            if (rule.mediaType != request.mediaType) error("Media type mismatch")
            val domain = ProviderRuntimeRules.domain(providerId)
                ?: "https://" + (ProviderRuntimeRules.defaultDomains[providerId]
                    ?: error("Provider domain required"))
            val path = rule.pathTemplate.replace("{page}", request.page.toString())
            val pageUrl = domain.trimEnd('/') + path
            val response = http.newCall(Request.Builder().url(pageUrl).get().build()).execute()
            val html = response.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                it.body?.byteStream()?.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 1_500_000) error("Listing HTML too large")
                        out.write(buffer, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                } ?: error("Empty response body")
            }
            val doc = Jsoup.parse(html, pageUrl)
            val domainHost = URI(domain).host
            val items = doc.select(rule.itemSelector).take(120).mapNotNull { element ->
                val link = if (rule.linkSelector == ":self") element else element.selectFirst(rule.linkSelector)
                val href = link?.absUrl("href").orEmpty().ifBlank {
                    link?.attr("href")?.let { URI(pageUrl).resolve(it).toString() }.orEmpty()
                }
                val uri = runCatching { URI(href) }.getOrNull()
                if (uri?.scheme != "https" && uri?.scheme != "http") return@mapNotNull null
                // Do not import third-party navigation / CDN links as content IDs.
                if (uri.host?.lowercase() != domainHost.lowercase()) return@mapNotNull null
                val title = (if (rule.titleSelector == ":self") element else element.selectFirst(rule.titleSelector))
                    ?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val poster = rule.imageSelector?.let { selector ->
                    val img = element.selectFirst(selector)
                    (img?.absUrl("src").orEmpty()).ifBlank { img?.absUrl("data-src").orEmpty() }
                        .ifBlank { img?.absUrl("data-original").orEmpty() }
                }?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ProviderItem(
                    providerId = providerId,
                    id = href,
                    title = title.take(240),
                    mediaType = request.mediaType,
                    posterUrl = poster,
                )
            }.distinctBy { it.id }
            if (items.isEmpty()) error("Configured selector returned no valid items")
            val hasNext = rule.nextSelector?.let { selector ->
                doc.select(selector).any { element ->
                    val href = element.absUrl("href")
                    href.isNotBlank() && href != pageUrl
                }
            } ?: false
            ProviderResult.Success(
                ProviderCatalogPage(
                    title = "Remote catalogue",
                    items = items,
                    nextPage = if (hasNext) request.page + 1 else null,
                ),
            )
        } catch (e: Exception) {
            ProviderResult.Failure(
                ProviderError(
                    providerId = providerId, operation = "remoteCatalog",
                    code = ProviderErrorCode.PARSE,
                    message = e.message ?: "Remote listing failed",
                    cause = e,
                ),
            )
        }
    }
}
