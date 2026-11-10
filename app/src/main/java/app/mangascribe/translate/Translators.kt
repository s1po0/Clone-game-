package app.mangascribe.translate

import app.mangascribe.core.EngineCodes
import app.mangascribe.core.Language
import app.mangascribe.core.Languages
import org.json.JSONArray
import org.json.JSONObject

/**
 * One translator surface. Every engine returns the translation plus the runtime that made it,
 * so a page can say where its text came from (and so a cloud failure can fall back cleanly).
 */
interface Translator {
    val id: String
    val displayName: String
    /** True when this engine needs a key, a network, or both. */
    val requiresNetwork: Boolean

    /**
     * The code this engine wants for a language, or null when it has none. Kurdish is the
     * reason this exists: `ckb` is understood by two of the three engines and rejected by
     * the third, and a rejected code must become a clear message, never a silent fallback
     * that hands the reader Turkish and calls it Kurdish.
     */
    fun codeFor(language: Language): String? = language.codeFor(id)?.takeIf { it != EngineCodes.NONE }

    fun supports(languageCode: String): Boolean = codeFor(Languages.of(languageCode)) != null

    fun supportsPair(sourceLanguage: String, targetLanguage: String): Boolean =
        supports(sourceLanguage) && supports(targetLanguage)

    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch
}

/** Raised when no configured engine can reach the requested pair. */
class UnsupportedPairException(
    val sourceLanguage: String,
    val targetLanguage: String,
    val tried: List<String>,
) : Exception(
    buildString {
        append("No translator here can do ")
        append(Languages.of(sourceLanguage).displayName).append(" → ")
        append(Languages.of(targetLanguage).displayName).append('.')
        if (tried.isNotEmpty()) append(" Tried: ").append(tried.joinToString(", ")).append('.')
        if (EngineCodes.isSorani(targetLanguage)) {
            append(" Switch to Google Translate in Settings → Translation engine: it is free " +
                "and has Kurdish Sorani. LibreTranslate has no Kurdish at all.")
        }
    }
)

data class TranslationBatch(
    val lines: List<String>,
    val engineId: String,
    val detectedSource: String? = null,
    /** Engines that failed at the transport level on the way to [engineId]. */
    val unreachable: List<String> = emptyList(),
)

class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Splits a chapter into batches that fit a request, keeping balloon order stable so results
 * map back onto blocks one-to-one. Pure logic, unit-tested.
 */
