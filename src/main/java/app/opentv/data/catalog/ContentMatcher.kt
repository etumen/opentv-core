package app.opentv.data.catalog

import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import java.text.Normalizer
import java.util.Locale

enum class ContentMatchConfidence {
    EXTERNAL_ID,
    TITLE_AND_YEAR,
    EXACT_TITLE,
    NONE,
}

data class ContentMatch(
    val confidence: ContentMatchConfidence,
    val normalizedTitle: String,
    val year: Int?,
) {
    val isMatch: Boolean
        get() = confidence != ContentMatchConfidence.NONE
}

/**
 * Conservative provider-independent title matcher.
 *
 * V1 deliberately avoids fuzzy string similarity. Exact normalized titles may merge when their
 * years do not conflict; the same title with two known different years never merges.
 */
class ContentMatcher {
    fun match(
        leftTitle: String,
        leftType: ProviderMediaType,
        leftYear: Int?,
        rightTitle: String,
        rightType: ProviderMediaType,
        rightYear: Int?,
        leftExternalIds: Map<String, String> = emptyMap(),
        rightExternalIds: Map<String, String> = emptyMap(),
    ): ContentMatch {
        if (leftType != rightType) return ContentMatch(ContentMatchConfidence.NONE, "", null)

        val left = identity(leftTitle, leftYear)
        val right = identity(rightTitle, rightYear)
        val leftIds = normalizeExternalIds(leftExternalIds)
        val rightIds = normalizeExternalIds(rightExternalIds)
        val sharedIdKeys = leftIds.keys.intersect(rightIds.keys)
        if (sharedIdKeys.any { key -> leftIds[key] != rightIds[key] }) {
            return ContentMatch(ContentMatchConfidence.NONE, left.title, left.year)
        }
        if (sharedIdKeys.isNotEmpty()) {
            return ContentMatch(
                ContentMatchConfidence.EXTERNAL_ID,
                left.title.ifBlank { right.title },
                left.year ?: right.year,
            )
        }

        if (left.title.isBlank() || left.title != right.title) {
            return ContentMatch(ContentMatchConfidence.NONE, left.title, left.year)
        }

        if (left.year != null && right.year != null && left.year != right.year) {
            return ContentMatch(ContentMatchConfidence.NONE, left.title, left.year)
        }

        val year = left.year ?: right.year
        val confidence =
            if (left.year != null && right.year != null) {
                ContentMatchConfidence.TITLE_AND_YEAR
            } else {
                ContentMatchConfidence.EXACT_TITLE
            }
        return ContentMatch(confidence, left.title, year)
    }

    fun merge(items: Iterable<ProviderItem>): List<CanonicalContent> {
        val groups = mutableListOf<MutableList<ProviderItem>>()

        for (item in items) {
            val target = groups.firstOrNull { group ->
                group.all { existing ->
                    match(
                        leftTitle = existing.title,
                        leftType = existing.mediaType,
                        leftYear = existing.year,
                        rightTitle = item.title,
                        rightType = item.mediaType,
                        rightYear = item.year,
                        leftExternalIds = existing.externalIds,
                        rightExternalIds = item.externalIds,
                    ).isMatch
                }
            }
            if (target == null) groups += mutableListOf(item) else target += item
        }

        return groups.map(::canonicalize)
    }

    private fun canonicalize(group: List<ProviderItem>): CanonicalContent {
        require(group.isNotEmpty())
        val first = group.first()
        val identities = group.map { identity(it.title, it.year) }
        val year = identities.firstNotNullOfOrNull { it.year }
        val normalizedTitle = identities.first().title
        val best = group.maxByOrNull(::metadataScore) ?: first
        val externalIds = mergeExternalIds(group)

        return CanonicalContent(
            id = canonicalIdNormalized(first.mediaType, normalizedTitle, year, externalIds),
            title = best.title.trim(),
            mediaType = first.mediaType,
            posterUrl = group.firstNotNullOfOrNull { it.posterUrl?.takeIf(String::isNotBlank) },
            backdropUrl = group.firstNotNullOfOrNull { it.backdropUrl?.takeIf(String::isNotBlank) },
            description = group
                .mapNotNull { it.description?.takeIf(String::isNotBlank) }
                .maxByOrNull(String::length),
            year = year,
            rating = group.mapNotNull { it.rating }.maxOrNull(),
            genres = group.flatMap { it.genres }.distinct(),
            externalIds = externalIds,
            sources = group
                .distinctBy { it.providerId to it.id }
                .map(ProviderItem::toContentSource),
        )
    }

    private fun metadataScore(item: ProviderItem): Int =
        listOf(
            item.posterUrl,
            item.backdropUrl,
            item.description,
        ).count { !it.isNullOrBlank() } +
            if (item.year != null) 1 else 0 +
            if (item.rating != null) 1 else 0 +
            if (item.genres.isNotEmpty()) 1 else 0 +
            if (item.externalIds.isNotEmpty()) 2 else 0

    fun canonicalId(
        mediaType: ProviderMediaType,
        title: String,
        year: Int?,
        externalIds: Map<String, String> = emptyMap(),
    ): String = canonicalIdNormalized(
        mediaType = mediaType,
        normalizedTitle = identity(title, year).title,
        year = year,
        externalIds = normalizeExternalIds(externalIds),
    )

    private fun canonicalIdNormalized(
        mediaType: ProviderMediaType,
        normalizedTitle: String,
        year: Int?,
        externalIds: Map<String, String>,
    ): String {
        val stableId = listOf("imdb", "tmdb")
            .firstNotNullOfOrNull { key -> externalIds[key]?.let { key to it } }
        if (stableId != null) {
            return mediaType.name.lowercase(Locale.ROOT) + ":" +
                stableId.first + ":" + stableId.second
        }

        val slug = normalizedTitle
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
            .ifBlank { "untitled" }
        return mediaType.name.lowercase(Locale.ROOT) + ":" + slug + ":" + (year ?: "unknown")
    }

    private fun normalizeExternalIds(values: Map<String, String>): Map<String, String> =
        values.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim().lowercase(Locale.ROOT)
            val value = rawValue.trim().lowercase(Locale.ROOT)
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toMap()

    private fun mergeExternalIds(group: List<ProviderItem>): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        val conflicts = mutableSetOf<String>()
        group.forEach { item ->
            normalizeExternalIds(item.externalIds).forEach { (key, value) ->
                val existing = merged[key]
                when {
                    existing == null -> merged[key] = value
                    existing != value -> conflicts += key
                }
            }
        }
        conflicts.forEach(merged::remove)
        return merged
    }

    private fun identity(title: String, explicitYear: Int?): Identity {
        val trimmed = title.trim()
        val trailing = TRAILING_YEAR.find(trimmed)
        val inferredYear = trailing?.groupValues?.getOrNull(1)?.toIntOrNull()
        val withoutYear =
            if (trailing != null) trimmed.removeRange(trailing.range).trim() else trimmed

        val normalized = Normalizer.normalize(withoutYear, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

        return Identity(
            title = normalized,
            year = explicitYear ?: inferredYear,
        )
    }

    private data class Identity(
        val title: String,
        val year: Int?,
    )

    private companion object {
        val TRAILING_YEAR = Regex("""(?:\(|\[)?(19\d{2}|20\d{2})(?:\)|\])?\s*$""")
    }
}
