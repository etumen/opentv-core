package app.opentv.data.catalog

import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderRepository
import app.opentv.data.provider.ProviderSearchRequest
import app.opentv.data.provider.ProviderMediaType

/**
 * Aggregates native providers into one user-facing catalogue.
 *
 * Provider failures are retained as diagnostics but do not suppress healthy providers.
 */
class CatalogAggregator(
    private val repository: ProviderRepository,
    private val matcher: ContentMatcher = ContentMatcher(),
) {
    suspend fun search(request: ProviderSearchRequest): UnifiedCatalogResult {
        val batch = repository.searchAll(request)
        return UnifiedCatalogResult(
            items = merge(batch.values.values.flatten()),
            failures = batch.failures,
        )
    }

    suspend fun catalog(request: ProviderCatalogRequest): UnifiedCatalogResult {
        val batch = if (request.sectionId == null) {
            repository.catalogAll(request)
        } else {
            repository.catalogAllSupportingSection(request)
        }
        return UnifiedCatalogResult(
            items = merge(batch.values.values.flatMap { it.items }),
            failures = batch.failures,
        )
    }

    fun pager(
        mediaType: ProviderMediaType,
        sectionId: String? = null,
    ): UnifiedCatalogPager =
        UnifiedCatalogPager(
            repository = repository,
            matcher = matcher,
            mediaType = mediaType,
            sectionId = sectionId,
        )

    fun merge(items: Iterable<ProviderItem>): List<CanonicalContent> =
        matcher.merge(items)
}
