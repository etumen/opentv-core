package app.opentv.data.metadata

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SharedPreferencesMetadataCache(
    private val prefs: SharedPreferences,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MetadataCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun get(identity: MetadataIdentity): ContentMetadata? {
        val key = cacheKey(identity) ?: return null
        val raw = prefs.getString(key, null) ?: return null
        val cached = runCatching {
            json.decodeFromString<CachedMetadata>(raw)
        }.getOrNull() ?: return null

        if (nowMillis() - cached.savedAtMillis > CACHE_TTL_MILLIS) {
            prefs.edit().remove(key).apply()
            return null
        }
        return cached.metadata
    }

    override fun put(metadata: ContentMetadata) {
        val key = cacheKey(MetadataIdentity(metadata.externalIds)) ?: return
        val payload = json.encodeToString(
            CachedMetadata(
                savedAtMillis = nowMillis(),
                metadata = metadata,
            ),
        )
        prefs.edit().putString(key, payload).apply()
    }

    private fun cacheKey(identity: MetadataIdentity): String? {
        val imdb = identity.externalIds["imdb"]?.trim()?.lowercase()
        if (!imdb.isNullOrBlank()) return "imdb:$imdb"

        val first = identity.externalIds
            .map { it.key.trim().lowercase() to it.value.trim().lowercase() }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .sortedBy { it.first }
            .firstOrNull()
            ?: return null
        return first.first + ":" + first.second
    }

    @Serializable
    private data class CachedMetadata(
        val savedAtMillis: Long,
        val metadata: ContentMetadata,
    )

    private companion object {
        const val CACHE_TTL_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
