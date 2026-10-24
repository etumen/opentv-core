package app.opentv.data.metadata

interface MetadataSource {
    val id: String

    suspend fun resolve(identity: MetadataIdentity): ContentMetadata?
}

interface MetadataCache {
    fun get(identity: MetadataIdentity): ContentMetadata?
    fun put(metadata: ContentMetadata)
}
