/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider

/**
 * UI-independent contract for movie/series providers.
 *
 * Providers discover content and resolve playable URLs. They never own Compose UI or the player.
 * A broken provider must fail through [ProviderResult] instead of throwing into another provider's
 * work or into the UI.
 */
interface Provider {
    val id: String
    val name: String
    val supportedMediaTypes: Set<ProviderMediaType>
    val catalogSections: List<ProviderCatalogSection>
        get() = emptyList()

    suspend fun discoverCatalogSections(
        mediaType: ProviderMediaType,
    ): ProviderResult<List<ProviderCatalogSection>> =
        ProviderResult.Success(catalogSections.filter { it.mediaType == mediaType })

    suspend fun catalog(request: ProviderCatalogRequest): ProviderResult<ProviderCatalogPage>

    suspend fun search(request: ProviderSearchRequest): ProviderResult<List<ProviderItem>>

    suspend fun load(itemId: String): ProviderResult<ProviderDetails>

    suspend fun episodes(seriesId: String): ProviderResult<List<ProviderEpisode>>

    suspend fun streams(target: ProviderPlaybackTarget): ProviderResult<List<ProviderStream>>

    suspend fun subtitles(target: ProviderPlaybackTarget): ProviderResult<List<ProviderSubtitle>>
}
