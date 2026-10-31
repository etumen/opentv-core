/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.hdfilmcehennemi

import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class HdFilmCehennemiProviderTest {

    private val provider = HdFilmCehennemiProvider(OkHttpClient())

    @Test
    fun cardParser_readsMovieCards() {
        val html = """
            <article id="post-26468" class="item dortlu movies">
                <div class="imdb"><span>IMDb</span>5.7</div>
                <div class="poster">
                    <a href="https://www.hdfilmcehennemi.land/film/ali-g-ben-kimim/">
                        <img src="data:image/gif;base64,R0lGODlh" data-src="https://www.hdfilmcehennemi.land/thumb_/ali-g.webp" alt="Ali G: Ben Kimim? Poster">
                        <div class="figure">
                            <div class="data">
                                <h2 class="flbaslik">Ali G: Ben Kimim?</h2>
                            </div>
                            <span class="anayil">2026</span>
                            <span class="anadil flm-multi">Dublaj &amp; Altyazı</span>
                        </div>
                    </a>
                </div>
            </article>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/film/")
        val items = provider.parseCards(doc, ProviderMediaType.MOVIE)

        assertThat(items).hasSize(1)
        val item = items.first()
        assertThat(item.id).isEqualTo("https://www.hdfilmcehennemi.land/film/ali-g-ben-kimim/")
        assertThat(item.title).isEqualTo("Ali G: Ben Kimim?")
        assertThat(item.mediaType).isEqualTo(ProviderMediaType.MOVIE)
        assertThat(item.year).isEqualTo(2026)
        assertThat(item.rating).isWithin(0.001).of(5.7)
        assertThat(item.posterUrl).isEqualTo("https://www.hdfilmcehennemi.land/thumb_/ali-g.webp")
    }

    @Test
    fun cardParser_readsSeriesCards() {
        val html = """
            <article id="post-26263" class="item dortlu tvshows">
                <div class="imdb"><span>IMDb</span>7.9</div>
                <div class="poster">
                    <a href="https://www.hdfilmcehennemi.land/dizi/helluva-boss/">
                        <img src="data:image/gif;base64,R0lGODlh" data-src="https://www.hdfilmcehennemi.land/thumb_/helluva.webp" alt="Helluva Boss Poster">
                        <div class="figure">
                            <div class="data">
                                <h2 class="flbaslik">Helluva Boss</h2>
                            </div>
                            <span class="anayil">2019</span>
                        </div>
                    </a>
                </div>
            </article>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/dizi/")
        val items = provider.parseCards(doc, ProviderMediaType.SERIES)

        assertThat(items).hasSize(1)
        val item = items.first()
        assertThat(item.id).isEqualTo("https://www.hdfilmcehennemi.land/dizi/helluva-boss/")
        assertThat(item.title).isEqualTo("Helluva Boss")
        assertThat(item.mediaType).isEqualTo(ProviderMediaType.SERIES)
        assertThat(item.year).isEqualTo(2019)
        assertThat(item.rating).isWithin(0.001).of(7.9)
    }

    @Test
    fun cardParser_readsSearchArticles() {
        val html = """
            <article>
                <div class="image">
                    <div class="thumbnail animation-2">
                        <a href="https://www.hdfilmcehennemi.land/film/batman-ve-superman-izle/">
                            <img src="https://www.hdfilmcehennemi.land/uploads/batman.webp" alt="Batman" />
                            <span class="movies">Film</span>
                        </a>
                    </div>
                </div>
                <div class="details">
                    <div class="title">
                        <a href="https://www.hdfilmcehennemi.land/film/batman-ve-superman-izle/">
                            Batman ve Superman - Dawn of Justice
                        </a>
                    </div>
                    <div class="meta">
                        <span class="rating">IMDb 6.4</span>
                        <span class="year">2016</span>
                    </div>
                    <div class="contenido">
                        <p>Dünya nasıl bir kahramana ihtiyaç duyduğunu sorgularken...</p>
                    </div>
                </div>
            </article>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/?s=Batman")
        val items = provider.parseCards(doc, ProviderMediaType.MOVIE)

        assertThat(items).hasSize(1)
        val item = items.first()
        assertThat(item.id).isEqualTo("https://www.hdfilmcehennemi.land/film/batman-ve-superman-izle/")
        assertThat(item.title).isEqualTo("Batman ve Superman")
        assertThat(item.mediaType).isEqualTo(ProviderMediaType.MOVIE)
        assertThat(item.year).isEqualTo(2016)
        assertThat(item.rating).isWithin(0.001).of(6.4)
        assertThat(item.description).contains("Dünya nasıl bir kahramana")
    }

    @Test
    fun cardParser_skipsEpisodesAndDuplicateHrefs() {
        val html = """
            <article class="item">
                <a href="https://www.hdfilmcehennemi.land/bolum/reacher-1-sezon-1-bolum/">
                    <h2>Episode should not become catalog item</h2>
                </a>
            </article>
            <article class="item movies">
                <a href="https://www.hdfilmcehennemi.land/film/valid-movie/">
                    <h2 class="flbaslik">Valid Movie</h2>
                </a>
            </article>
            <article class="item movies">
                <a href="https://www.hdfilmcehennemi.land/film/valid-movie/">
                    <h2 class="flbaslik">Duplicate Movie</h2>
                </a>
            </article>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/")
        val items = provider.parseCards(doc, ProviderMediaType.MOVIE)

        assertThat(items).hasSize(1)
        assertThat(items.first().title).isEqualTo("Valid Movie")
    }

    @Test
    fun detailParser_readsMovieDetails() {
        val html = """
            <html>
            <head>
                <meta property="og:description" content="Batman v Superman summary">
                <meta property="og:image" content="https://www.hdfilmcehennemi.land/uploads/batman-poster.webp">
            </head>
            <body>
                <div class="data">
                    <h1>Batman ve Superman: Adaletin Şafağı</h1>
                    <div class="extra">
                        <div lang="en" class="or-title">Batman v Superman: Dawn of Justice</div>
                        <span class="C"><a href="/yil/2016-izle-1/">2016</a></span>
                        <span class="runtime">151 Dak.</span>
                    </div>
                    <div class="sgeneros">
                        <a href="/tur/aksiyon-izle-1/">Aksiyon</a>
                        <a href="/tur/macera-izle-1/">Macera</a>
                    </div>
                </div>
                <div class="custom_fields">
                    <b class="variante">IMDb Puanı</b>
                    <a href="https://www.imdb.com/title/tt2975590/" data-id="tt2975590">
                        <span class="valor"><b id="repimdb"><strong>6.5</strong></b></span>
                    </a>
                </div>
                <div class="custom_fields">
                    <b class="variante">Yönetmen</b>
                    <span class="valor"><a href="/yonetmen/zack-snyder/">Zack Snyder</a></span>
                </div>
                <a href="/oyuncu/ben-affleck/">Ben Affleck</a>
                <a href="/oyuncu/henry-cavill/">Henry Cavill</a>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/film/batman-ve-superman/")
        val details = provider.parseDetails(doc, "https://www.hdfilmcehennemi.land/film/batman-ve-superman/")

        assertThat(details).isNotNull()
        assertThat(details?.item?.title).isEqualTo("Batman ve Superman: Adaletin Şafağı")
        assertThat(details?.item?.mediaType).isEqualTo(ProviderMediaType.MOVIE)
        assertThat(details?.item?.year).isEqualTo(2016)
        assertThat(details?.item?.rating).isWithin(0.001).of(6.5)
        assertThat(details?.item?.posterUrl).isEqualTo("https://www.hdfilmcehennemi.land/uploads/batman-poster.webp")
        assertThat(details?.item?.genres).containsExactly("Aksiyon", "Macera")
        assertThat(details?.durationSeconds).isEqualTo(151 * 60)
        assertThat(details?.externalIds).containsEntry("imdb", "tt2975590")
        assertThat(details?.director).isEqualTo("Zack Snyder")
        assertThat(details?.cast).containsExactly("Ben Affleck", "Henry Cavill")
    }

    @Test
    fun detailParser_readsSeriesDetails() {
        val html = """
            <html>
            <head>
                <meta property="og:description" content="Helluva Boss animated series">
                <meta property="og:image" content="https://www.hdfilmcehennemi.land/uploads/helluva.webp">
            </head>
            <body>
                <div class="data">
                    <h1>Helluva Boss</h1>
                    <div class="extra">
                        <span><a href="/yil/2019/">2019</a></span>
                    </div>
                    <div class="sgeneros">
                        <a href="/tur/animasyon-izle/">Animasyon</a>
                        <a href="/tur/komedi-izle-2/">Komedi</a>
                    </div>
                </div>
                <div id="seasons">
                    <div class="se-c"><span class="se-t">1</span></div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/dizi/helluva-boss/")
        val details = provider.parseDetails(doc, "https://www.hdfilmcehennemi.land/dizi/helluva-boss/")

        assertThat(details).isNotNull()
        assertThat(details?.item?.title).isEqualTo("Helluva Boss")
        assertThat(details?.item?.mediaType).isEqualTo(ProviderMediaType.SERIES)
        assertThat(details?.item?.year).isEqualTo(2019)
        assertThat(details?.item?.genres).containsExactly("Animasyon", "Komedi")
    }

    @Test
    fun episodeParser_readsSeasonsAndEpisodes() {
        val html = """
            <div id="seasons">
                <div class="se-c">
                    <div class="se-q"><span class="se-t">1</span></div>
                    <ul class="episodios">
                        <li>
                            <div class="imagen">
                                <a href="/bolum/helluva-boss-1-sezon-1-bolum/">
                                    <img data-src="/thumbs/s1e1.webp">
                                </a>
                            </div>
                            <div class="numerando">1 - 1</div>
                            <h2 class="episodiotitle">
                                <a href="/bolum/helluva-boss-1-sezon-1-bolum/">Murder Family</a>
                            </h2>
                        </li>
                        <li>
                            <div class="imagen">
                                <a href="/bolum/helluva-boss-1-sezon-2-bolum/">
                                    <img data-src="/thumbs/s1e2.webp">
                                </a>
                            </div>
                            <div class="numerando">1 - 2</div>
                            <h2 class="episodiotitle">
                                <a href="/bolum/helluva-boss-1-sezon-2-bolum/">Loo Loo Land</a>
                            </h2>
                        </li>
                    </ul>
                </div>
                <div class="se-c">
                    <div class="se-q"><span class="se-t">2</span></div>
                    <ul class="episodios">
                        <li>
                            <div class="imagen">
                                <a href="/bolum/helluva-boss-2-sezon-1-bolum/">
                                    <img data-src="/thumbs/s2e1.webp">
                                </a>
                            </div>
                            <div class="numerando">2 - 1</div>
                            <h2 class="episodiotitle">
                                <a href="/bolum/helluva-boss-2-sezon-1-bolum/">The Circus</a>
                            </h2>
                        </li>
                    </ul>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/dizi/helluva-boss/")
        val episodes = provider.parseEpisodes(doc, "https://www.hdfilmcehennemi.land/dizi/helluva-boss/")

        assertThat(episodes).hasSize(3)

        assertThat(episodes[0].season).isEqualTo(1)
        assertThat(episodes[0].episodeNumber).isEqualTo(1)
        assertThat(episodes[0].title).isEqualTo("Murder Family")
        assertThat(episodes[0].id).isEqualTo("https://www.hdfilmcehennemi.land/bolum/helluva-boss-1-sezon-1-bolum/")
        assertThat(episodes[0].stillUrl).isEqualTo("https://www.hdfilmcehennemi.land/thumbs/s1e1.webp")

        assertThat(episodes[1].season).isEqualTo(1)
        assertThat(episodes[1].episodeNumber).isEqualTo(2)
        assertThat(episodes[1].title).isEqualTo("Loo Loo Land")

        assertThat(episodes[2].season).isEqualTo(2)
        assertThat(episodes[2].episodeNumber).isEqualTo(1)
        assertThat(episodes[2].title).isEqualTo("The Circus")
    }

    @Test
    fun episodeParser_flatListFallback() {
        val html = """
            <ul class="episodios">
                <li><a href="/bolum/mini-series-1-sezon-1-bolum/">Pilot</a></li>
                <li><a href="/bolum/mini-series-1-sezon-2-bolum/">Finale</a></li>
            </ul>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.hdfilmcehennemi.land/dizi/mini-series/")
        val episodes = provider.parseEpisodes(doc, "https://www.hdfilmcehennemi.land/dizi/mini-series/")

        assertThat(episodes).hasSize(2)
        assertThat(episodes[0].season).isEqualTo(1)
        assertThat(episodes[0].episodeNumber).isEqualTo(1)
        assertThat(episodes[0].title).isEqualTo("Pilot")
        assertThat(episodes[1].season).isEqualTo(1)
        assertThat(episodes[1].episodeNumber).isEqualTo(2)
        assertThat(episodes[1].title).isEqualTo("Finale")
    }

    @Test
    fun malformedHtml_handledGracefullyWithoutCrashing() {
        val emptyDoc = Jsoup.parse("", "https://www.hdfilmcehennemi.land/")
        assertThat(provider.parseCards(emptyDoc, ProviderMediaType.MOVIE)).isEmpty()
        assertThat(provider.parseDetails(emptyDoc, "https://www.hdfilmcehennemi.land/film/broken")).isNull()
        assertThat(provider.parseEpisodes(emptyDoc, "https://www.hdfilmcehennemi.land/dizi/broken")).isEmpty()
    }

    @Test
    fun streams_returnsFailureGracefullyOnNetworkError() = runBlocking {
        // Points to invalid target URL, should fail safely with ProviderResult.Failure instead of crashing
        val target = ProviderPlaybackTarget.Movie(
            providerId = HdFilmCehennemiProvider.PROVIDER_ID,
            itemId = "http://127.0.0.1:59999/non-existent-film",
        )
        val result = provider.streams(target)
        assertThat(result).isInstanceOf(ProviderResult.Failure::class.java)
    }

    @Test
    fun search_liveNetwork_returnsResults() = runBlocking {
        val req = app.opentv.data.provider.ProviderSearchRequest(
            query = "John Wick",
            mediaType = ProviderMediaType.MOVIE,
        )
        val result = provider.search(req)
        println("SEARCH RESULT: $result")
        if (result is ProviderResult.Failure) {
            println("FAILURE: ${result.error.message}")
        }
        assertThat(result).isInstanceOf(ProviderResult.Success::class.java)
        val items = (result as ProviderResult.Success).value
        println("SEARCH ITEMS COUNT: ${items.size}")
        items.forEach { println(" - ${it.title} (${it.year}) -> ${it.id}") }
        assertThat(items).isNotEmpty()
    }
}
