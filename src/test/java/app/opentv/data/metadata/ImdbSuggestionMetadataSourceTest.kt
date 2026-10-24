package app.opentv.data.metadata

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

class ImdbSuggestionMetadataSourceTest {
    private val source = ImdbSuggestionMetadataSource(OkHttpClient())

    @Test
    fun parseResponse_readsTitleYearPosterAndCast() {
        val body = """
            {
              "d": [
                {
                  "i": {
                    "height": 755,
                    "imageUrl": "https://m.media-amazon.com/reacher.jpg",
                    "width": 604
                  },
                  "id": "tt9288030",
                  "l": "Reacher",
                  "q": "TV series",
                  "s": "Alan Ritchson, Maria Sten",
                  "y": 2022
                }
              ],
              "q": "tt9288030",
              "v": 1
            }
        """.trimIndent()

        val metadata = source.parseResponse(body, "tt9288030")

        assertThat(metadata?.title).isEqualTo("Reacher")
        assertThat(metadata?.year).isEqualTo(2022)
        assertThat(metadata?.posterUrl).contains("reacher.jpg")
        assertThat(metadata?.cast).containsExactly("Alan Ritchson", "Maria Sten").inOrder()
        assertThat(metadata?.externalIds).containsEntry("imdb", "tt9288030")
    }
}
