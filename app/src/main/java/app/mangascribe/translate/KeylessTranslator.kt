package app.mangascribe.translate

import app.mangascribe.core.EngineCodes
import app.mangascribe.core.Language
import app.mangascribe.core.Languages
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Keyless translation that keeps working when one endpoint stops.
 *
 * The app already had one keyless Google route (`translate_a/single?client=gtx`). When that
 * route rate-limits — which is exactly what happens on a ten-page chapter — the app had nothing
 * left for Kurdish: MyMemory has no Kurdish at all, LibreTranslate has none either, and the page
 * failed with "MyMemory returned no text".
 *
 * This engine is a *rotation*, not a second guess. Four independent keyless endpoints are tried
 * in order, each with its own parser, so a 429, a 403 from a changed `client` value, a consent
 * page or a dead mirror costs one request and moves on:
 *
 *  1. `translate.googleapis.com/translate_a/single?client=gtx` — the endpoint the web UI uses;
 *  2. `clients5.google.com/translate_a/t?client=dict-chrome-ex` — the Chrome dictionary
 *     extension's endpoint, which answers with a different shape (`{"sentences":[…]}`) and,
 *     in practice, has a separate quota from (1);
 *  3. **Lingva** (`/api/v1/{source}/{target}/{query}`) — a free front-end for Google Translate
 *     with public instances; each instance is its own server, so a rate limit is per host;
 *  4. `translate.google.com/translate_a/single?client=gtx` — the m.google path, last resort.
 *
 * Failure policy, learned from the field:
 *  - a host that fails *transport-level* is remembered and skipped for the rest of the run
 *    ([deadHosts]) instead of being retried per page;
 *  - a 429 parks the host for a short cooldown ([HostHealth]) instead of hammering it;
 *  - a line that already came back translated is never asked again (the caller's persistent
 *    cache makes that true across runs).
 *
 * No key, no account, no billing: every URL here is a public web endpoint that a browser calls.
 */
