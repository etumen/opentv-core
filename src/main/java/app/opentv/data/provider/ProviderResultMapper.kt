/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider

/**
 * Normalises provider-owned values at the boundary.
 *
 * Bad rows are dropped instead of poisoning the whole catalogue. Provider ids are stamped by the
 * host so one provider cannot accidentally return objects attributed to another provider.
 */
internal object ProviderResultMapper {
    fun catalog(
        provider: Provider,
        page: ProviderCatalogPage,
        requestedType: ProviderMediaType,
    ): ProviderCatalogPage =
        page.copy(items = items(provider, page.items, requestedType))

    fun items(
        provider: Provider,
        values: List<ProviderItem>,
        requestedType: ProviderMediaType? = null,
    ): List<ProviderItem> =
        values.mapNotNull { value ->
            if (value.id.isBlank() || value.title.isBlank()) return@mapNotNull null
            if (requestedType != null && value.mediaType != requestedType) return@mapNotNull null
            value.copy(providerId = provider.id)
        }

    fun details(provider: Provider, value: ProviderDetails): ProviderDetails? {
        val item = items(provider, listOf(value.item)).singleOrNull() ?: return null
        return value.copy(item = item)
    }

    fun episodes(
        provider: Provider,
        seriesId: String,
        values: List<ProviderEpisode>,
    ): List<ProviderEpisode> =
        values.mapNotNull { value ->
            if (value.id.isBlank() || value.episodeNumber < 0 || value.season < 0) {
                return@mapNotNull null
            }
            value.copy(providerId = provider.id, seriesId = seriesId)
        }

    fun streams(provider: Provider, values: List<ProviderStream>): List<ProviderStream> =
        values.mapNotNull { value ->
            if (value.url.isBlank()) return@mapNotNull null
            value.copy(providerId = provider.id)
        }

    fun subtitles(provider: Provider, values: List<ProviderSubtitle>): List<ProviderSubtitle> =
        values.mapNotNull { value ->
            if (value.url.isBlank() || value.label.isBlank()) return@mapNotNull null
            value.copy(providerId = provider.id)
        }
}
