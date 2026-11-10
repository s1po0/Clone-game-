package app.mangascribe.translate

import app.mangascribe.core.Language
import app.mangascribe.data.TranslationCache

/**
 * Puts a persistent cache in front of any engine.
 *
 * A hit is a local read: no request, no rate limit, and it works with no network at all — which
 * is the difference between "this chapter costs 40 requests" and "this chapter costs 40 requests
 * once, ever". Misses are collected and sent as one batch, then written back, so the cost per
 * page does not change.
 *
 * The cache key includes the engine id, so switching engines returns that engine's own text
 * instead of a stale answer from the previous one.
 */
class CachedTranslator(
    private val inner: Translator,
    private val cache: TranslationCache,
) : Translator {

    override val id: String get() = inner.id
    override val displayName: String get() = inner.displayName
    override val requiresNetwork: Boolean get() = inner.requiresNetwork

    override fun codeFor(language: Language): String? = inner.codeFor(language)

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val keys = texts.map {
            TranslationCache.key(inner.id, sourceLanguage, targetLanguage, it)
        }
        val cached = cache.getAll(keys)

        val out = texts.toMutableList()
        val misses = mutableListOf<Int>()
        texts.forEachIndexed { index, _ ->
            val hit = cached[keys[index]]
            if (hit != null) out[index] = hit else misses += index
        }
        if (misses.isEmpty()) {
            // Everything was local: no request was made and the engine is reported as the source.
            return TranslationBatch(out, inner.id)
        }

        val batch = inner.translate(misses.map { texts[it] }, sourceLanguage, targetLanguage)
        val fresh = HashMap<String, String>(misses.size)
        misses.forEachIndexed { i, original ->
            val value = batch.lines.getOrNull(i)
            if (!value.isNullOrBlank()) {
                out[original] = value
                fresh[keys[original]] = value
            }
        }
        cache.putAll(fresh)
        return TranslationBatch(out, batch.engineId, batch.detectedSource, batch.unreachable)
    }
}
