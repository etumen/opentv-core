package app.opentv.data.provider.filmmakinesi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilmMakinesiCodecTest {
    @Test
    fun rapidFallback_readsDirectM3u8() {
        val html = """<script>var file="https://cdn.example.test/master.m3u8?token=abc";</script>"""
        assertThat(FilmMakinesiCodec.extractRapidStream(html))
            .isEqualTo("https://cdn.example.test/master.m3u8?token=abc")
    }

    @Test
    fun subtitles_readsTurkishTrack() {
        val html = """
            <script>
              tracks: [
                {"file":"/subs/tr.vtt","label":"Turkish","language":"tr"},
                {"file":"https://cdn.example/en.vtt","label":"English","language":"en"}
              ]
            </script>
        """.trimIndent()
        val subtitles = FilmMakinesiCodec.extractSubtitles(
            html,
            "https://rapid.filmmakinesi.to/embed/123",
        )

        assertThat(subtitles).hasSize(2)
        assertThat(subtitles.first().url)
            .isEqualTo("https://rapid.filmmakinesi.to/subs/tr.vtt")
        assertThat(subtitles.first().language).isEqualTo("tr")
    }
}
