package app.mangascribe.translate

import app.mangascribe.core.EngineCodes
import app.mangascribe.core.Language
import app.mangascribe.core.Languages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Google Translate.
 *
 * This uses the public web endpoint Google's own translator page calls
 * (`translate_a/single?client=gtx`), which needs no key and covers every language the app
 * offers — including Central Kurdish (Sorani, `ckb`), which the free engines in this app do
 * not. It is why it is the default: MyMemory's crowd translations were producing the wrong
 * dialogue, and a reader cannot tell a bad translation from a bad OCR pass.
 *
 * Being an unofficial endpoint it has no SLA: it rate-limits bursts with HTTP 429 and
 * occasionally changes shape. So the engine
 *  - sends at most [maxConcurrent] requests at a time,
 *  - retries a 429/5xx twice with backoff,
 *  - caches each line, so re-reading a page costs nothing,
 *  - and lets [TranslationPipeline] fall back to another engine when it still fails.
 *
 * For a published app with real traffic, use the official Cloud Translation API (a key and
 * billing) through the same interface: swap this class, nothing else changes.
 */
class GoogleTranslateTranslator(
    private val http: Http,
    private val maxConcurrent: Int = 4,
) : Translator {

    override val id = EngineCodes.GOOGLE
    override val displayName = "Google Translate"
    override val requiresNetwork = true

    private val gate = Semaphore(maxConcurrent)
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 600
    }
    private val cacheLock = Any()

    override fun codeFor(language: Language): String? =
        GoogleCodes.forLanguage(language) ?: super.codeFor(language)

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val source = Languages.of(sourceLanguage)
        val target = Languages.of(targetLanguage)
        val sl = codeFor(source) ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        val tl = codeFor(target) ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))

        val out = MutableList(texts.size) { texts[it] }
        val failures = mutableListOf<String>()

        // One coroutine per line, capped by the semaphore inside requestWithRetry; a plain
        // try/catch because Kotlin will not take a `return@launch` inside a runCatching
        // lambda, and a failure must not cancel its siblings.
        kotlinx.coroutines.coroutineScope {
            val jobs = texts.mapIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@mapIndexed null
                launch {
                    val key = sl + "|" + tl + "|" + line
                    val cached = synchronized(cacheLock) { cache[key] }
                    if (cached != null) {
                        out[index] = cached
                        return@launch
                    }

                    try {
                        val translated = requestWithRetry(line, sl, tl)
                        if (translated.isNotBlank()) {
                            out[index] = translated
                            synchronized(cacheLock) { cache[key] = translated }
                        }
                    } catch (error: Throwable) {
                        synchronized(cacheLock) { failures += (error.message ?: "error") }
                    }
                }
            }
            jobs.forEach { it?.join() }
        }

        // Nothing at all came back: say why, so the pipeline tries the next engine and the
        // page shows a reason instead of the source text pretending to be a translation.
        if (out.indices.all { out[it] == texts[it] } && texts.any { it.isNotBlank() }) {
            val why = synchronized(cacheLock) { failures.firstOrNull() } ?: "no response"
            throw TranslationException("Google Translate did not answer ($why)")
        }
        return TranslationBatch(out, id)
    }

    private suspend fun requestWithRetry(line: String, sl: String, tl: String): String {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                gate.withPermit { return request(line, sl, tl) }
            } catch (t: Throwable) {
                last = t
                val transient = t.message?.contains("429") == true ||
                    t.message?.contains(" 5") == true ||
                    t.message?.contains("timeout", ignoreCase = true) == true
                if (!transient || attempt == 2) throw t
                delay(400L * (attempt + 1))
            }
        }
        throw last ?: TranslationException("Google Translate request failed")
    }

    private suspend fun request(line: String, sl: String, tl: String): String {
        val url = buildString {
            append("https://translate.googleapis.com/translate_a/single?client=gtx")
            append("&sl=").append(sl)
            append("&tl=").append(tl)
            append("&dt=t&ie=UTF-8&oe=UTF-8&q=")
            append(java.net.URLEncoder.encode(line, "UTF-8"))
        }
        val body = http.get(
            url,
            headers = mapOf(
                // The endpoint answers without these, but a browser-shaped call is less likely
                // to be handed a consent page instead of JSON.
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                "Accept" to "application/json",
            ),
        )
        return GoogleResponseParser.parse(body)
            ?: throw TranslationException("Google Translate returned an unexpected response")
    }
}

/** Google's language codes, where they differ from the app's. */
object GoogleCodes {
    fun forLanguage(language: Language): String? = when (language.code) {
        // Central Kurdish is `ckb`; the bare `ku` is Kurmanji, a different language.
        "ckb" -> "ckb"
        "ku" -> "ku"
        "zh-Hans" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        // Google handles every other code in the app as-is; null means "use language.code".
        else -> null
    }
}

/**
 * The response is a nested array, not an object:
 * `[[["translated","source",null,null,10],[...more segments...]],null,"ja",...]`
 * The translation is every `[i][0]` of the first element joined in order — a sentence split
 * across segments must not be truncated to its first fragment.
 */
object GoogleResponseParser {

    fun parse(json: String): String? {
        val segments = parseSegments(json) ?: return null
        val text = segments.joinToString("")
        return unescapeHtml(text).takeIf { it.isNotBlank() }
    }

    /** Raw segments, exposed for tests: the joining rule is the interesting part. */
    fun parseSegments(json: String): List<String>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return null
        val array = runCatching { org.json.JSONArray(trimmed) }.getOrNull() ?: return null
        val first = array.optJSONArray(0) ?: return null
        val out = mutableListOf<String>()
        for (i in 0 until first.length()) {
            val segment = first.optJSONArray(i) ?: continue
            val piece = segment.optString(0)
            if (piece.isNotEmpty()) out += piece
        }
        return out
    }

    private fun unescapeHtml(text: String): String =
        text.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
}
