package app.opentv.data.catalog

import app.opentv.data.provider.ProviderError
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType

/**
 * One provider-independent title shown to the user.
 *
 * Provider-specific ids stay in [sources], so a provider may change domains or disappear without
 * changing the product-facing content model.
 */
data class CanonicalContent(
    val id: String,
    val title: String,
    val mediaType: ProviderMediaType,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val externalIds: Map<String, String> = emptyMap(),
    val sources: List<ContentSource>,
)

data class ContentSource(
    val providerId: String,
    val itemId: String,
    val title: String,
    val mediaType: ProviderMediaType,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val externalIds: Map<String, String> = emptyMap(),
)

data class UnifiedCatalogResult(
    val items: List<CanonicalContent>,
    val failures: Map<String, ProviderError> = emptyMap(),
)

internal fun ProviderItem.toContentSource(): ContentSource =
    ContentSource(
        providerId = providerId,
        itemId = id,
        title = title,
        mediaType = mediaType,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        description = description,
        year = year,
        rating = rating,
        genres = genres,
        externalIds = externalIds,
    )
