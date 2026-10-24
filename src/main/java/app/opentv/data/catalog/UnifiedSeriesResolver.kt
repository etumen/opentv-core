package app.opentv.data.catalog

import app.opentv.data.metadata.MetadataIdentity
import app.opentv.data.metadata.MetadataRepository
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
import app.opentv.data.provider.ProviderError
import app.opentv.data.provider.ProviderErrorCode
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderRepository
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderSearchRequest
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class EpisodeSource(
    val providerId: String,
    val seriesId: String,
    val episodeId: String,
    val title: String,
)

data class CanonicalEpisode(
    val id: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val description: String? = null,
    val stillUrl: String? = null,
    val durationSeconds: Int? = null,
    val sources: List<EpisodeSource>,
)

data class UnifiedSeriesDetails(
    val details: ProviderDetails,
    val content: CanonicalContent,
    val episodes: List<CanonicalEpisode>,
    val failures: Map<String, ProviderError> = emptyMap(),
)

data class UnifiedEpisodePlayback(
    val source: EpisodeSource,
    val streams: List<ProviderStream>,
    val subtitles: List<ProviderSubtitle>,
)

/**
 * Resolves one logical series across all available providers.
 *
 * The route may still contain a provider-specific item id, but this resolver expands it back into
 * the canonical title, merges equal season/episode numbers, then hides source failover from UI.
 */
