package app.opentv.data.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun supporting_filtersByMediaType() {
        val movie = FakeProvider("movie", setOf(ProviderMediaType.MOVIE))
        val series = FakeProvider("series", setOf(ProviderMediaType.SERIES))
        val registry = ProviderRegistry(listOf(movie, series))

        assertThat(registry.supporting(ProviderMediaType.MOVIE)).containsExactly(movie)
        assertThat(registry.supporting(ProviderMediaType.SERIES)).containsExactly(series)
    }

    @Test
    fun duplicateId_isRejected() {
        val registry = ProviderRegistry()
        registry.register(FakeProvider("same", setOf(ProviderMediaType.MOVIE)))

        val error = runCatching {
            registry.register(FakeProvider("same", setOf(ProviderMediaType.SERIES)))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(registry.all()).hasSize(1)
    }

    private class FakeProvider(
        override val id: String,
        override val supportedMediaTypes: Set<ProviderMediaType>,
    ) : Provider {
        override val name: String = id
        override suspend fun catalog(request: ProviderCatalogRequest) =
            ProviderResult.Success(ProviderCatalogPage(id, emptyList()))
        override suspend fun search(request: ProviderSearchRequest) =
            ProviderResult.Success(emptyList<ProviderItem>())
        override suspend fun load(itemId: String) =
            ProviderResult.Failure(error("load"))
        override suspend fun episodes(seriesId: String) =
            ProviderResult.Success(emptyList<ProviderEpisode>())
        override suspend fun streams(target: ProviderPlaybackTarget) =
            ProviderResult.Success(emptyList<ProviderStream>())
        override suspend fun subtitles(target: ProviderPlaybackTarget) =
            ProviderResult.Success(emptyList<ProviderSubtitle>())

        private fun error(operation: String) = ProviderError(
            providerId = id,
            operation = operation,
            code = ProviderErrorCode.NOT_FOUND,
            message = "not found",
        )
    }
}
