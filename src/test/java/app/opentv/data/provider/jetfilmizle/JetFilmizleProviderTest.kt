/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.jetfilmizle

import app.opentv.data.provider.ProviderMediaType
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class JetFilmizleProviderTest {

    private val provider = JetFilmizleProvider(OkHttpClient())

    @Test
    fun providerConfig_isMovieOnly() {
        assertThat(provider.id).isEqualTo("jetfilmizle")
        assertThat(provider.name).isEqualTo("JetFilmizle")
        assertThat(provider.supportedMediaTypes).containsExactly(ProviderMediaType.MOVIE)
        assertThat(provider.catalogSections).isNotEmpty()
    }

    @Test
    fun parseCards_extractsMovieData() {
        val html = """
            <div class="film-card">
                <a href="https://jetfilmizle.app/film/ali-g-who-iz-i" title="Ali G: Who Iz I? Full Türkçe Dublaj İzle">
                    <img src="https://cdn.jetfilmizle.app/uploads/films/2026/a/ali-g-who-iz-i-poster-medium.webp" alt="Ali G">
                    <span class="fc-rating">6.8</span>
                    <span class="fc-year">2026</span>
                </a>
                <div class="film-card-title">Ali G: Who Iz I?</div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://jetfilmizle.app/filmler/")
        val items = provider.parseCards(doc)

        assertThat(items).hasSize(1)
        val item = items.first()
        assertThat(item.id).isEqualTo("https://jetfilmizle.app/film/ali-g-who-iz-i")
        assertThat(item.title).isEqualTo("Ali G: Who Iz I?")
        assertThat(item.mediaType).isEqualTo(ProviderMediaType.MOVIE)
        assertThat(item.year).isEqualTo(2026)
        assertThat(item.rating).isWithin(0.001).of(6.8)
        assertThat(item.posterUrl).isEqualTo("https://cdn.jetfilmizle.app/uploads/films/2026/a/ali-g-who-iz-i-poster-medium.webp")
    }

    @Test
    fun parseHasNextPage_detectsPagination() {
        val html = """
            <ul class="pagination">
                <li class="page-item active"><a class="page-link" href="#">1</a></li>
                <li class="page-item"><a class="page-link" href="https://jetfilmizle.app/filmler/sayfa-2">2</a></li>
            </ul>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://jetfilmizle.app/filmler/")
        assertThat(provider.parseHasNextPage(doc, 1)).isTrue()
        assertThat(provider.parseHasNextPage(doc, 2)).isFalse()
    }

    @Test
    fun parseHasNextPage_recognizesLivePageItemModernMarkup() {
        val doc = Jsoup.parse(
            """<div class="page-numbers-mobile"><a href="/filmler/sayfa-2">2</a></div>""",
            "https://jetfilmizle.app/filmler/",
        )
        assertThat(provider.parseHasNextPage(doc, 1)).isTrue()
        assertThat(provider.parseHasNextPage(doc, 2)).isFalse()
    }
}
