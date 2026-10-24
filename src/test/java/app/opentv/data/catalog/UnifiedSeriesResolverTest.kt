package app.opentv.data.catalog

import app.opentv.data.metadata.ContentMetadata
import app.opentv.data.metadata.MetadataIdentity
import app.opentv.data.metadata.MetadataRepository
import app.opentv.data.metadata.MetadataSource
import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogPage
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
import app.opentv.data.provider.ProviderError
import app.opentv.data.provider.ProviderErrorCode
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderRegistry
import app.opentv.data.provider.ProviderRepository
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderSearchRequest
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UnifiedSeriesResolverTest {
    @Test
    fun resolve_mergesEqualEpisodesAcrossProviders() = runTest {
        val resolver = resolver(
            FakeSeriesProvider("a", streamFails = false),
            FakeSeriesProvider("b", streamFails = false),
        )

        val result = resolver.resolve("a", "a-series") as ProviderResult.Success

        assertThat(result.value.content.sources.map { it.providerId })
            .containsExactly("a", "b")
        assertThat(result.value.episodes).hasSize(1)
        val episode = result.value.episodes.single()
        assertThat(episode.season).isEqualTo(1)
        assertThat(episode.episodeNumber).isEqualTo(1)
        assertThat(episode.sources.map { it.providerId }).containsExactly("a", "b")
    }

    @Test
    fun resolve_promotesPrimaryExternalIdToCanonicalIdentity() = runTest {
        val resolver = resolver(
            FakeSeriesProvider(
                "a",
                streamFails = false,
                detailExternalIds = mapOf("imdb" to "tt9288030"),
            ),
            FakeSeriesProvider("b", streamFails = false),
        )

        val result = resolver.resolve("a", "a-series") as ProviderResult.Success

        assertThat(result.value.content.id).isEqualTo("series:imdb:tt9288030")
        assertThat(result.value.content.externalIds)
            .containsEntry("imdb", "tt9288030")
        assertThat(
            result.value.content.sources.first { it.providerId == "a" }.externalIds,
        ).containsEntry("imdb", "tt9288030")
    }

    @Test
    fun resolve_enrichesMetadataFromExternalSourceFoundOnSecondaryProvider() = runTest {
        val providers = listOf(
            FakeSeriesProvider("a", streamFails = false),
            FakeSeriesProvider(
                "b",
                streamFails = false,
                detailExternalIds = mapOf("imdb" to "tt9288030"),
            ),
        )
        val repository = ProviderRepository(ProviderRegistry(providers))
        val catalog = CatalogAggregator(repository)
        val metadata = MetadataRepository(
            sources = listOf(
                object : MetadataSource {
                    override val id: String = "fake-imdb"

                    override suspend fun resolve(identity: MetadataIdentity): ContentMetadata? {
                        if (identity.externalIds["imdb"] != "tt9288030") return null
                        return ContentMetadata(
                            sourceId = id,
                            externalIds = mapOf("imdb" to "tt9288030"),
                            title = "Reacher",
                            year = 2022,
                            posterUrl = "https://metadata.test/reacher.jpg",
                            cast = listOf("Alan Ritchson", "Maria Sten"),
                        )
                    }
                },
            ),
        )
        val resolver = UnifiedSeriesResolver(
            repository = repository,
            catalog = catalog,
            metadata = metadata,
        )

        val result = resolver.resolve("a", "a-series") as ProviderResult.Success

        assertThat(result.value.content.id).isEqualTo("series:imdb:tt9288030")
        assertThat(result.value.content.posterUrl)
            .isEqualTo("https://metadata.test/reacher.jpg")
        assertThat(result.value.content.externalIds)
            .containsEntry("imdb", "tt9288030")
    }

    @Test
    fun playback_fallsBackToNextProviderWhenFirstSourceFails() = runTest {
        val resolver = resolver(
            FakeSeriesProvider("a", streamFails = true),
            FakeSeriesProvider("b", streamFails = false),
        )
        val series = (resolver.resolve("a", "a-series") as ProviderResult.Success).value
        val episode = series.episodes.single()

        val playback = resolver.playback(episode) as ProviderResult.Success

        assertThat(playback.value.source.providerId).isEqualTo("b")
        assertThat(playback.value.streams.single().url).isEqualTo("https://b.test/video.m3u8")
        assertThat(playback.value.subtitles.single().language).isEqualTo("tr")
    }

    @Test
    fun playback_prefersProviderWithBetterHealthScore() = runTest {
        val providers = listOf(
            FakeSeriesProvider("a", streamFails = false),
            FakeSeriesProvider("b", streamFails = false),
        )
        val repository = ProviderRepository(ProviderRegistry(providers))
        val catalog = CatalogAggregator(repository)
        val health = ProviderHealthTracker()
        health.recordFailure("a")
        health.recordSuccess("b")
        val resolver = UnifiedSeriesResolver(repository, catalog, health = health)

        val series = (resolver.resolve("a", "a-series") as ProviderResult.Success).value
        val playback = resolver.playback(series.episodes.single()) as ProviderResult.Success

        assertThat(playback.value.source.providerId).isEqualTo("b")
        assertThat(health.score("b")).isGreaterThan(health.score("a"))
    }

    private fun resolver(vararg providers: Provider): UnifiedSeriesResolver {
        val repository = ProviderRepository(ProviderRegistry(providers.toList()))
        val catalog = CatalogAggregator(repository)
        return UnifiedSeriesResolver(repository, catalog)
    }

    private class FakeSeriesProvider(
        override val id: String,
        private val streamFails: Boolean,
        private val searchExternalIds: Map<String, String> = emptyMap(),
        private val detailExternalIds: Map<String, String> = emptyMap(),
    ) : Provider {
        override val name: String = id
        override val supportedMediaTypes = setOf(ProviderMediaType.SERIES)

        override suspend fun catalog(request: ProviderCatalogRequest) =
            ProviderResult.Success(ProviderCatalogPage(name, emptyList()))

        override suspend fun search(request: ProviderSearchRequest) =
            ProviderResult.Success(
                listOf(
                    ProviderItem(
                        providerId = id,
                        id = id + "-series",
                        title = "Reacher",
                        mediaType = ProviderMediaType.SERIES,
                        year = 2022,
                        externalIds = searchExternalIds,
                    ),
                ),
            )

        override suspend fun load(itemId: String) =
            ProviderResult.Success(
                ProviderDetails(
                    item = ProviderItem(
                        providerId = id,
                        id = itemId,
                        title = "Reacher",
                        mediaType = ProviderMediaType.SERIES,
                        year = 2022,
                        externalIds = detailExternalIds,
                    ),
                    externalIds = detailExternalIds,
                ),
            )

        override suspend fun episodes(seriesId: String) =
            ProviderResult.Success(
                listOf(
                    ProviderEpisode(
                        providerId = id,
                        seriesId = seriesId,
                        id = id + "-s1e1",
                        season = 1,
                        episodeNumber = 1,
                        title = "Welcome to Margrave",
                    ),
                ),
            )

        override suspend fun streams(target: ProviderPlaybackTarget): ProviderResult<List<ProviderStream>> {
            if (streamFails) {
                return ProviderResult.Failure(
                    ProviderError(
                        providerId = id,
                        operation = "streams",
                        code = ProviderErrorCode.NETWORK,
                        message = "source failed",
                    ),
                )
            }
            return ProviderResult.Success(
                listOf(
                    ProviderStream(
                        providerId = id,
                        url = "https://" + id + ".test/video.m3u8",
                        mimeType = "application/x-mpegURL",
                    ),
                ),
            )
        }

        override suspend fun subtitles(target: ProviderPlaybackTarget) =
            ProviderResult.Success(
                listOf(
                    ProviderSubtitle(
                        providerId = id,
                        url = "https://" + id + ".test/tr.vtt",
                        label = "Türkçe",
                        language = "tr",
                        mimeType = "text/vtt",
                    ),
                ),
            )
    }
}
