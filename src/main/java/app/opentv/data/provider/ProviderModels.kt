/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider

/** Media kinds understood by the provider layer. */
enum class ProviderMediaType {
    MOVIE,
    SERIES,
    OTHER,
}

data class ProviderCatalogSection(
    val id: String,
    val title: String,
    val mediaType: ProviderMediaType,
    val showOnHome: Boolean = true,
)

data class ProviderCatalogRequest(
    val mediaType: ProviderMediaType,
    val sectionId: String? = null,
    val page: Int = 1,
)

data class ProviderSearchRequest(
    val query: String,
    val mediaType: ProviderMediaType? = null,
)

data class ProviderCatalogPage(
    val title: String,
    val items: List<ProviderItem>,
    val nextPage: Int? = null,
)

data class ProviderItem(
    val providerId: String,
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
)

data class ProviderDetails(
    val item: ProviderItem,
    val description: String? = item.description,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val durationSeconds: Int? = null,
    val externalIds: Map<String, String> = emptyMap(),
)

data class ProviderEpisode(
    val providerId: String,
    val seriesId: String,
    val id: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val description: String? = null,
    val stillUrl: String? = null,
    val durationSeconds: Int? = null,
)

sealed interface ProviderPlaybackTarget {
    val providerId: String

    data class Movie(
        override val providerId: String,
        val itemId: String,
    ) : ProviderPlaybackTarget

    data class Episode(
        override val providerId: String,
        val seriesId: String,
        val episodeId: String,
    ) : ProviderPlaybackTarget
}

data class ProviderStream(
    val providerId: String,
    val url: String,
    val label: String = "Stream",
    val quality: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class ProviderSubtitle(
    val providerId: String,
    val url: String,
    val label: String,
    val language: String? = null,
    val mimeType: String? = null,
)

enum class ProviderErrorCode {
    NETWORK,
    PARSE,
    NOT_FOUND,
    UNSUPPORTED,
    INVALID_DATA,
    UNKNOWN,
}

data class ProviderError(
    val providerId: String,
    val operation: String,
    val code: ProviderErrorCode,
    val message: String,
    val cause: Throwable? = null,
)

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data class Failure(val error: ProviderError) : ProviderResult<Nothing>
}

fun <T> ProviderResult<T>.getOrNull(): T? =
    (this as? ProviderResult.Success<T>)?.value


data class ProviderBatchResult<T>(
    val values: Map<String, T>,
    val failures: Map<String, ProviderError>,
)
