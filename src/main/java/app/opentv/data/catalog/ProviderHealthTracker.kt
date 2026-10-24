package app.opentv.data.catalog

import android.content.SharedPreferences

/**
 * Lightweight persistent provider reliability score.
 *
 * New providers start neutral (0.5). Successful stream resolution moves the score toward 1.0;
 * failures move it toward 0.0. Scores are intentionally slow-moving so one temporary outage does
 * not permanently bury an otherwise healthy source.
 */
class ProviderHealthTracker(
    private val prefs: SharedPreferences? = null,
) {
    @Synchronized
    fun score(providerId: String): Double =
        prefs?.getFloat(scoreKey(providerId), DEFAULT_SCORE.toFloat())?.toDouble()
            ?: memoryScores[providerId]
            ?: DEFAULT_SCORE

    @Synchronized
    fun samples(providerId: String): Int =
        prefs?.getInt(samplesKey(providerId), 0)
            ?: memorySamples[providerId]
            ?: 0

    @Synchronized
    fun recordSuccess(providerId: String) {
        update(providerId, target = 1.0)
    }

    @Synchronized
    fun recordFailure(providerId: String) {
        update(providerId, target = 0.0)
    }

    @Synchronized
    fun reset(providerId: String) {
        memoryScores.remove(providerId)
        memorySamples.remove(providerId)
        prefs?.edit()
            ?.remove(scoreKey(providerId))
            ?.remove(samplesKey(providerId))
            ?.apply()
    }

    private fun update(providerId: String, target: Double) {
        val current = score(providerId)
        val next = (current * HISTORY_WEIGHT + target * NEW_SAMPLE_WEIGHT)
            .coerceIn(0.0, 1.0)
        val count = samples(providerId) + 1

        memoryScores[providerId] = next
        memorySamples[providerId] = count
        prefs?.edit()
            ?.putFloat(scoreKey(providerId), next.toFloat())
            ?.putInt(samplesKey(providerId), count)
            ?.apply()
    }

    private val memoryScores = linkedMapOf<String, Double>()
    private val memorySamples = linkedMapOf<String, Int>()

    private fun scoreKey(providerId: String) = "score_" + providerId
    private fun samplesKey(providerId: String) = "samples_" + providerId

    private companion object {
        const val DEFAULT_SCORE = 0.5
        const val HISTORY_WEIGHT = 0.8
        const val NEW_SAMPLE_WEIGHT = 0.2
    }
}