class KeylessTranslateTranslator(
    private val http: Http,
    private val maxConcurrent: Int = 3,
    private val lingvaInstances: List<String> = LINGVA_INSTANCES,
) : Translator {

    override val id = EngineCodes.KEYLESS
    override val displayName = "Free engines (no key)"
    override val requiresNetwork = true

    private val gate = Semaphore(maxConcurrent)

    override fun codeFor(language: Language): String? = when (language.code) {
        "ckb" -> "ckb"
        "ku" -> "ku"
        "zh-Hans" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        else -> language.code
    }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val source = Languages.of(sourceLanguage)
        val target = Languages.of(targetLanguage)
        val sl = codeFor(source) ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        val tl = codeFor(target) ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))

        val out = texts.toMutableList()
        val failures = mutableListOf<String>()
        var dead = emptyList<String>()

        // One host per line at first, in rotation, so a single rate-limited host does not stall
        // the page; the whole page then retries through the remaining hosts if it failed.
        kotlinx.coroutines.coroutineScope {
            val jobs = texts.mapIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@mapIndexed null
                kotlinx.coroutines.launch {
                    gate.withPermit {
                        val attempt = tryRotation(line, sl, tl)
                        if (attempt.text != null) {
                            out[index] = attempt.text
                        } else {
                            synchronized(failures) { failures += attempt.reason ?: "no answer" }
                        }
                        if (attempt.dead.isNotEmpty()) dead = attempt.dead
                    }
                }
            }
            jobs.forEach { it?.join() }
        }

        if (out.indices.all { out[it] == texts[it] } && texts.any { it.isNotBlank() }) {
            throw TranslationException(
                "Every free endpoint failed (${failures.firstOrNull() ?: "no answer"}). " +
                    "This usually means the phone's network is blocking them, or all mirrors " +
                    "are rate-limited right now — wait a minute and retry, or add a key in " +
                    "Settings for an engine that cannot be rate-limited.",
            )
        }
        return TranslationBatch(out, id, unreachable = dead)
    }

    private data class Attempt(val text: String?, val reason: String? = null, val dead: List<String> = emptyList())

    /**
     * Try every endpoint in order for one line. A host that answers sets its health back to
     * green; a 429 parks it; a DNS/connect failure marks it dead for this run.
     */
    private suspend fun tryRotation(line: String, sl: String, tl: String): Attempt {
        val dead = mutableListOf<String>()
        var lastReason: String? = null
        for (route in routes(sl, tl)) {
            if (!HostHealth.isUsable(route.host) || route.host in deadHosts) {
                dead += route.host
                continue
            }
            val result = runCatching { gate.withPermit { requestWithRetry(route, line) } }
            val value = result.getOrNull()
            if (!value.isNullOrBlank()) {
                HostHealth.succeeded(route.host)
                return Attempt(value, dead = dead)
            }
            val error = result.exceptionOrNull()
            when {
                error == null -> lastReason = "${route.name} returned no text"
                isRateLimit(error) -> {
                    HostHealth.rateLimited(route.host)
                    lastReason = "${route.name}: rate-limited"
                }
                isTransport(error) -> {
                    deadHosts += route.host
                    dead += route.host
                    lastReason = "${route.name} is unreachable"
                }
                else -> lastReason = "${route.name}: ${error.message?.take(80)}"
            }
        }
        return Attempt(null, lastReason, dead)
    }

    private suspend fun requestWithRetry(route: Route, line: String): String? {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                return fetch(route, line)
            } catch (t: Throwable) {
                last = t
                if (!isRateLimit(t) && !isTransport(t)) throw t
                if (attempt == 1) throw t
                delay(600L)
            }
        }
        throw last ?: TranslationException("${route.name} did not answer")
    }

    private suspend fun fetch(route: Route, line: String): String? {
        val encoded = java.net.URLEncoder.encode(line, "UTF-8")
        val body = http.get(route.url(encoded), headers = route.headers)
        // A captcha or consent page is not a translation: fail this route, not the page.
        if (KeylessParsers.looksLikeHtml(body)) {
            throw TranslationException("${route.name} answered with a web page instead of JSON")
        }
        return route.parser(body, line)
    }

    /** One endpoint: how to build its URL, what headers it wants, and how to read its answer. */
    private data class Route(
        val name: String,
        val host: String,
        val build: (String) -> String,
        val headers: Map<String, String>,
        val parser: (String, String) -> String?,
    ) {
        fun url(encoded: String): String = build(encoded)
    }

    private fun routes(sl: String, tl: String): List<Route> {
        const val ua =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        return buildList {
            add(
                Route(
                    name = "Google web",
                    host = "translate.googleapis.com",
                    build = { q ->
                        "https://translate.googleapis.com/translate_a/single?client=gtx" +
                            "&sl=$sl&tl=$tl&dt=t&ie=UTF-8&oe=UTF-8&q=$q"
                    },
                    headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                    parser = { body, _ -> GoogleResponseParser.parse(body) },
                )
            )
            add(
                Route(
                    name = "Google dictionary",
                    host = "clients5.google.com",
                    build = { q ->
                        "https://clients5.google.com/translate_a/t?client=dict-chrome-ex" +
                            "&sl=$sl&tl=$tl&ie=UTF-8&oe=UTF-8&q=$q"
                    },
                    headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                    parser = { body, source ->
                        KeylessParsers.parseClients5(body, source)
                    },
                )
            )
            // Lingva: one route per public instance, because each instance is its own server and
            // therefore its own rate limit. A dead instance is skipped for the rest of the run.
            lingvaInstances.forEachIndexed { index, base ->
                val host = runCatching { java.net.URI(base).host }.getOrNull() ?: base
                add(
                    Route(
                        name = "Lingva ${index + 1}",
                        host = host,
                        build = { q ->
                            // The API takes the raw query as the last path segment; the caller
                            // already URL-encoded it, which is what the instance expects.
                            "${base.trimEnd('/')}/api/v1/$sl/$tl/$q"
                        },
                        headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                        parser = { body, _ -> KeylessParsers.parseLingva(body) },
                    )
                )
            }
            add(
                Route(
                    name = "Google mobile",
                    host = "translate.google.com",
                    build = { q ->
                        "https://translate.google.com/translate_a/single?client=gtx" +
                            "&sl=$sl&tl=$tl&dt=t&ie=UTF-8&oe=UTF-8&q=$q"
                    },
                    headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                    parser = { body, _ -> GoogleResponseParser.parse(body) },
                )
            )
        }
    }

    private fun isRateLimit(error: Throwable): Boolean {
        val m = error.message.orEmpty()
        return m.contains("429") || m.contains("403") || m.contains("too many", ignoreCase = true)
    }

    private fun isTransport(error: Throwable): Boolean =
        error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            (error is TranslationException && error.message.orEmpty().contains("HTTP 5"))

    companion object {
        /**
         * Public Lingva instances (from the project's own `instances.json`). They come and go;
         * the list is a rotation, so one being down costs one skipped host, not a failed page.
         */
        val LINGVA_INSTANCES: List<String> = listOf(
            "https://lingva.ml",
            "https://translate.plausibility.cloud",
            "https://lingva.lunar.icu",
            "https://translate.projectsegfau.lt",
        )

        /** Hosts that failed at the transport level during this run. */
        private val deadHosts: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        fun resetHosts() = deadHosts.clear()
    }
}

