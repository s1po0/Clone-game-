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
 * There is no key: the Edge browser's own translation feature uses the same two public endpoints,
 * and a browser is what a browser may call.
 *
 *  - `GET  https://edge.microsoft.com/translate/auth` → a JWT, valid about ten minutes
 *  - `POST https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=ckb`
 *    with `{"Text":"..."}`; the reply is `[{"translations":[{"text":"...","to":"ckb"}]}]`
 *
 * Both facts are stable and documented in Microsoft's own web client; neither needs an account.
 */
class MicrosoftTranslateTranslator(
    private val http: Http,
    private val maxConcurrent: Int = 1,
) : Translator {

    override val id = EngineCodes.MICROSOFT
    override val displayName = "Microsoft Translator"
    override val requiresNetwork = true

    private val gate = Semaphore(maxConcurrent)

    /** The JWT and when it stops being usable. A token lasts ~10 minutes; renew at 8. */
    @Volatile private var token: String? = null
    @Volatile private var tokenExpiry: Long = 0L

    override fun codeFor(language: Language): String? = when (language.code) {
        // Microsoft's own name for the same language: Central Kurdish.
        "ckb" -> "ckb"
        "ku" -> "ku"
        "zh-Hans" -> "zh-Hans"
        "zh-Hant" -> "zh-Hant"
        else -> null
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

        // One request per line: Microsoft's public endpoint caps a request at 1000 characters and
        // a page of bubbles is not worth a 400 for. The pause keeps the token and the quota happy.
        texts.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val result = runCatching {
                gate.withPermit {
                    delay(PLEASANT_DELAY_MS)
                    requestLine(line, sl, tl)
                }
            }
            val value = result.getOrNull()
            if (!value.isNullOrBlank()) {
                out[index] = value
            } else {
                val error = result.exceptionOrNull()
                failures += error?.message ?: "no answer"
                if (error is java.net.UnknownHostException || error is java.net.ConnectException) {
                    dead += id
                }
            }
        }

        if (out.indices.all { out[it] == texts[it] } && texts.any { it.isNotBlank() }) {
            throw TranslationException(
                "Microsoft Translator did not answer (${failures.firstOrNull() ?: "no response"})",
            )
        }
        return TranslationBatch(out, id, unreachable = dead)
    }

    private suspend fun requestLine(line: String, sl: String, tl: String): String? {
        val auth = token()
        val body = jsonBody(line)
        val url = "https://api-edge.cognitive.microsofttranslator.com/translate" +
            "?api-version=3.0&to=$tl" + if (sl != "auto") "&from=$sl" else ""
        val raw = http.postJson(
            url,
            body,
            headers = mapOf(
                "Authorization" to "Bearer $auth",
                "Content-Type" to "application/json",
                "User-Agent" to UA,
            ),
        )
        return MicrosoftResponseParser.parse(raw)
    }

    /** The JWT, fetched once and reused until it is nearly expired. */
    private suspend fun token(): String {
        val current = token
        if (current != null && System.currentTimeMillis() < tokenExpiry) return current
        val fresh = http.get(
            "https://edge.microsoft.com/translate/auth",
            headers = mapOf("User-Agent" to UA, "Accept" to "*/*"),
        ).trim()
        if (fresh.isBlank() || fresh.startsWith("<")) {
            throw TranslationException("Microsoft's token endpoint answered with a web page")
        }
        token = fresh
        // Renew at eight minutes: the token is good for ten, and a page can outlive a short one.
        tokenExpiry = System.currentTimeMillis() + 8 * 60 * 1000L
        return fresh
    }

    /** `{"Text":"..."}` — JSON-escaped by hand so no serialisation plugin is needed here. */
    private fun jsonBody(line: String): String = buildString {
        append("[{\"Text\":\"")
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
        append("\"}]")
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val PLEASANT_DELAY_MS = 250L
    }
}

/** The reply is `[{"translations":[{"text":"...","to":"ckb"}]}]`; the first one is the answer. */
object MicrosoftResponseParser {
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
