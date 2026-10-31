package app.opentv.data.provider.dizipal

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class DiziPalProviderTest {
    private val provider = DiziPalProvider(OkHttpClient())

    @Test
    fun listingPageUrl_usesActualPageParameter() {
        val base = "https://dizipal1592.com"
        assertThat(provider.listingPageUrl(base, "/yabanci-dizi-izle", 1))
            .isEqualTo("$base/yabanci-dizi-izle")
        assertThat(provider.listingPageUrl(base, "/yabanci-dizi-izle", 2))
            .isEqualTo("$base/yabanci-dizi-izle?page=2")
        assertThat(provider.listingPageUrl(base, "/yabanci-dizi-izle?sort=latest", 2))
            .isEqualTo("$base/yabanci-dizi-izle?sort=latest&page=2")
    }

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
    fun seriesCards_readsNextJsFlightPayload() {
        val html = """
            <html><body>
              <script>
                self.__next_f.push([1,"30:[{\"id\":\"dizi1\",\"t\":\"Reacher\",\"href\":\"/series/reacher\",\"img\":\"https://images.example/reacher.jpg\",\"r\":\"8.0\",\"year\":\"2022\",\"genres\":\"Aksiyon · Suç\"}]"])
              </script>
            </body></html>
        """.trimIndent()

        val items = provider.parseSeriesCards(
            Jsoup.parse(html, "https://dizipal1592.com/kanal/netflix"),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Reacher")
        assertThat(items.single().id).isEqualTo("https://dizipal1592.com/series/reacher")
        assertThat(items.single().posterUrl).isEqualTo("https://images.example/reacher.jpg")
        assertThat(items.single().year).isEqualTo(2022)
        assertThat(items.single().rating).isEqualTo(8.0)
        assertThat(items.single().genres).containsExactly("Aksiyon", "Suç").inOrder()
    }

    @Test
    fun channelCards_ignorePopularPayloadAndReadChannelItems() {
        val html = """
            <html><body>
              <script>
                self.__next_f.push([1,"30:{\"popular\":[{\"id\":\"dizi0\",\"t\":\"Below\",\"href\":\"/series/below\",\"img\":\"/poster/below.jpg\",\"year\":\"2026\"}],\"items\":[{\"id\":\"dizi1\",\"t\":\"Netflix Only\",\"href\":\"/series/netflix-only\",\"img\":\"/poster/netflix.jpg\",\"r\":\"8.4\",\"year\":\"2025\",\"genres\":\"Dram\"}],\"error\":false,\"hasMore\":true,\"more\":{\"action\":\"getSerieListByChannel\",\"params\":[10,1,\"2,3,4\"],\"pageAt\":1,\"next\":2}}"])
              </script>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://dizipal1592.com/kanal/netflix")

        val items = provider.parseChannelSeriesCards(doc)

        assertThat(items.map { it.title }).containsExactly("Netflix Only")
        assertThat(provider.extractChannelId(doc)).isEqualTo("10")
    }
    @Test
    fun playerPayload_readsEncryptedObjectFromNextFlightData() {
        val html = """
            <html><body><script>
              self.__next_f.push([1,"31:{\"player\":{\"enc\":{\"ciphertext\":\"abc/def==\",\"iv\":\"00112233445566778899aabbccddeeff\",\"salt\":\"aabbccdd\"}}}"])
            </script></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://dizipal1592.com/bolum/neagley-1x1")

        val payload = provider.extractEncryptedPlayerPayload(doc)

        assertThat(payload).isNotNull()
        assertThat(payload).contains("\"ciphertext\":\"abc/def==\"")
        assertThat(payload).contains("\"iv\":\"00112233445566778899aabbccddeeff\"")
        assertThat(payload).contains("\"salt\":\"aabbccdd\"")
    }

    @Test
    fun dplayerNpConfig_readsCurrentEmbeddedConfig() {
        val html = """
            <html><body><script>
            window.NP_CONFIG = {
              "id":"episode-id",
              "sources":[
                {
                  "title":"Orijinal",
                  "kind":"orig",
                  "master":"https://four.dplayer82.site/nm.php?v=token"
                }
              ],
              "subtitles":[
                {
                  "label":"Türkçe",
                  "lang":"tr",
                  "url":"https://four.dplayer82.site/nsub.php?v=subtoken"
                }
              ]
            };
            </script></body></html>
        """.trimIndent()

        val config = provider.extractDplayerNpConfig(html)

        assertThat(config).isNotNull()
        assertThat(config).contains("\"master\":\"https://four.dplayer82.site/nm.php?v=token\"")
        assertThat(config).contains("\"lang\":\"tr\"")
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
    fun jsonLdEpisodes_acceptsSingleSeasonObject() {
        val html = """
            <html><body>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@type":"TVSeries",
                "name":"Neagley",
                "containsSeason":{
                  "@type":"TVSeason",
                  "url":"http://dizipal1592.com/sezon/neagley/sezon-1",
                  "seasonNumber":1,
                  "episode":[
                    {
                      "@type":"TVEpisode",
                      "name":"1. Bölüm",
                      "url":"http://dizipal1592.com/bolum/neagley-1x1",
                      "episodeNumber":1
                    },
                    {
                      "@type":"TVEpisode",
                      "name":"2. Bölüm",
                      "url":"http://dizipal1592.com/bolum/neagley-1x2",
                      "episodeNumber":2
                    }
                  ]
                }
              }
              </script>
            </body></html>
        """.trimIndent()

        val items = provider.parseEpisodes(
            Jsoup.parse(html, "https://dizipal1592.com/series/neagley"),
            "https://dizipal1592.com/series/neagley",
        )

        assertThat(items).hasSize(2)
        assertThat(items.map { it.episodeNumber }).containsExactly(1, 2).inOrder()
        assertThat(items.all { it.season == 1 }).isTrue()
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