/**
 * Short-lived per-host health, so a rate limit parks a host for a minute instead of burning the
 * page's whole retry budget against it.
 */
object HostHealth {
    private const val COOLDOWN_MS = 60_000L
    private val parkedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isUsable(host: String): Boolean {
        val until = parkedUntil[host] ?: return true
        if (System.currentTimeMillis() >= until) {
            parkedUntil.remove(host)
            return true
        }
        return false
    }

    fun rateLimited(host: String) {
        parkedUntil[host] = System.currentTimeMillis() + COOLDOWN_MS
    }

    fun succeeded(host: String) {
        parkedUntil.remove(host)
    }

    fun reset() = parkedUntil.clear()
}

/** Response shapes of the keyless endpoints. Pure functions, unit-tested. */
object KeylessParsers {

    /**
     * `clients5.google.com/translate_a/t` answers with one of two shapes depending on the
     * client value: `{"sentences":[{"trans":"…"}]}` or `[["translated","de"]]`.
     */
    fun parseClients5(body: String, source: String = ""): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            val json = runCatching { org.json.JSONObject(trimmed) }.getOrNull() ?: return null
            val sentences = json.optJSONArray("sentences") ?: return null
            val joined = buildString {
                for (i in 0 until sentences.length()) {
                    append(sentences.optJSONObject(i)?.optString("trans").orEmpty())
                }
            }
            return joined.takeIf { it.isNotBlank() }
        }
        if (trimmed.startsWith("[")) {
            val array = runCatching { org.json.JSONArray(trimmed) }.getOrNull() ?: return null
            // [["translated","de"]] — one pair per input line.
            val first = array.optJSONArray(0)
            if (first != null && first.opt(1) is String) {
                return first.optString(0).takeIf { it.isNotBlank() }
            }
            // [["a"],["b"]] — a list of one-element arrays.
            val joined = buildString {
                for (i in 0 until array.length()) {
                    val part = array.opt(i)
                    when (part) {
                        is org.json.JSONArray -> append(part.optString(0))
                        is String -> append(part)
                    }
                }
            }
            return joined.takeIf { it.isNotBlank() }
        }
        return null
    }

    /** Lingva: `{"translation":"…","info":{…}}`. */
    fun parseLingva(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val json = runCatching { org.json.JSONObject(trimmed) }.getOrNull() ?: return null
        return json.optString("translation").takeIf { it.isNotBlank() }
    }

    /** Some mirrors answer with HTML (a captcha or a consent page): never treat that as text. */
    fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(200).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.contains("<body")
    }
}
