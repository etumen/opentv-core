/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.sinemacx

import app.opentv.data.provider.ProviderMediaType
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class SinemaCxProviderTest {

    private val provider = SinemaCxProvider(OkHttpClient())

    @Test
    fun providerConfig_isMovieOnly() {
        assertThat(provider.id).isEqualTo("sinemacx")
        assertThat(provider.name).isEqualTo("SinemaCX")
        assertThat(provider.supportedMediaTypes).containsExactly(ProviderMediaType.MOVIE)
        assertThat(provider.catalogSections).isNotEmpty()
    }

    @Test
    fun parseCards_extractsMovieData() {
        val html = """
            <div class="film_kutusu">
                <a href="https://sinemacc.com/film/evrenin-ucuna-yolculuk-2008/" title="Evrenin Ucuna Yolculuk İzle">
                    <img src="data:image/svg+xml,..." data-src="https://sinemacc.com/uploads/filmler/2026-10/evrenin-ucuna-yolculuk_f.webp" alt="Evrenin Ucuna Yolculuk">
                    <span class="puan">8.5</span>
                </a>
                <div class="baslik">
                    <a href="https://sinemacc.com/film/evrenin-ucuna-yolculuk-2008/">Evrenin Ucuna Yolculuk</a>
                </div>
                <div class="yil">2008</div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://sinemacc.com/")
        val items = provider.parseCards(doc)

        assertThat(items).hasSize(1)
        val item = items.first()
        assertThat(item.id).isEqualTo("https://sinemacc.com/film/evrenin-ucuna-yolculuk-2008/")
        assertThat(item.title).isEqualTo("Evrenin Ucuna Yolculuk")
        assertThat(item.mediaType).isEqualTo(ProviderMediaType.MOVIE)
        assertThat(item.year).isEqualTo(2008)
        assertThat(item.rating).isWithin(0.001).of(8.5)
        assertThat(item.posterUrl).isEqualTo("https://sinemacc.com/uploads/filmler/2026-10/evrenin-ucuna-yolculuk_f.webp")
    }

    @Test
    fun parseHasNextPage_detectsPagination() {
        val html = """
            <div class="sayfala">
                <span class="current">1</span>
                <a href="https://sinemacc.com/page/2/">2</a>
                <a href="https://sinemacc.com/page/3/">3</a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://sinemacc.com/")
        assertThat(provider.parseHasNextPage(doc, 1)).isTrue()
        assertThat(provider.parseHasNextPage(doc, 3)).isFalse()
    }
}
