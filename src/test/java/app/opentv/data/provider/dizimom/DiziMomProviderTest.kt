/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.dizimom

import app.opentv.data.provider.ProviderMediaType
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class DiziMomProviderTest {

    private val provider = DiziMomProvider(OkHttpClient())
    @Test
    fun latestEpisodes_mapsHomePageEpisodesToSeriesCards() {
        val html = """
            <section><h2>Son Eklenen Bölümler</h2>
                <a href="/dizi/nocturne/sezon-1/bolum-1">
                    <img alt="Nocturne 1. Sezon 1. Bölüm" src="/thumb.webp">
                </a>
                <a href="/dizi/nocturne/sezon-1/bolum-2">
                    <img alt="Nocturne 1. Sezon 2. Bölüm" src="/thumb2.webp">
                </a>
            </section>
        """.trimIndent()
        val items = provider.parseLatestEpisodeSeries(Jsoup.parse(html, "https://ddizimom.net/"))
        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Nocturne")
        assertThat(items.single().id).isEqualTo("https://ddizimom.net/dizi/nocturne")
    }


    @Test
    fun providerConfig_isSeriesOnly() {
        assertThat(provider.id).isEqualTo("dizimom")
        assertThat(provider.name).isEqualTo("DiziMom")
        assertThat(provider.supportedMediaTypes).containsExactly(ProviderMediaType.SERIES)
        assertThat(provider.catalogSections).isNotEmpty()
    }

    @Test
    fun parseCards_extractsSeriesData() {
        val html = """
            <div class="grid">
                <a href="https://ddizimom.net/dizi/game-of-thrones" class="group">
                    <img src="https://ddizimom.net/storage/tmdb/posters/w342/1XS1oqL89opfnbLl8WnZY1O1uJx.webp" alt="Game of Thrones">
                    <span class="score">8.5</span>
                    <h2 class="title">Game of Thrones</h2>
                </a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://ddizimom.net/diziler")
        val items = provider.parseCards(doc)

        assertThat(items).hasSize(1)
        val item = items.first()
        assertThat(item.id).isEqualTo("https://ddizimom.net/dizi/game-of-thrones")
        assertThat(item.title).isEqualTo("Game of Thrones")
        assertThat(item.mediaType).isEqualTo(ProviderMediaType.SERIES)
        assertThat(item.rating).isWithin(0.001).of(8.5)
        assertThat(item.posterUrl).isEqualTo("https://ddizimom.net/storage/tmdb/posters/w342/1XS1oqL89opfnbLl8WnZY1O1uJx.webp")
    }

    @Test
    fun parseHasNextPage_detectsPagination() {
        val html = """
            <nav class="pagination">
                <a href="https://ddizimom.net/diziler?page=2">2</a>
                <a href="https://ddizimom.net/diziler?page=3">3</a>
            </nav>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://ddizimom.net/diziler")
        assertThat(provider.parseHasNextPage(doc, 1)).isTrue()
        assertThat(provider.parseHasNextPage(doc, 3)).isFalse()
    }
}
