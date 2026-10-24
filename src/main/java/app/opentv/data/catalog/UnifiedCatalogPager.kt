package app.opentv.data.catalog

import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderError
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderRepository
import app.opentv.data.provider.ProviderResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Provider-independent catalogue pager.
 *
 * Each provider keeps its own native page counter. The UI only sees one accumulated canonical
 * catalogue plus [hasMore], so provider pagination never leaks into the product surface.
 */
class UnifiedCatalogPager internal constructor(
    private val repository: ProviderRepository,
    private val matcher: ContentMatcher,
    private val mediaType: ProviderMediaType,
    private val sectionId: String?,
) {
    private val rawItems = linkedMapOf<String, ProviderItem>()
    private val seenByProvider = linkedMapOf<String, MutableSet<String>>()
    private val nextPages = linkedMapOf<String, Int?>()
    private val failures = linkedMapOf<String, ProviderError>()
    private var initialized = false

    val hasMore: Boolean
        get() = initialized && nextPages.values.any { it != null }

    suspend fun loadInitial(): UnifiedCatalogPage {
        rawItems.clear()
        seenByProvider.clear()
        nextPages.clear()
        failures.clear()

        val providers = repository.providers(mediaType)
        val results = coroutineScope {
            providers.map { provider ->
                async {
                    provider.id to repository.catalog(
                        provider.id,
                        ProviderCatalogRequest(
                            mediaType = mediaType,
                            sectionId = sectionId,
                            page = 1,
                        ),
                    )
                }
            }.awaitAll()
        }

        results.forEach { (providerId, result) ->
            applyProviderPage(
                providerId = providerId,
                requestedPage = 1,
                result = result,
            )
        }
        initialized = true
        return snapshot()
    }

    suspend fun loadMore(): UnifiedCatalogPage {
        if (!initialized) return loadInitial()

        val active = nextPages
            .mapNotNull { (providerId, page) -> page?.let { providerId to it } }
        if (active.isEmpty()) return snapshot()

        val results = coroutineScope {
            active.map { (providerId, page) ->
                async {
                    Triple(
                        providerId,
                        page,
                        repository.catalog(
                            providerId,
                            ProviderCatalogRequest(
                                mediaType = mediaType,
                                sectionId = sectionId,
                                page = page,
                            ),
                        ),
                    )
                }
            }.awaitAll()
        }

        results.forEach { (providerId, page, result) ->
            applyProviderPage(providerId, page, result)
        }
        return snapshot()
    }

    private fun applyProviderPage(
        providerId: String,
        requestedPage: Int,
        result: ProviderResult<app.opentv.data.provider.ProviderCatalogPage>,
    ) {
        when (result) {
            is ProviderResult.Success -> {
                failures.remove(providerId)
                val seen = seenByProvider.getOrPut(providerId) { linkedSetOf() }
                var added = 0
                result.value.items.forEach { item ->
                    val key = item.id
                    if (seen.add(key)) {
                        rawItems[providerId + ":" + key] = item
                        added += 1
                    }
                }

                val candidate = result.value.nextPage
                nextPages[providerId] =
                    candidate?.takeIf { added > 0 && it > requestedPage }
            }

            is ProviderResult.Failure -> {
                failures[providerId] = result.error
                // A broken provider must not keep infinite-scroll alive forever. The next full
                // refresh can retry it; this pager continues with healthy providers only.
                nextPages[providerId] = null
            }
        }
    }

    private fun snapshot(): UnifiedCatalogPage =
        UnifiedCatalogPage(
            items = matcher.merge(rawItems.values),
            hasMore = nextPages.values.any { it != null },
            failures = failures.toMap(),
        )
}

data class UnifiedCatalogPage(
    val items: List<CanonicalContent>,
    val hasMore: Boolean,
    val failures: Map<String, ProviderError> = emptyMap(),
)
