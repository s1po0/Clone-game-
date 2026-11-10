package app.mangascribe.translate

import app.mangascribe.core.Languages

/**
 * Answer "why did it not translate?" from inside the app, on the reader's own phone and network.
 *
 * Every previous round of this bug was guessed at from a screenshot. The phone knows the truth:
 * which URL it sent, what status the endpoint returned, and what came back. This runs that
 * request and reports it in words the reader can paste back — a control tower instead of a
 * guessing game.
 *
 * It is deliberately dumb: one plain GET per route, the exact URLs the translators use, and a
 * line of plain text per route. No retries, no cooldowns, no cache.
 */
object SelfTest {

    data class Step(
        val name: String,
        /** What happened, in one sentence a reader can act on. */
        val outcome: String,
        val ok: Boolean,
        /** First 120 characters of the body, so a failure can be recognised from a screenshot. */
        val sample: String = "",
    )

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun run(http: Http, targetLanguage: String = "ckb"): List<Step> {
        val tl = Languages.of(targetLanguage).code
        val steps = mutableListOf<Step>()
        val phrase = "hello"

        steps += probe(
            http,
            "Google (translate.googleapis.com)",
            "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=auto&tl=$tl&dt=t&q=$phrase",
        )
        steps += probe(
            http,
            "Google (no extra options)",
            "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=auto&tl=$tl&dt=t&ie=UTF-8&oe=UTF-8&q=$phrase",
        )
        steps += probe(
            http,
            "Google dictionary route",
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex" +
                "&sl=en&tl=$tl&q=$phrase",
        )
        // Sanity check on the network itself: if this fails, nothing else can work.
        steps += probe(
            http,
            "The phone's internet (example.com)",
            "https://example.com",
        )
        return steps
    }

    private suspend fun probe(http: Http, name: String, url: String): Step {
        val result = runCatching {
            http.get(url, headers = mapOf("User-Agent" to UA, "Accept" to "application/json"))
        }
        val body = result.getOrNull()
        if (body == null) {
            val error = result.exceptionOrNull()
            return Step(
                name = name,
                outcome = humanise(error),
                ok = false,
            )
        }
        val looksKurdish = body.any { it in '\u0600'..'\u06FF' }
        val sample = body.trim().replace(Regex("\\s+"), " ").take(120)
        return when {
            looksKurdish -> Step(name, "Answered in Kurdish letters.", true, sample)
            name.startsWith("The phone") -> Step(name, "Reached the internet.", true, sample)
            else -> Step(
                name,
                "Answered, but not in Kurdish (English or an error page came back).",
                false,
                sample,
            )
        }
    }

    private fun humanise(error: Throwable?): String {
        val m = error?.message.orEmpty()
        return when {
            m.contains("HTTP 429") -> "Rate-limited (HTTP 429) — the endpoint is refusing this phone."
            m.contains("HTTP 403") -> "Refused (HTTP 403) — the endpoint blocked this request."
            m.contains("HTTP") -> "The endpoint answered: ${m.take(80)}"
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "Timed out — the connection is too slow or blocked."
            m.contains("Unable to resolve host") || m.contains("UnknownHost") ->
                "No DNS — the phone cannot find the address at all."
            m.contains("SSL", true) || m.contains("certificate", true) ->
                "TLS refused — something on the network is intercepting."
            error == null -> "No answer and no error."
            else -> m.take(120)
        }
    }
}
