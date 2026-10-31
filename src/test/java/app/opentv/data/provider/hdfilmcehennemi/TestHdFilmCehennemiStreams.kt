package app.opentv.data.provider.hdfilmcehennemi

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import app.opentv.data.provider.ProviderPlaybackTarget
import org.junit.Test

class TestHdFilmCehennemiStreams {

    @Test
    fun testLiveStreams() = runBlocking {
        val http = OkHttpClient()
        val provider = HdFilmCehennemiProvider(http)

        println("=== Testing HDFilmCehennemi Streams ===")
        val movieUrl = "https://www.hdfilmcehennemi.nl/the-dog-stars-2/"
        val target = ProviderPlaybackTarget.Movie("hdfilmcehennemi", movieUrl)
        val res = provider.streams(target)
        println("Streams result: $res")
    }
}