class UnifiedSeriesResolver(
    private val repository: ProviderRepository,
    private val catalog: CatalogAggregator,
    private val matcher: ContentMatcher = ContentMatcher(),
    private val health: ProviderHealthTracker = ProviderHealthTracker(),
    private val metadata: MetadataRepository? = null,
) {
    suspend fun resolve(
        providerId: String,
        itemId: String,
    ): ProviderResult<UnifiedSeriesDetails> {
        val primaryResult = repository.load(providerId, itemId)
        if (primaryResult is ProviderResult.Failure) return primaryResult
        val primary = (primaryResult as ProviderResult.Success).value

        val search = catalog.search(
            ProviderSearchRequest(
                query = primary.item.title,
                mediaType = ProviderMediaType.SERIES,
            ),
        )

        val matched = search.items.firstOrNull { candidate ->
            matcher.match(
                leftTitle = primary.item.title,
                leftType = primary.item.mediaType,
                leftYear = primary.item.year,
                rightTitle = candidate.title,
                rightType = candidate.mediaType,
                rightYear = candidate.year,
                leftExternalIds = primary.item.externalIds,
                rightExternalIds = candidate.externalIds,
            ).isMatch
        }

        var canonical = ensurePrimarySource(
            matched ?: catalog.merge(listOf(primary.item)).single(),
            primary.item,
        )

        val detailFailures = linkedMapOf<String, ProviderError>()
        val detailResults = coroutineScope {
            canonical.sources.map { source ->
                async {
                    source to if (
                        source.providerId == providerId &&
                        source.itemId == itemId
                    ) {
                        ProviderResult.Success(primary)
                    } else {
                        repository.load(source.providerId, source.itemId)
                    }
                }
            }.awaitAll()
        }

        val detailedItems = mutableListOf(primary.item)
        detailResults.forEach { (source, result) ->
            when (result) {
                is ProviderResult.Success -> detailedItems += result.value.item
                is ProviderResult.Failure -> detailFailures[source.providerId] = result.error
            }
        }
        val detailedCanonical = catalog.merge(detailedItems)
            .firstOrNull { content ->
                content.sources.any {
                    it.providerId == providerId && it.itemId == itemId
                }
            }
        if (detailedCanonical != null) {
            canonical = mergeCanonicalContent(canonical, detailedCanonical)
        }
        canonical = enrichMetadata(canonical)

        val orderedSources = canonical.sources.sortedWith(
            compareByDescending<ContentSource> { health.score(it.providerId) }
                .thenBy { source ->
                    if (source.providerId == providerId && source.itemId == itemId) 0 else 1
                },
        )

        val episodeResults = coroutineScope {
            orderedSources.map { source ->
                async {
                    source to repository.episodes(source.providerId, source.itemId)
                }
            }.awaitAll()
        }

        val failures = linkedMapOf<String, ProviderError>()
        failures.putAll(detailFailures)
        val episodeGroups = linkedMapOf<Pair<Int, Int>, MutableList<ProviderEpisode>>()
        episodeResults.forEach { (source, result) ->
            when (result) {
                is ProviderResult.Success -> result.value.forEach { episode ->
                    episodeGroups.getOrPut(episode.season to episode.episodeNumber) {
                        mutableListOf()
                    } += episode
                }
                is ProviderResult.Failure -> failures[source.providerId] = result.error
            }
        }

        val episodes = episodeGroups
            .map { (key, values) -> canonicalEpisode(key.first, key.second, values) }
            .sortedWith(compareBy<CanonicalEpisode> { it.season }.thenBy { it.episodeNumber })

        return ProviderResult.Success(
            UnifiedSeriesDetails(
                details = primary,
                content = canonical,
                episodes = episodes,
                failures = search.failures + failures,
            ),
        )
    }

    suspend fun playback(
        episode: CanonicalEpisode,
    ): ProviderResult<UnifiedEpisodePlayback> {
        var lastFailure: ProviderError? = null

        val orderedSources = episode.sources.sortedByDescending { health.score(it.providerId) }

        for (source in orderedSources) {
            val target = ProviderPlaybackTarget.Episode(
                providerId = source.providerId,
                seriesId = source.seriesId,
                episodeId = source.episodeId,
            )
            when (val streams = repository.streams(target)) {
                is ProviderResult.Success -> {
                    if (streams.value.isEmpty()) {
                        health.recordFailure(source.providerId)
                        continue
                    }
                    health.recordSuccess(source.providerId)
                    val subtitles = when (val result = repository.subtitles(target)) {
                        is ProviderResult.Success -> result.value
                        is ProviderResult.Failure -> emptyList()
                    }
                    return ProviderResult.Success(
                        UnifiedEpisodePlayback(
                            source = source,
                            streams = streams.value,
                            subtitles = subtitles,
                        ),
                    )
                }
                is ProviderResult.Failure -> {
                    health.recordFailure(source.providerId)
                    lastFailure = streams.error
                }
            }
        }

        return ProviderResult.Failure(
            lastFailure ?: ProviderError(
                providerId = episode.sources.firstOrNull()?.providerId.orEmpty(),
                operation = "streams",
                code = ProviderErrorCode.NOT_FOUND,
                message = "No playable source found for S" +
                    episode.season + "E" + episode.episodeNumber,
            ),
        )
    }

    private fun mergeCanonicalContent(
        base: CanonicalContent,
        detailed: CanonicalContent,
    ): CanonicalContent {
        val externalIds = base.externalIds + detailed.externalIds
        val title = detailed.title.ifBlank { base.title }
        val year = detailed.year ?: base.year
        return base.copy(
            id = matcher.canonicalId(
                mediaType = base.mediaType,
                title = title,
                year = year,
                externalIds = externalIds,
            ),
            title = title,
            posterUrl = detailed.posterUrl ?: base.posterUrl,
            backdropUrl = detailed.backdropUrl ?: base.backdropUrl,
            description = detailed.description ?: base.description,
            year = year,
            rating = detailed.rating ?: base.rating,
            genres = (detailed.genres + base.genres).distinct(),
            externalIds = externalIds,
            sources = (detailed.sources + base.sources)
                .distinctBy { it.providerId to it.itemId },
        )
    }

    private suspend fun enrichMetadata(content: CanonicalContent): CanonicalContent {
        val repository = metadata ?: return content
        if (content.externalIds.isEmpty()) return content
        val resolved = repository.resolve(
            MetadataIdentity(content.externalIds),
        ) ?: return content

        val externalIds = content.externalIds + resolved.externalIds
        val title = resolved.title?.takeIf { it.isNotBlank() } ?: content.title
        val year = resolved.year ?: content.year
        return content.copy(
            id = matcher.canonicalId(
                mediaType = content.mediaType,
                title = title,
                year = year,
                externalIds = externalIds,
            ),
            title = title,
            posterUrl = resolved.posterUrl ?: content.posterUrl,
            backdropUrl = resolved.backdropUrl ?: content.backdropUrl,
            description = resolved.description ?: content.description,
            year = year,
            rating = resolved.rating ?: content.rating,
            genres = if (resolved.genres.isNotEmpty()) {
                resolved.genres
            } else {
                content.genres
            },
            externalIds = externalIds,
        )
    }

    private fun ensurePrimarySource(
        content: CanonicalContent,
        primary: ProviderItem,
    ): CanonicalContent {
        val externalIds = content.externalIds + primary.externalIds
        return content.copy(
            id = matcher.canonicalId(
                mediaType = content.mediaType,
                title = content.title,
                year = content.year,
                externalIds = externalIds,
            ),
            externalIds = externalIds,
            sources = (listOf(primary.toContentSource()) + content.sources)
                .distinctBy { it.providerId to it.itemId },
        )
    }

    private fun canonicalEpisode(
        season: Int,
        episodeNumber: Int,
        values: List<ProviderEpisode>,
    ): CanonicalEpisode {
        val best = values.maxByOrNull(::episodeMetadataScore) ?: values.first()
        return CanonicalEpisode(
            id = "s" + season + "e" + episodeNumber,
            season = season,
            episodeNumber = episodeNumber,
            title = best.title,
            description = values.mapNotNull { it.description?.takeIf(String::isNotBlank) }
                .maxByOrNull(String::length),
            stillUrl = values.firstNotNullOfOrNull { it.stillUrl?.takeIf(String::isNotBlank) },
            durationSeconds = values.mapNotNull { it.durationSeconds }.maxOrNull(),
            sources = values
                .map {
                    EpisodeSource(
                        providerId = it.providerId,
                        seriesId = it.seriesId,
                        episodeId = it.id,
                        title = it.title,
                    )
                }
                .distinctBy { Triple(it.providerId, it.seriesId, it.episodeId) },
        )
    }

    private fun episodeMetadataScore(episode: ProviderEpisode): Int =
        (if (!episode.description.isNullOrBlank()) 2 else 0) +
            (if (!episode.stillUrl.isNullOrBlank()) 1 else 0) +
            (if (episode.durationSeconds != null) 1 else 0) +
            (if (episode.title.isNotBlank()) 1 else 0)
}
