/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider

/**
 * Small in-process registry for native providers.
 *
 * Provider ids are stable keys and duplicates are rejected so two implementations can never
 * silently shadow one another.
 */
class ProviderRegistry(initialProviders: Iterable<Provider> = emptyList()) {
    private val providers = linkedMapOf<String, Provider>()

    init {
        initialProviders.forEach(::register)
    }

    @Synchronized
    fun register(provider: Provider) {
        require(provider.id.isNotBlank()) { "Provider id must not be blank" }
        require(provider.name.isNotBlank()) { "Provider name must not be blank" }
        require(provider.id !in providers) { "Provider id already registered: ${provider.id}" }
        providers[provider.id] = provider
    }

    @Synchronized
    fun unregister(providerId: String): Provider? = providers.remove(providerId)

    @Synchronized
    fun find(providerId: String): Provider? = providers[providerId]

    @Synchronized
    fun all(): List<Provider> = providers.values.toList()

    @Synchronized
    fun supporting(mediaType: ProviderMediaType): List<Provider> =
        providers.values.filter { mediaType in it.supportedMediaTypes }
}
