package app.opentv.data.provider.dramadizilerim

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class DramaDizilerimProviderTest {
    private val provider = DramaDizilerimProvider(OkHttpClient())
    @Test
    fun playback_publicHlsTicketIsResolvedFromRelativeMediaEndpoint() {
        val playback = provider.parsePublicPlayback(
            """{"success":true,"kind":"hls","url":"/api/media?ticket=example","captions":[]}""",
            "https://dramadizilerim.com/izle/gercek?s=1&e=1",
        )
        assertThat(playback).isNotNull()
        assertThat(playback!!.url).isEqualTo("https://dramadizilerim.com/api/media?ticket=example")
        assertThat(playback.mimeType).isEqualTo("application/x-mpegURL")
        assertThat(playback.headers["Referer"]).contains("/izle/gercek")
    }

    @Test
    fun playback_failedPublicApiDoesNotYieldStream() {
        val playback = provider.parsePublicPlayback(
            """{"success":false,"message":"Unavailable"}""",
            "https://dramadizilerim.com/izle/gercek",
        )
        assertThat(playback).isNull()
    }

    @Test
    fun cards_excludesWebsiteForbiddenPlaceholderAndCleansPosterTitle() {
        val html = """
            <a href="/dizi/403-forbidden-nuxt"><img alt="403 - Forbidden | Nuxt dizi afişi"></a>
            <a href="/dizi/gercek"><img alt="Gerçek Dizi dizi afişi"></a>
        """.trimIndent()
        val items = provider.parseCards(Jsoup.parse(html, "https://dramadizilerim.com/diziler"))
        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Gerçek Dizi")
    }

    @Test
    fun pagination_recognizesActualNextPageLink() {
        val html = """<a href="/diziler?page=2">Sonraki</a>"""
        val doc = Jsoup.parse(html, "https://dramadizilerim.com/diziler")
        assertThat(provider.parseHasNextPage(doc, 1)).isTrue()
        assertThat(provider.parseHasNextPage(doc, 2)).isFalse()
    }

    @Test
    fun episodes_preservesFirstEpisodeAfterPlayLink() {
        val html = """
            <a href="/izle/gercek">İzlemeye başla</a>
            <a href="/izle/gercek">1 . bölüm</a>
            <a href="/izle/gercek?s=1&e=2">2 . bölüm</a>
        """.trimIndent()
        val episodes = provider.parseEpisodes(
            Jsoup.parse(html, "https://dramadizilerim.com/dizi/gercek"),
            "https://dramadizilerim.com/dizi/gercek",
        )
        assertThat(episodes.map { it.episodeNumber }).containsExactly(1, 2).inOrder()
    }


    @Test
    fun cards_readCurrentDramaShape() {
        val html = """
            <a href="/dizi/gizemli-oyun">
              <img alt="Gizemli Oyun İzle" src="/poster.jpg">
            </a>
        """.trimIndent()

        val items = provider.parseCards(
            Jsoup.parse(html, "https://dramadizilerim.com/dizi"),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Gizemli Oyun")
        assertThat(items.single().id)
            .isEqualTo("https://dramadizilerim.com/dizi/gizemli-oyun")
    }

    @Test
    fun episodes_readSeasonAndEpisodeQuery() {
        val html = """
            <a href="/izle/gizemli-oyun?s=1&e=1">Şimdi İzle</a>
            <a href="/izle/gizemli-oyun?s=1&e=1">Bölüm 1</a>
            <a href="/izle/gizemli-oyun?s=1&e=2">Bölüm 2</a>
        """.trimIndent()

        val items = provider.parseEpisodes(
            Jsoup.parse(html, "https://dramadizilerim.com/dizi/gizemli-oyun"),
            "https://dramadizilerim.com/dizi/gizemli-oyun",
        )

        assertThat(items).hasSize(2)
        assertThat(items.map { it.episodeNumber }).containsExactly(1, 2).inOrder()
        assertThat(items.all { it.season == 1 }).isTrue()
    }

    @Test
    fun embedSource_readsPublicHlsVariable() {
        val source = provider.extractSource(
            """<script>let source = "https://cdn.example/video/master.m3u8";</script>""",
        )

        assertThat(source).isEqualTo("https://cdn.example/video/master.m3u8")
    }
}
