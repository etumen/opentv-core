package app.opentv.data.provider.dramadizilerim

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class DramaDizilerimProviderTest {
    private val provider = DramaDizilerimProvider(OkHttpClient())

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
