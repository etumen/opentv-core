package app.opentv.data.metadata

class MetadataRepository(
    private val sources: List<MetadataSource>,
    private val cache: MetadataCache? = null,
) {
    suspend fun resolve(identity: MetadataIdentity): ContentMetadata? {
        cache?.get(identity)?.let { return it }

        for (source in sources) {
            val metadata = runCatching { source.resolve(identity) }.getOrNull() ?: continue
            cache?.put(metadata)
            return metadata
        }
        return null
    }
}
