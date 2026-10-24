package app.opentv.data.provider

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderRepositoryTest {
    @Test
    fun catalog_filtersWrongTypeAndBadRows_andStampsProviderId() = runTest {
        val provider = FakeProvider(
            id = "alpha",
            catalogItems = listOf(
                ProviderItem("wrong", "movie-1", "Film", ProviderMediaType.MOVIE),
                ProviderItem("wrong", "series-1", "Dizi", ProviderMediaType.SERIES),
                ProviderItem("wrong", "", "Bozuk", ProviderMediaType.MOVIE),
            ),
        )
        val repository = ProviderRepository(ProviderRegistry(listOf(provider)))

        val result = repository.catalog(
            "alpha",
            ProviderCatalogRequest(ProviderMediaType.MOVIE),
        ) as ProviderResult.Success

        assertThat(result.value.items).hasSize(1)
        assertThat(result.value.items.single().id).isEqualTo("movie-1")
        assertThat(result.value.items.single().providerId).isEqualTo("alpha")
    }

    @Test
    fun searchAll_keepsHealthyProviderWhenAnotherThrows() = runTest {
        val good = FakeProvider(
            id = "good",
            searchItems = listOf(
                ProviderItem("", "1", "İçerik", ProviderMediaType.MOVIE),
            ),
        )
        val broken = FakeProvider(id = "broken", throwOnSearch = true)
        val repository = ProviderRepository(ProviderRegistry(listOf(good, broken)))

        val result = repository.searchAll(
            ProviderSearchRequest("icerik", ProviderMediaType.MOVIE),
        )

        assertThat(result.values.keys).containsExactly("good")
        assertThat(result.values.getValue("good").single().providerId).isEqualTo("good")
        assertThat(result.failures.keys).containsExactly("broken")
        assertThat(result.failures.getValue("broken").code).isEqualTo(ProviderErrorCode.UNKNOWN)
    }

    @Test
    fun streams_dropsInvalidRows_andStampsProviderId() = runTest {
        val provider = FakeProvider(
            id = "alpha",
            streamItems = listOf(
                ProviderStream("", "https://example.test/video.m3u8", "1080p"),
                ProviderStream("", "", "broken"),
            ),
        )
        val repository = ProviderRepository(ProviderRegistry(listOf(provider)))

        val result = repository.streams(
            ProviderPlaybackTarget.Movie("alpha", "movie-1"),
        ) as ProviderResult.Success

        assertThat(result.value).hasSize(1)
        assertThat(result.value.single().providerId).isEqualTo("alpha")
        assertThat(result.value.single().url).contains("video.m3u8")
    }

    private class FakeProvider(
        override val id: String,
        private val catalogItems: List<ProviderItem> = emptyList(),
        private val searchItems: List<ProviderItem> = emptyList(),
        private val streamItems: List<ProviderStream> = emptyList(),
        private val throwOnSearch: Boolean = false,
    ) : Provider {
        override val name: String = id
        override val supportedMediaTypes =
            setOf(ProviderMediaType.MOVIE, ProviderMediaType.SERIES)

        override suspend fun catalog(request: ProviderCatalogRequest) =
            ProviderResult.Success(ProviderCatalogPage("Test", catalogItems))

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
            ProviderResult.Success(streamItems)

        override suspend fun subtitles(target: ProviderPlaybackTarget) =
            ProviderResult.Success(emptyList<ProviderSubtitle>())
    }
}
