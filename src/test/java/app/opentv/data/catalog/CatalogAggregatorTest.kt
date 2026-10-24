package app.opentv.data.catalog

import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogPage
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
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

class CatalogAggregatorTest {
    @Test
    fun search_mergesSameSeriesFromMultipleProviders() = runTest {
        val repository = ProviderRepository(
            ProviderRegistry(
                listOf(
                    FakeProvider(
                        id = "dizipal",
                        searchItems = listOf(
                            ProviderItem(
                                providerId = "",
                                id = "dp-reacher",
                                title = "Reacher",
                                mediaType = ProviderMediaType.SERIES,
                                year = 2022,
                                posterUrl = "https://images.test/reacher.jpg",
                            ),
                        ),
                    ),
                    FakeProvider(
                        id = "other",
                        searchItems = listOf(
                            ProviderItem(
                                providerId = "",
                                id = "other-reacher",
                                title = "Reacher (2022)",
                                mediaType = ProviderMediaType.SERIES,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = CatalogAggregator(repository).search(
            ProviderSearchRequest("Reacher", ProviderMediaType.SERIES),
        )

        assertThat(result.items).hasSize(1)
        val reacher = result.items.single()
        assertThat(reacher.title).contains("Reacher")
        assertThat(reacher.year).isEqualTo(2022)
        assertThat(reacher.sources.map { it.providerId }).containsExactly("dizipal", "other")
        assertThat(reacher.sources.map { it.itemId })
            .containsExactly("dp-reacher", "other-reacher")
    }

    @Test
    fun merge_keepsSameTitleWithDifferentYearsSeparate() {
        val items = listOf(
            ProviderItem("a", "dune-1984", "Dune", ProviderMediaType.MOVIE, year = 1984),
            ProviderItem("b", "dune-2021", "Dune", ProviderMediaType.MOVIE, year = 2021),
        )

        val result = ContentMatcher().merge(items)

        assertThat(result).hasSize(2)
        assertThat(result.map { it.year }).containsExactly(1984, 2021)
    }

    @Test
    fun merge_doesNotBridgeConflictingYearsThroughUnknownYear() {
        val items = listOf(
            ProviderItem("unknown", "reacher-x", "Reacher", ProviderMediaType.SERIES),
            ProviderItem("modern", "reacher-2022", "Reacher", ProviderMediaType.SERIES, year = 2022),
            ProviderItem("old", "reacher-1984", "Reacher", ProviderMediaType.SERIES, year = 1984),
        )

        val result = ContentMatcher().merge(items)

        assertThat(result).hasSize(2)
        assertThat(result.mapNotNull { it.year }).containsExactly(2022, 1984)
    }

    @Test
    fun merge_doesNotMixMovieAndSeriesWithSameTitle() {
        val items = listOf(
            ProviderItem("a", "same-movie", "Same", ProviderMediaType.MOVIE, year = 2022),
            ProviderItem("b", "same-series", "Same", ProviderMediaType.SERIES, year = 2022),
        )

        assertThat(ContentMatcher().merge(items)).hasSize(2)
    }

    @Test
    fun merge_prefersSharedExternalIdOverTitleDifferences() {
        val items = listOf(
            ProviderItem(
                "a",
                "reacher-a",
                "Reacher",
                ProviderMediaType.SERIES,
                year = 2022,
                externalIds = mapOf("imdb" to "tt9288030"),
            ),
            ProviderItem(
                "b",
                "reacher-b",
                "Jack Reacher Dizisi",
                ProviderMediaType.SERIES,
                externalIds = mapOf("IMDB" to "TT9288030"),
            ),
        )

        val result = ContentMatcher().merge(items)

        assertThat(result).hasSize(1)
        assertThat(result.single().id).isEqualTo("series:imdb:tt9288030")
        assertThat(result.single().externalIds).containsEntry("imdb", "tt9288030")
        assertThat(result.single().sources).hasSize(2)
    }

    @Test
    fun merge_keepsConflictingExternalIdsSeparate() {
        val items = listOf(
            ProviderItem(
                "a",
                "same-a",
                "Same",
                ProviderMediaType.SERIES,
                year = 2022,
                externalIds = mapOf("imdb" to "tt1111111"),
            ),
            ProviderItem(
                "b",
                "same-b",
                "Same",
                ProviderMediaType.SERIES,
                year = 2022,
                externalIds = mapOf("imdb" to "tt2222222"),
            ),
        )

        assertThat(ContentMatcher().merge(items)).hasSize(2)
    }

    @Test
    fun search_keepsHealthyResultsWhenProviderFails() = runTest {
        val repository = ProviderRepository(
            ProviderRegistry(
                listOf(
                    FakeProvider(
                        id = "healthy",
                        searchItems = listOf(
                            ProviderItem("", "1", "Reacher", ProviderMediaType.SERIES, year = 2022),
                        ),
                    ),
                    FakeProvider(id = "broken", throwOnSearch = true),
                ),
            ),
        )

        val result = CatalogAggregator(repository).search(
            ProviderSearchRequest("Reacher", ProviderMediaType.SERIES),
        )

        assertThat(result.items).hasSize(1)
        assertThat(result.items.single().sources.single().providerId).isEqualTo("healthy")
        assertThat(result.failures.keys).containsExactly("broken")
    }

    @Test
    fun pager_mergesSourcesAcrossDifferentProviderPages() = runTest {
        val repository = ProviderRepository(
            ProviderRegistry(
                listOf(
                    FakeProvider(
                        id = "a",
                        catalogPages = mapOf(
                            1 to listOf(
                                ProviderItem("", "a-reacher", "Reacher", ProviderMediaType.SERIES, year = 2022),
                            ),
                            2 to listOf(
                                ProviderItem("", "a-fallout", "Fallout", ProviderMediaType.SERIES, year = 2024),
                            ),
                        ),
                        nextPages = mapOf(1 to 2, 2 to null),
                    ),
                    FakeProvider(
                        id = "b",
                        catalogPages = mapOf(
                            1 to listOf(
                                ProviderItem("", "b-silo", "Silo", ProviderMediaType.SERIES, year = 2023),
                            ),
                            2 to listOf(
                                ProviderItem("", "b-reacher", "Reacher (2022)", ProviderMediaType.SERIES),
                            ),
                        ),
                        nextPages = mapOf(1 to 2, 2 to null),
                    ),
                ),
            ),
        )
        val pager = CatalogAggregator(repository).pager(ProviderMediaType.SERIES)

        val first = pager.loadInitial()
        assertThat(first.hasMore).isTrue()
        assertThat(first.items.map { it.title }).containsAtLeast("Reacher", "Silo")
        assertThat(first.items.first { it.title.contains("Reacher") }.sources).hasSize(1)

        val second = pager.loadMore()
        assertThat(second.hasMore).isFalse()
        assertThat(second.items.map { it.title }).containsAtLeast("Reacher", "Silo", "Fallout")
        assertThat(second.items.first { it.title.contains("Reacher") }.sources).hasSize(2)
    }

    @Test
    fun pager_stopsProviderWhenPageAddsNoNewItems() = runTest {
        val repeated = ProviderItem("", "same", "Same", ProviderMediaType.SERIES, year = 2025)
        val repository = ProviderRepository(
            ProviderRegistry(
                listOf(
                    FakeProvider(
                        id = "repeat",
                        catalogPages = mapOf(1 to listOf(repeated), 2 to listOf(repeated)),
                        nextPages = mapOf(1 to 2, 2 to 3),
                    ),
                ),
            ),
        )
        val pager = CatalogAggregator(repository).pager(ProviderMediaType.SERIES)

        assertThat(pager.loadInitial().hasMore).isTrue()
        val second = pager.loadMore()
        assertThat(second.items).hasSize(1)
        assertThat(second.hasMore).isFalse()
    }

    @Test
    fun matcher_normalizesTurkishCharactersAndPunctuation() {
        val items = listOf(
            ProviderItem("a", "1", "Çağrı: İstanbul", ProviderMediaType.MOVIE, year = 2024),
            ProviderItem("b", "2", "Cagri Istanbul (2024)", ProviderMediaType.MOVIE),
        )

        val result = ContentMatcher().merge(items)

        assertThat(result).hasSize(1)
        assertThat(result.single().sources).hasSize(2)
    }

    private class FakeProvider(
        override val id: String,
        private val searchItems: List<ProviderItem> = emptyList(),
        private val throwOnSearch: Boolean = false,
        private val catalogPages: Map<Int, List<ProviderItem>> = emptyMap(),
        private val nextPages: Map<Int, Int?> = emptyMap(),
    ) : Provider {
        override val name: String = id
        override val supportedMediaTypes =
            setOf(ProviderMediaType.MOVIE, ProviderMediaType.SERIES)

        override suspend fun catalog(request: ProviderCatalogRequest) =
            ProviderResult.Success(
                ProviderCatalogPage(
                    title = "Test",
                    items = catalogPages[request.page] ?: searchItems,
                    nextPage = nextPages[request.page],
                ),
            )

        override suspend fun search(request: ProviderSearchRequest): ProviderResult<List<ProviderItem>> {
            if (throwOnSearch) error("boom")
            return ProviderResult.Success(searchItems)
        }

        override suspend fun load(itemId: String) =
            ProviderResult.Success(
                ProviderDetails(
                    ProviderItem(id, itemId, "Item", ProviderMediaType.MOVIE),
                ),
            )

        override suspend fun episodes(seriesId: String) =
            ProviderResult.Success(emptyList<ProviderEpisode>())

        override suspend fun streams(target: ProviderPlaybackTarget) =
            ProviderResult.Success(emptyList<ProviderStream>())

        override suspend fun subtitles(target: ProviderPlaybackTarget) =
            ProviderResult.Success(emptyList<ProviderSubtitle>())
    }
}
