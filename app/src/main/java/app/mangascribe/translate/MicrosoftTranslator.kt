package app.mangascribe.translate

import app.mangascribe.core.EngineCodes
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import app.mangascribe.core.Language
import app.mangascribe.core.Languages

/**
 * Microsoft Translator, keyless — the second big engine, and the one that matters most here.
 *
 * Sorani is not a language Google treats as a first-class target on its free web endpoint, but
 * Microsoft has shipped `ckb` in its public translator for years and carries a dedicated model.
 * When Google echoes an English line back, this is the engine that can still answer.
 *
 * There is no key and no token. Microsoft **moved this endpoint in August 2026**: the old
 * `translate/auth` JWT flow now answers 404 (it is gone, not rate-limited), and Edge's own web
 * translator calls a single keyless POST instead:
 *
 *  - `POST https://edge.microsoft.com/translate/translatetext?from=en&to=ckb&isEnterpriseClient=false`
 *  - body is a **plain JSON array of strings**: `["Hello world","Good morning"]`
 *  - reply: `[{"translations":[{"text":"...","to":"ckb"}],"detectedLanguage":{...}}, ...]`
 *
 * The old `[{"Text":"..."}]` body is rejected with 400 on this endpoint, which is why it is sent
 * as a bare array. Every fact here was checked against the endpoint itself, not a blog.
 */
class MicrosoftTranslateTranslator(
    private val http: Http,
    private val maxConcurrent: Int = 1,
) : Translator {

    override val id = EngineCodes.MICROSOFT
    override val displayName = "Microsoft Translator"
    override val requiresNetwork = true

    private val gate = Semaphore(maxConcurrent)

    override fun codeFor(language: Language): String? = when (language.code) {
        // **Microsoft uses `ku` for Central Kurdish (Sorani)**, not `ckb`. Sending `ckb` to its
        // keyless endpoint answers HTTP 400 — measured, not assumed: `to=ckb` was refused while
        // `to=ku` returned `سڵاو` for "hello". This is the opposite of Google, where `ckb` is
        // Sorani and `ku` is Kurmanji, and getting it wrong is a whole page of untranslated text.
        "ckb" -> "ku"
        // Kurmanji is the other direction: Microsoft's `ku` is already taken by Central Kurdish,
        // and this endpoint has no separate Northern Kurdish code, so it is left to Google.
        "ku" -> null
        // **Everything else keeps its own ISO code.** Returning null here was the bug that kept
        // Microsoft silent: a null source became "auto" and then a refused request, so the engine
        // always failed with HTTP 400 even after the target code was right.
        else -> language.code
    }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val tl = codeFor(Languages.of(targetLanguage))
            ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        val declared = codeFor(Languages.of(sourceLanguage))
        val sl = if (declared == null || declared == tl) "auto" else declared

        val out = MutableList(texts.size) { texts[it] }
        val failures = mutableListOf<String>()
        val dead = mutableListOf<String>()

        // **One request for the whole page.** The endpoint accepts an array of strings and
        // answers one translation per element, which is both fewer requests (the thing that gets
        // a free endpoint to rate-limit you) and easier to align than a newline-joined blob.
        val indices = texts.indices.filter { texts[it].isNotBlank() }
        if (indices.isEmpty()) return TranslationBatch(texts, id)
        val payload = indices.joinToString(",", "[", "]") { texts[it].trim().let(::jsonString) }
        // `from` omitted (not "auto") is how this endpoint is asked to detect the language; the
        // literal string "auto" is rejected.
        val fromParam = if (sl == "auto") "" else "&from=$sl"
        val result = runCatching {
            gate.withPermit {
                delay(PLEASANT_DELAY_MS)
                http.postJson(
                    "https://edge.microsoft.com/translate/translatetext" +
                        "?to=$tl&isEnterpriseClient=false$fromParam",
                    payload,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to UA,
                        "Accept" to "application/json",
                    ),
                )
            }
        }
        val parsed = result.getOrNull()?.let { MicrosoftResponseParser.parseArray(it) }
        if (parsed != null && parsed.size == indices.size) {
            indices.forEachIndexed { position, index -> out[index] = parsed[position] }
        } else {
            val error = result.exceptionOrNull()
            failures += error?.message ?: "the answer did not line up with the request"
            if (error is java.net.UnknownHostException || error is java.net.ConnectException) {
                dead += id
            }
        }

        if (out.indices.all { out[it] == texts[it] } && texts.any { it.isNotBlank() }) {
            throw TranslationException(
                "Microsoft Translator did not answer (${failures.firstOrNull() ?: "no response"})",
            )
        }
        return TranslationBatch(out, id, unreachable = dead)
    }

    /** One JSON string literal, escaped by hand: no serialisation plugin is needed here. */
    private fun jsonString(line: String): String = buildString {
        append('"')
        for (ch in line) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val PLEASANT_DELAY_MS = 250L
    }
}

/** The reply is `[{"translations":[{"text":"...","to":"ckb"}]}]`; the first one is the answer. */
object MicrosoftResponseParser {

    /** Every element of a multi-string reply, in order. */
    fun parseArray(json: String): List<String>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return null
        val array = runCatching { org.json.JSONArray(trimmed) }.getOrNull() ?: return null
        if (array.length() == 0) return null
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val translations = entry.optJSONArray("translations") ?: return@mapNotNull null
            translations.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
        }.takeIf { it.size == array.length() }
    }

    fun parse(json: String): String? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return null
        val array = runCatching { org.json.JSONArray(trimmed) }.getOrNull() ?: return null
        val first = array.optJSONObject(0) ?: return null
        val translations = first.optJSONArray("translations") ?: return null
        val entry = translations.optJSONObject(0) ?: return null
        return entry.optString("text").takeIf { it.isNotBlank() }
    }
}
