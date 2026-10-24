package app.opentv.data.metadata

import kotlinx.serialization.Serializable

data class MetadataIdentity(
    val externalIds: Map<String, String>,
)

@Serializable
data class ContentMetadata(
    val sourceId: String,
    val externalIds: Map<String, String>,
    val title: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
)
