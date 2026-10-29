package app.opentv.data.provider.ddizibox

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class DiziBoxProviderTest {
    private val provider = DiziBoxProvider(OkHttpClient())

    @Test
    fun parseSeriesCards_keepsSeriesCardsAndMetadata() {
        val doc = Jsoup.parse(
            """
            <html><body>
              <a href="https://ddizibox.com/dizi/reacher" class="group block">
                <img src="https://img.test/reacher.webp" alt="Reacher Dizi izle">
                <span class="badge-rating">★ 8.1</span>
                <h3>Reacher</h3><p>2022</p>
              </a>
              <a href="https://ddizibox.com/dizi/reacher/sezon-1/bolum-1">
                <h3>Episode should not become a series</h3>
              </a>
            </body></html>
            """.trimIndent(),
            "https://ddizibox.com/ara?q=Reacher",
        )

        val items = provider.parseSeriesCards(doc)

        assertThat(items).hasSize(1)
        val item = items.single()
        assertThat(item.id).isEqualTo("https://ddizibox.com/dizi/reacher")
        assertThat(item.title).isEqualTo("Reacher")
        assertThat(item.year).isEqualTo(2022)
        assertThat(item.rating).isWithin(0.001).of(8.1)
        assertThat(item.posterUrl).isEqualTo("https://img.test/reacher.webp")
    }

    @Test
    fun parseDetails_readsYearRatingGenresBackdropAndDescription() {
        val doc = Jsoup.parse(
            """
            <html>
              <head>
                <meta property="og:description" content="Reacher description">
                <meta property="og:image" content="https://img.test/poster.webp">
              </head>
              <body><main>
                <section class="relative">
                  <div class="absolute"><img src="https://img.test/backdrop.webp"></div>
                  <h1>Reacher</h1>
                  <a href="/tur/aksiyon">Aksiyon</a>
                  <a href="/tur/suc">Suç</a>
                  <img alt="Reacher" src="https://img.test/poster.webp">
                </section>
                <dl>
                  <div><dt>IMDB Puanı</dt><dd>★ 8.1</dd></div>
                  <div><dt>Yapım Yılı</dt><dd>2022</dd></div>
                </dl>
              </main></body>
            </html>
            """.trimIndent(),
            "https://ddizibox.com/dizi/reacher",
        )

        val details = provider.parseDetails(doc, "https://ddizibox.com/dizi/reacher")!!

        assertThat(details.item.title).isEqualTo("Reacher")
        assertThat(details.item.year).isEqualTo(2022)
        assertThat(details.item.rating).isWithin(0.001).of(8.1)
        assertThat(details.item.genres).containsExactly("Aksiyon", "Suç")
        assertThat(details.item.backdropUrl).isEqualTo("https://img.test/backdrop.webp")
        assertThat(details.description).isEqualTo("Reacher description")
    }

    @Test
    fun parseEpisodes_readsSeasonAndEpisodeFromStableUrls() {
        val doc = Jsoup.parse(
            """
            <html><body>
              <section id="bolumler">
                <a href="https://ddizibox.com/dizi/reacher/sezon-4/bolum-1" class="group">
                  <img src="https://img.test/s4e1.webp">
                  <p class="mt-2">Kardeşçe Sevgi Şehri</p>
                  <p>4. Sezon 1. Bölüm</p>
                </a>
                <a href="https://ddizibox.com/dizi/reacher/sezon-4/bolum-2" class="group">
                  <p class="mt-2">Kafes Dövüşü</p>
                </a>
              </section>
            </body></html>
            """.trimIndent(),
            "https://ddizibox.com/dizi/reacher?sezon=4",
        )

        val episodes = provider.parseEpisodes(doc, "https://ddizibox.com/dizi/reacher")

        assertThat(episodes).hasSize(2)
        assertThat(episodes[0].season).isEqualTo(4)
        assertThat(episodes[0].episodeNumber).isEqualTo(1)
        assertThat(episodes[0].title).isEqualTo("Kardeşçe Sevgi Şehri")
        assertThat(episodes[1].episodeNumber).isEqualTo(2)
    }

    @Test
    fun resolvePlayerOrigin_followsCurrentPlayerScriptHost() {
        val doc = Jsoup.parse(
            """
            <html><body>
              <div data-pv="opaque"></div>
              <script src="https://play7.pilavyerplay.top/assets/js/core.js"></script>
            </body></html>
            """.trimIndent(),
            "https://ddizibox.net/dizi/neagley/sezon-1/bolum-1",
        )

        assertThat(provider.resolvePlayerOrigin(doc))
            .isEqualTo("https://play7.pilavyerplay.top")
    }

    @Test
    fun parsePlayerConfig_readsHlsAndSubtitles() {
        val html = """
            <html><script>
            window.__PLAYER__ = {
              "stream":"https://pilavyerplay.top/api/stream.php?v=abc\u0026token=xyz",
              "subs":[
                {"lang":"tr","label":"Türkçe Altyazı","src":"https://pilavyerplay.top/api/sub.php?v=abc\u0026sid=tr\u0026token=xyz"},
                {"lang":"en","label":"İngilizce Altyazı","src":"https://pilavyerplay.top/api/sub.php?v=abc\u0026sid=en\u0026token=xyz"}
              ]
            };
            </script></html>
        """.trimIndent()

        val playback = provider.parsePlayerConfig(
            html,
            "https://pilavyerplay.top/assets/js/s.php?s=opaque",
        )

        assertThat(playback.streams).hasSize(1)
        assertThat(playback.streams.single().url)
            .isEqualTo("https://pilavyerplay.top/api/stream.php?v=abc&token=xyz")
        assertThat(playback.streams.single().mimeType)
            .isEqualTo("application/x-mpegURL")
        assertThat(playback.subtitles.map { it.language }).containsExactly("tr", "en")
        assertThat(playback.subtitles.first().url).contains("&sid=tr&")
    }
}
