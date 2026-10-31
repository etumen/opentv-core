package app.opentv.data.provider

import java.net.URI

/** Immutable, data-only settings. Remote rules never contain scripts or executable code. */
data class RemoteListingRule(
    val mediaType: ProviderMediaType,
    val pathTemplate: String,
    val itemSelector: String,
    val linkSelector: String,
    val titleSelector: String,
    val imageSelector: String?,
    val nextSelector: String?,
)

data class RemoteProviderRule(
    val domain: String? = null,
    val listings: Map<String, RemoteListingRule> = emptyMap(),
)

data class RemoteRuleSet(
    val version: Int = 0,
    val providers: Map<String, RemoteProviderRule> = emptyMap(),
)

/** Atomic swap on publish/rollback: active requests see either the old or the new rule set. */
object ProviderRuntimeRules {
    @Volatile private var current = RemoteRuleSet()

    val supportedIds = setOf(
        "fullhdfilmizlesene", "filmmakinesi", "hdfilmcehennemi",
        "sinemacx", "jetfilmizle", "dizipal", "ddizibox",
        "dramadizilerim", "dizimom",
    )

    /** Default document hosts; CDN/player domains are deliberately not rewritten. */
    val defaultDomains = mapOf(
        "fullhdfilmizlesene" to "www.fullhdfilmizlesene.now",
        "filmmakinesi" to "filmmakinesi.co",
        "hdfilmcehennemi" to "www.hdfilmcehennemi.nl",
        "sinemacx" to "sinemacc.com",
        "jetfilmizle" to "jetfilmizle.app",
        "ddizibox" to "ddizibox.com",
        "dramadizilerim" to "dramadizilerim.com",
        "dizimom" to "ddizimom.net",
    )

    fun snapshot(): RemoteRuleSet = current

    @Synchronized
    fun install(rules: RemoteRuleSet) {
        require(rules.providers.keys.all { it in supportedIds })
        current = rules
    }

    fun domain(providerId: String): String? = current.providers[providerId]?.domain
    fun listing(providerId: String, section: String?): RemoteListingRule? =
        section?.let { current.providers[providerId]?.listings?.get(it) }

    /** Used in the shared OkHttp client only for an exact known document host. */
    fun remapHost(host: String): String? {
        val rule = current.providers.entries.firstOrNull { (id, config) ->
            defaultDomains[id]?.equals(host, ignoreCase = true) == true && config.domain != null
        }?.value ?: return null
        return runCatching { URI(rule.domain).host }.getOrNull()
    }
}