object BatchPlanner {
    const val MAX_CHARS_PER_REQUEST = 4200
    const val MAX_LINES_PER_REQUEST = 48
    /** Some engines charge the same for an empty string; drop them and re-align after. */
    fun plan(texts: List<String>): List<List<Int>> {
        val batches = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0
        texts.forEachIndexed { index, text ->
            val cost = text.length.coerceAtLeast(1)
            if (current.isNotEmpty() &&
                (chars + cost > MAX_CHARS_PER_REQUEST || current.size >= MAX_LINES_PER_REQUEST)
            ) {
                batches += current
                current = mutableListOf()
                chars = 0
            }
            current += index
            chars += cost
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** Re-expand a batch's results to the original line count, filling gaps with the source. */
    fun realign(source: List<String>, indices: List<Int>, translated: List<String>): List<String> {
        val out = source.toMutableList()
        indices.forEachIndexed { i, original ->
            val value = translated.getOrNull(i)?.takeIf { it.isNotBlank() }
            if (value != null && original in out.indices) out[original] = value
        }
        return out
    }
}

/**
 * Which configured engine can do which pair, without building engines or touching the
 * network — the settings screen needs the same answer the pipeline will use, and the two
 * must never disagree about Kurdish.
 */
object EngineCatalog {
    data class Entry(val id: String, val label: String, val note: String)

    /**
     * **One entry.** Google is the only free route that answers Kurdish Sorani. Microsoft
     * refuses `ckb` with HTTP 400, MyMemory has no Sorani, every public Lingva mirror rejects
     * the language code, and no published LibreTranslate model set contains Kurdish. Listing
     * them anyway only invited the reader to pick a route that cannot work, and then to
     * believe the app was broken when it filled a bubble with English.
     */
    val all: List<Entry> = listOf(
        Entry(
            EngineCodes.KEYLESS,
            "Google Translate",
            "Free, no key. Two Google hosts, tried in turn: a rate limit delays the page " +
                "instead of failing it.",
        ),
    )

    fun supports(engineId: String, sourceLanguage: String, targetLanguage: String): Boolean {
        val src = Languages.of(sourceLanguage)
        val dst = Languages.of(targetLanguage)
        fun ok(language: app.mangascribe.core.Language) =
            language.codeFor(engineId) != null &&
                language.codeFor(engineId) != EngineCodes.NONE
        return ok(src) && ok(dst)
    }

    /** Engines that can reach the pair, best first, with the preferred one leading. */
    fun capable(preferred: String, sourceLanguage: String, targetLanguage: String): List<Entry> =
        all.filter { supports(it.id, sourceLanguage, targetLanguage) }
            .sortedBy { if (it.id == preferred) 0 else 1 }

    fun kurdishAdvice(targetLanguage: String): String? {
        if (!EngineCodes.isSorani(targetLanguage)) return null
        return "Kurdish Sorani goes through Google Translate — free, no key, nothing to set up. " +
            "The app tries two Google hosts in turn, so a rate limit slows a page down " +
            "instead of failing it."
    }
}

/**
 * The engine the user picked, plus the fallback rule: a cloud engine that fails falls back to
 * whatever else is configured instead of leaving the page untranslated.
 */
class TranslationPipeline(
    private val engines: List<Translator>,
    private val preferred: String,
) {
    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch = translate(texts, sourceLanguage, targetLanguage, emptyList())

    /**
     * [without] names engines that must not be tried: a dead host that already failed once in
     * this batch is skipped instead of costing a DNS timeout per page. The caller decides the
     * scope (one page, one chapter); the pipeline stays stateless.
     */
    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        without: Collection<String>,
    ): TranslationBatch {
        val ordered = engines.sortedBy { if (it.id == preferred) 0 else 1 }
            .filterNot { it.id in without }
        // A pair with identical ends is not a translation request. Every caller means "detect
        // it and give me the target", which is what `auto` is for; passing ckb→ckb to a free
        // endpoint returns the input unchanged and looks like a failed page.
        val source = if (sourceLanguage == targetLanguage) "auto" else sourceLanguage
        val capable = ordered.filter { it.supportsPair(source, targetLanguage) }
        val skipped = ordered.filterNot { it.supportsPair(source, targetLanguage) }
        if (capable.isEmpty()) {
            throw UnsupportedPairException(
                sourceLanguage,
                targetLanguage,
                skipped.map { it.displayName },
            )
        }
        var lastError: Throwable? = null
        val tried = mutableListOf<String>()
        val unreachable = mutableListOf<String>()
        val targetIsRtl = Languages.of(targetLanguage).rtl
        var best: TranslationBatch? = null
        for (engine in capable) {
            tried += engine.displayName
            try {
                val result = engine.translate(texts, source, targetLanguage)
                // An engine that echoes the source ("Worthless." came back as "WORTHLESS.") has
                // not translated: try the next engine before accepting it. The renderer also
                // refuses to draw such a line, but a retry is where a real translation can
                // still come from.
                // For an RTL target the bar is absolute: an engine that leaves any Latin letter
                // in the answer has not finished the job, and the next engine is asked before the
                // result is accepted. The old share-based test let "one English word in a Kurdish
                // line" through, which is precisely what the reader kept seeing.
                val unfinishedForRtl = targetIsRtl && result.lines.any { line ->
                    app.mangascribe.core.KurdishTransliteration.hasLatinLetters(line)
                }
                if (unfinishedForRtl || app.mangascribe.core.TranslatedTextCleanup.hasLatinResidue(
                        result.lines.joinToString(" "), targetIsRtl,
                    )
                ) {
                    best = best ?: result
                    lastError = TranslationException(
                        "${engine.displayName} returned the source text unchanged",
                    )
                    continue
                }
                if (result.lines.any { it.isNotBlank() }) {
                    return result.copy(
                        detectedSource = Languages.detect(texts.joinToString(" ")),
                        unreachable = unreachable,
                    )
                }
            } catch (t: Throwable) {
                lastError = t
                if (isTransportFailure(t)) unreachable += engine.id
            }
        }
        // Every engine echoed the source: give the renderer what came back (it will keep the
        // original lettering visible) rather than failing the whole page.
        best?.let {
            if (it.lines.any { line -> line.isNotBlank() }) {
                return it.copy(
                    detectedSource = Languages.detect(texts.joinToString(" ")),
                    unreachable = unreachable,
                )
            }
        }
        val why = explain(lastError)
            ?: UnsupportedPairException(sourceLanguage, targetLanguage, tried).message.orEmpty()
        throw TranslationException(why, lastError)
    }

    /**
     * Turns the transport-level failures a phone actually produces into a sentence a reader can
     * act on. "Unable to resolve host translate.argosopentech.com" is accurate and useless: the
     * reader did not choose that host, and the fix is either a network or another engine.
     */
    private fun isTransportFailure(error: Throwable): Boolean =
        error is java.net.UnknownHostException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.ConnectException

    private fun explain(error: Throwable?): String? {
        val message = error?.message?.takeIf { it.isNotBlank() } ?: return null
        val dead = error is java.net.UnknownHostException ||
            message.contains("Unable to resolve host", ignoreCase = true)
        val offline = error is java.net.SocketTimeoutException ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("Failed to connect", ignoreCase = true)
        return when {
            dead -> "No connection to the translation server ($message). Check the phone's " +
                "internet, or pick a different engine in Settings."
            offline -> "The translation server did not answer in time. Check the connection and " +
                "try again — the page keeps its place."
            else -> message
        }
    }

    fun engineNames(): List<String> = engines.map { it.displayName }

    /** What the UI shows next to an engine's name for this pair. */
    fun coverage(sourceLanguage: String, targetLanguage: String): List<Pair<String, Boolean>> =
        engines.map { it.displayName to it.supportsPair(sourceLanguage, targetLanguage) }
}
