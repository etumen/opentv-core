package app.opentv.data.provider.dizipal

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class DiziPalProviderTest {
    private val provider = DiziPalProvider(OkHttpClient())

    @Test
    fun seriesCards_readCurrentGrid() {
        val html = """
            <ul class="content-grid">
              <li>
                <a href="/dizi/test-series">
                  <img data-src="/poster/test.jpg" />
                  <div class="card-info"><h3>Test Series</h3></div>
                </a>
              </li>
            </ul>
        """.trimIndent()

        val items = provider.parseSeriesCards(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Test Series")
        assertThat(items.single().id).contains("/dizi/test-series")
    }

    @Test
    fun seriesCards_prefersLazySrcsetOverPlaceholderSrc() {
        val html = """
            <ul class="content-grid">
              <li>
                <a href="/dizi/lazy-series">
                  <img
                    src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
                    data-srcset="/poster/lazy-320.jpg 320w, /poster/lazy-640.jpg 640w"
                    alt="Lazy Series"
                  />
                  <div class="card-info"><h3>Lazy Series</h3></div>
                </a>
              </li>
            </ul>
        """.trimIndent()

        val item = provider.parseSeriesCards(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL),
        ).single()

        assertThat(item.posterUrl)
            .isEqualTo(DiziPalProvider.FALLBACK_BASE_URL + "/poster/lazy-640.jpg")
    }

    @Test
    fun seriesCards_fallbackToDirectSeriesLinks() {
        val html = """
            <div id="router-view">
              <a href="/series/reacher-c04" aria-label="Reacher">
                <img src="/poster/reacher.jpg" alt="Reacher" />
              </a>
              <a href="/series/the-simpsons">
                <img src="/poster/simpsons.jpg" alt="The Simpsons" />
              </a>
            </div>
        """.trimIndent()

        val items = provider.parseSeriesCards(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL),
        )

        assertThat(items.map { it.title })
            .containsExactly("Reacher", "The Simpsons")
            .inOrder()
    }

    @Test
    fun latestEpisodeCard_mapsBackToSeries() {
        val html = """
            <div class="episodes-list-grid">
              <a class="episode-list-item" href="/bolum/test-series-2x4">
                <img data-src="/poster/test.jpg" />
                <div class="ep-title">Test Series</div>
                <div class="ep-info">2. Sezon 4. Bölüm</div>
              </a>
            </div>
        """.trimIndent()

        val items = provider.parseLatestEpisodeCards(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().id).contains("/series/test-series")
        assertThat(items.single().id).doesNotContain("/bolum/")
    }

    @Test
    fun detailsAndEpisodes_readSeasonMetadata() {
        val html = """
            <html>
              <head><meta property="og:image" content="/poster/show.jpg" /></head>
              <body>
                <h1 class="series-title">Örnek Dizi</h1>
                <p class="series-description">Açıklama.</p>
                <a href="https://www.imdb.com/title/tt9288030/">IMDb</a>
                <div class="info-row">Yıl <span class="info-value">2026</span></div>
                <div class="info-row">Kategoriler
                  <span class="info-value categories"><a>Dram</a><a>Gizem</a></span>
                </div>
                <div class="detail-episode-item-wrap">
                  <a class="detail-episode-item" href="/bolum/ornek-dizi-1-sezon-3-bolum">
                    <div class="detail-episode-title">Üçüncü Bölüm</div>
                    <div class="detail-episode-subtitle">1. Sezon 3. Bölüm</div>
                  </a>
                </div>
              </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(
            html,
            DiziPalProvider.FALLBACK_BASE_URL + "/dizi/ornek-dizi",
        )
        val seriesId = DiziPalProvider.FALLBACK_BASE_URL + "/dizi/ornek-dizi"

        val details = provider.parseDetails(doc, seriesId)
        val episodes = provider.parseEpisodes(doc, seriesId)

        assertThat(details?.item?.title).isEqualTo("Örnek Dizi")
        assertThat(details?.item?.year).isEqualTo(2026)
        assertThat(details?.item?.genres).containsExactly("Dram", "Gizem")
        assertThat(details?.item?.externalIds).containsEntry("imdb", "tt9288030")
        assertThat(details?.externalIds).containsEntry("imdb", "tt9288030")
        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().season).isEqualTo(1)
        assertThat(episodes.single().episodeNumber).isEqualTo(3)
    }

    @Test
    fun search_keepsOnlySeries() {
        val body = """
            {
              "results": [
                {"title":"Dizi A","url":"/dizi/a","poster":"/a.jpg","year":2025,"type":"Dizi"},
                {"title":"Film B","url":"/film/b","poster":"/b.jpg","year":2024,"type":"Film"}
              ]
            }
        """.trimIndent()

        val items = provider.parseSearch(body)

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Dizi A")
    }

    @Test
    fun jsonLdEpisodes_readsCurrentNextJsSeriesPayload() {
        val html = """
            <html><body>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@type":"TVSeries",
                "name":"Reacher",
                "containsSeason":[
                  {
                    "@type":"TVSeason",
                    "url":"http://dizipal1592.com/sezon/reacher/sezon-1",
                    "episode":[
                      {
                        "@type":"TVEpisode",
                        "name":"1. Bölüm",
                        "description":"Pilot",
                        "url":"http://dizipal1592.com/bolum/reacher-1x1-c02",
                        "episodeNumber":1
                      }
                    ]
                  }
                ]
              }
              </script>
            </body></html>
        """.trimIndent()

        val items = provider.parseEpisodes(
            Jsoup.parse(html, "https://dizipal1592.com/series/reacher-c04"),
            "https://dizipal1592.com/series/reacher-c04",
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().season).isEqualTo(1)
        assertThat(items.single().episodeNumber).isEqualTo(1)
        assertThat(items.single().id)
            .isEqualTo("https://dizipal1592.com/bolum/reacher-1x1-c02")
        assertThat(items.single().description).isEqualTo("Pilot")
    }

    @Test
    fun currentSearchPage_readsSeriesTitleYearAndPoster() {
        val html = """
            <div class="wrap">
              <div class="g6">
                <a class="tc" aria-label="Reacher, 2022" href="/series/reacher-c04">
                  <span class="fr"><img src="/poster/reacher.jpg" /></span>
                  <span class="tt">Reacher</span>
                </a>
              </div>
            </div>
        """.trimIndent()

        val items = provider.parseSearchPage(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL + "/arama-yap?s=Reacher"),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Reacher")
        assertThat(items.single().year).isEqualTo(2022)
        assertThat(items.single().id).contains("/series/reacher-c04")
        assertThat(items.single().posterUrl).contains("/poster/reacher.jpg")
    }

    @Test
    fun advertisedNextDomain_prefersFutureDomain() {
        val html = """
            <html><body>
              Dizipal tekrar yenilendi. Sonraki adresimiz dizipal1593.com olacaktÄ±r.
              <a href="https://dizipal1592.com/">Åimdiki adres</a>
            </body></html>
        """.trimIndent()

        assertThat(provider.extractAdvertisedNextDomain(html, 1592))
            .isEqualTo("https://dizipal1593.com")
    }
    @Test
    fun dplayerMaster_preservesExternalAudioAndAbsolutizesUris() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE='AUDIO',GROUP-ID="audio",NAME="Türkçe",DEFAULT=YES,AUTOSELECT=YES,URI='../audio/tr/index.m3u8'
            #EXT-X-STREAM-INF:BANDWIDTH=5400000,RESOLUTION=1920x1080,AUDIO="audio"
            video/1080/index.m3u8
        """.trimIndent()

        assertThat(provider.isMasterHls(master)).isTrue()
        assertThat(provider.hasExternalAudioRendition(master)).isTrue()

        val rewritten = provider.absolutizeHlsReferences(
            master,
            "https://cdn.example.test/hls/master/master.m3u8",
        )

        assertThat(rewritten).contains(
            """URI="https://cdn.example.test/hls/audio/tr/index.m3u8"""",
        )
        assertThat(rewritten).contains(
            "https://cdn.example.test/hls/master/video/1080/index.m3u8",
        )
        assertThat(rewritten).contains("""AUDIO="audio"""")
    }

    @Test
    fun tracks_readsSubtitleEntries() {
        val source = """
            tracks: [
              { file: "https://cdn.test/tr.vtt", label: "Türkçe" },
              { file: "https://cdn.test/en.srt", label: "English" }
            ]
        """.trimIndent()

        val tracks = provider.parseTracks(source)

        assertThat(tracks).containsExactly(
            "Türkçe" to "https://cdn.test/tr.vtt",
            "English" to "https://cdn.test/en.srt",
        )
    }
}
