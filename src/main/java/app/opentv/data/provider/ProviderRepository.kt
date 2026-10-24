/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Host-side facade around native providers.
 *
 * Every call is isolated and normalised here. A provider exception becomes a provider-scoped
 * failure, while cancellation still propagates normally.
 */
class ProviderRepository(
    private val registry: ProviderRegistry,
) {
    fun providers(mediaType: ProviderMediaType? = null): List<Provider> =
        if (mediaType == null) registry.all() else registry.supporting(mediaType)

    suspend fun catalogSections(
        providerId: String,
        mediaType: ProviderMediaType,
    ): ProviderResult<List<ProviderCatalogSection>> {
        val provider = registry.find(providerId) ?: return missing(providerId, "catalogSections")
        if (mediaType !in provider.supportedMediaTypes) {
            return unsupported(provider, "catalogSections", mediaType)
        }
        return call(provider, "catalogSections") {
            provider.discoverCatalogSections(mediaType)
        }
    }

    suspend fun catalog(
        providerId: String,
        request: ProviderCatalogRequest,
    ): ProviderResult<ProviderCatalogPage> {
        val provider = registry.find(providerId) ?: return missing(providerId, "catalog")
        if (request.mediaType !in provider.supportedMediaTypes) {
            return unsupported(provider, "catalog", request.mediaType)
        }
        return when (val result = call(provider, "catalog") { provider.catalog(request) }) {
            is ProviderResult.Success ->
                ProviderResult.Success(ProviderResultMapper.catalog(provider, result.value, request.mediaType))
            is ProviderResult.Failure -> result
        }
    }

    suspend fun catalogAll(
        request: ProviderCatalogRequest,
    ): ProviderBatchResult<ProviderCatalogPage> =
        batch(registry.supporting(request.mediaType)) { provider ->
            catalog(provider.id, request)
        }

    suspend fun search(
        providerId: String,
        request: ProviderSearchRequest,
    ): ProviderResult<List<ProviderItem>> {
        val provider = registry.find(providerId) ?: return missing(providerId, "search")
        val requestedType = request.mediaType
        if (requestedType != null && requestedType !in provider.supportedMediaTypes) {
            return unsupported(provider, "search", requestedType)
        }
        return when (val result = call(provider, "search") { provider.search(request) }) {
            is ProviderResult.Success ->
                ProviderResult.Success(ProviderResultMapper.items(provider, result.value, requestedType))
            is ProviderResult.Failure -> result
        }
    }

    suspend fun searchAll(
        request: ProviderSearchRequest,
    ): ProviderBatchResult<List<ProviderItem>> =
        batch(providers(request.mediaType)) { provider ->
            search(provider.id, request)
        }

    suspend fun load(providerId: String, itemId: String): ProviderResult<ProviderDetails> {
        val provider = registry.find(providerId) ?: return missing(providerId, "load")
        return when (val result = call(provider, "load") { provider.load(itemId) }) {
            is ProviderResult.Success -> {
                val mapped = ProviderResultMapper.details(provider, result.value)
                if (mapped != null) ProviderResult.Success(mapped)
                else invalid(provider, "load", "Provider returned an invalid item")
            }
            is ProviderResult.Failure -> result
        }
    }

    suspend fun episodes(providerId: String, seriesId: String): ProviderResult<List<ProviderEpisode>> {
        val provider = registry.find(providerId) ?: return missing(providerId, "episodes")
        if (ProviderMediaType.SERIES !in provider.supportedMediaTypes) {
            return unsupported(provider, "episodes", ProviderMediaType.SERIES)
        }
        return when (val result = call(provider, "episodes") { provider.episodes(seriesId) }) {
            is ProviderResult.Success ->
                ProviderResult.Success(ProviderResultMapper.episodes(provider, seriesId, result.value))
            is ProviderResult.Failure -> result
        }
    }

    suspend fun streams(target: ProviderPlaybackTarget): ProviderResult<List<ProviderStream>> {
        val provider = registry.find(target.providerId)
            ?: return missing(target.providerId, "streams")
        return when (val result = call(provider, "streams") { provider.streams(target) }) {
            is ProviderResult.Success ->
                ProviderResult.Success(ProviderResultMapper.streams(provider, result.value))
            is ProviderResult.Failure -> result
        }
    }

    suspend fun subtitles(target: ProviderPlaybackTarget): ProviderResult<List<ProviderSubtitle>> {
        val provider = registry.find(target.providerId)
            ?: return missing(target.providerId, "subtitles")
        return when (val result = call(provider, "subtitles") { provider.subtitles(target) }) {
            is ProviderResult.Success ->
                ProviderResult.Success(ProviderResultMapper.subtitles(provider, result.value))
            is ProviderResult.Failure -> result
        }
    }

    private suspend fun <T> batch(
        providers: List<Provider>,
        block: suspend (Provider) -> ProviderResult<T>,
    ): ProviderBatchResult<T> = coroutineScope {
        val results = providers.map { provider ->
            async { provider.id to block(provider) }
        }.awaitAll()

        val values = linkedMapOf<String, T>()
        val failures = linkedMapOf<String, ProviderError>()
        results.forEach { (providerId, result) ->
            when (result) {
                is ProviderResult.Success -> values[providerId] = result.value
                is ProviderResult.Failure -> failures[providerId] = result.error
            }
        }
        ProviderBatchResult(values, failures)
    }

    private fun missing(providerId: String, operation: String): ProviderResult.Failure =
        ProviderResult.Failure(
            ProviderError(
                providerId = providerId,
                operation = operation,
                code = ProviderErrorCode.NOT_FOUND,
                message = "Provider is not registered",
            ),
        )

    private fun unsupported(
        provider: Provider,
        operation: String,
        mediaType: ProviderMediaType,
    ): ProviderResult.Failure =
        ProviderResult.Failure(
            ProviderError(
                providerId = provider.id,
                operation = operation,
                code = ProviderErrorCode.UNSUPPORTED,
                message = "${provider.name} does not support $mediaType",
            ),
        )

    private fun invalid(
        provider: Provider,
        operation: String,
        message: String,
    ): ProviderResult.Failure =
        ProviderResult.Failure(
            ProviderError(
                providerId = provider.id,
                operation = operation,
                code = ProviderErrorCode.INVALID_DATA,
                message = message,
            ),
        )

    private suspend fun <T> call(
        provider: Provider,
        operation: String,
        block: suspend () -> ProviderResult<T>,
    ): ProviderResult<T> =
        try {
            when (val result = block()) {
                is ProviderResult.Success -> result
                is ProviderResult.Failure ->
                    ProviderResult.Failure(
                        result.error.copy(providerId = provider.id, operation = operation),
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderResult.Failure(
                ProviderError(
                    providerId = provider.id,
                    operation = operation,
                    code = ProviderErrorCode.UNKNOWN,
                    message = e.message ?: "Provider operation failed",
                    cause = e,
                ),
            )
        }
}
