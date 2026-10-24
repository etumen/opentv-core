package app.opentv.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class ImdbSuggestionMetadataSource(
    private val http: OkHttpClient,
) : MetadataSource {
    override val id: String = "imdb"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun resolve(identity: MetadataIdentity): ContentMetadata? {
        val imdbId = identity.externalIds["imdb"]
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("""tt\d{5,12}""")) }
            ?: return null

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://v2.sg.media-imdb.com/suggestion/x/$imdbId.json")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseResponse(response.body?.string().orEmpty(), imdbId)
            }
        }
    }

    internal fun parseResponse(body: String, imdbId: String): ContentMetadata? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrNull() ?: return null
        val item = root["d"]?.jsonArray
            ?.firstOrNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    ?.equals(imdbId, ignoreCase = true) == true
            }
            ?.jsonObject
            ?: return null

        val title = item["l"]?.jsonPrimitive?.contentOrNull
        val year = item["y"]?.jsonPrimitive?.intOrNull
        val poster = item["i"]?.jsonObject
            ?.get("imageUrl")
            ?.jsonPrimitive
            ?.contentOrNull
        val cast = item["s"]?.jsonPrimitive?.contentOrNull
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return ContentMetadata(
            sourceId = id,
            externalIds = mapOf("imdb" to imdbId.lowercase()),
            title = title,
            year = year,
            posterUrl = poster,
            cast = cast,
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
