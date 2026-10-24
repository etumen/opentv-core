/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider

/**
 * Metadata describing a provider exposed by a provider repository.
 *
 * The descriptor deliberately contains no executable code. A later repository implementation can
 * map a descriptor to a native provider factory without coupling this contract to GitHub, HTTP,
 * Compose, or Android.
 */
data class ProviderDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val mediaTypes: Set<ProviderMediaType>,
    val enabledByDefault: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Source of provider metadata. Implementations may be built-in, file-backed or remote.
 */
interface ProviderRepositorySource {
    val id: String

    suspend fun providers(): ProviderResult<List<ProviderDescriptor>>
}
