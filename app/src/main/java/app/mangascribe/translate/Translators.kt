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
 * MyMemory — a free, key-less public memory: good for a first run and for spot checks.
 * It is rate limited, so the engine sends one line per request and never pretends to be a
 * bulk cloud model.
 */
class MyMemoryTranslator(
    private val http: Http,
) : Translator {
    override val id = "mymemory"
    override val displayName = "MyMemory (free, no key)"
    override val requiresNetwork = true

    /**
     * MyMemory tags Kurdish inconsistently across its own memory: `ckb` is the correct
     * ISO-639-3 code, `ku-Arab` is what CLDR uses for Sorani, and a lot of its stored
     * translations sit under the bare `ku`. Trying them in order costs one request only when
     * the previous tag is refused, and the third one is what actually returns Sorani text.
     */
    private fun candidateCodes(language: Language): List<String> {
        val base = codeFor(language) ?: return emptyList()
        return when (language.code) {
            "ckb" -> listOf(base, "ku-Arab", "ku")
            "zh-Hans" -> listOf("zh-CN", "zh")
            "zh-Hant" -> listOf("zh-TW", "zh")
            else -> listOf(base.substringBefore('-'))
        }
    }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val srcCodes = candidateCodes(Languages.of(sourceLanguage))
        val tgtCodes = candidateCodes(Languages.of(targetLanguage))
        if (srcCodes.isEmpty() || tgtCodes.isEmpty()) {
            throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        }
        var lastError: Throwable? = null
        for (src in srcCodes) {
            for (tgt in tgtCodes) {
                try {
                    val results = texts.map { line ->
                        if (line.isBlank()) {
                            line
                        } else {
                            val url = "https://api.mymemory.translated.net/get?q=" +
                                java.net.URLEncoder.encode(line.take(480), "UTF-8") +
                                "&langpair=$src|$tgt"
                            val body = http.get(url)
                            MyMemoryParser.parse(body)
                                ?: throw TranslationException("MyMemory returned no text")
                        }
                    }
                    return TranslationBatch(results, id)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
        }
        throw TranslationException(
            "MyMemory could not translate this pair: ${lastError?.message ?: "no route"}",
            lastError,
        )
    }
}

object MyMemoryParser {
    fun parse(json: String): String? = runCatching {
        val root = JSONObject(json)
        val responseData = root.optJSONObject("responseData")
        val translated = responseData?.optString("translatedText").orEmpty()
        if (translated.isBlank()) return@runCatching null
        // MyMemory returns an English apology inside translatedText on quota errors
        if (translated.startsWith("PLEASE SELECT", ignoreCase = true) ||
            translated.startsWith("QUERY LENGTH LIMIT", ignoreCase = true) ||
            translated.startsWith("MYMEMORY WARNING", ignoreCase = true)
        ) {
            return@runCatching null
        }
        unescape(translated)
    }.getOrNull()

    private fun unescape(s: String): String =
        s.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/**
 * LibreTranslate — self-hostable, key-less when the instance allows it. The app ships a
 * configurable endpoint so a user with their own server gets bulk translation for free.
 */
class LibreTranslateTranslator(
    private val http: Http,
    private val endpoint: String,
    private val apiKey: String?,
) : Translator {
    override val id = "libretranslate"
    override val displayName = "LibreTranslate"
    override val requiresNetwork = true

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val srcCode = codeFor(Languages.of(sourceLanguage))
            ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        val tgtCode = codeFor(Languages.of(targetLanguage))
            ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        val batches = BatchPlanner.plan(texts)
        val out = texts.toMutableList()
        for (batch in batches) {
            val payload = JSONObject().apply {
                put("q", JSONArray(batch.map { texts[it] }))
                put("source", srcCode.substringBefore('-'))
                put("target", tgtCode.substringBefore('-'))
                put("format", "text")
                if (!apiKey.isNullOrBlank()) put("api_key", apiKey)
            }
            val body = http.postJson(endpoint.trimEnd('/') + "/translate", payload.toString())
            val parsed = LibreTranslateParser.parse(body)
                ?: throw TranslationException("LibreTranslate returned no text")
            BatchPlanner.realign(texts, batch, parsed).forEachIndexed { i, v -> out[i] = v }
        }
        return TranslationBatch(out, id)
    }
}

object LibreTranslateParser {
    fun parse(json: String): List<String>? = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("translatedText") ?: return@runCatching null
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrNull()
}

/**
 * OpenAI-compatible chat endpoint (OpenAI, Groq, OpenRouter, a local llama.cpp server).
 * The prompt is the important part: one numbered line per balloon, instructions to keep the
 * count and never merge two balloons — the parser refuses a mismatched reply rather than
 * silently shifting every bubble on the page by one.
 */
class OpenAiCompatibleTranslator(
    private val http: Http,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : Translator {
    override val id = "openai-compatible"
    override val displayName = "OpenAI-compatible model"
    override val requiresNetwork = true

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val src = Languages.of(sourceLanguage)
        val tgt = Languages.of(targetLanguage)
        codeFor(src) ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        codeFor(tgt) ?: throw UnsupportedPairException(sourceLanguage, targetLanguage, listOf(id))
        val batches = BatchPlanner.plan(texts)
        val out = texts.toMutableList()
        for (batch in batches) {
            val request = ChatPrompt.build(
                source = ChatPrompt.languageInstruction(src),
                target = ChatPrompt.languageInstruction(tgt),
                lines = batch.map { texts[it] },
            )
            val payload = JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", ChatPrompt.SYSTEM))
                    put(JSONObject().put("role", "user").put("content", request))
                })
            }
            val body = http.postJson(
                baseUrl.trimEnd('/') + "/chat/completions",
                payload.toString(),
                mapOf("Authorization" to "Bearer $apiKey"),
            )
            val content = ChatPrompt.extractContent(body)
                ?: throw TranslationException("The model returned no text")
            val parsed = ChatPrompt.parseNumbered(content, batch.size)
                ?: throw TranslationException("The model returned the wrong number of lines")
            BatchPlanner.realign(texts, batch, parsed).forEachIndexed { i, v -> out[i] = v }
        }
        return TranslationBatch(out, id)
    }
}

object ChatPrompt {
    const val SYSTEM =
        "You translate comic and manga dialogue. You keep each numbered balloon separate, " +
            "translate meaning and tone rather than words, never add narration, and reply with " +
            "numbered lines only, exactly as many as you were given."

    /**
     * How to name a language to the model. Kurdish is the one that needs saying properly:
     * a model told only "Kurdish" will answer in Kurmanji (Latin script), which is the wrong
     * language for these readers, so the dialect and the letter set are spelled out.
     */
    fun languageInstruction(language: Language): String = when (language.script) {
        app.mangascribe.core.Script.KURDISH ->
            "Central Kurdish (Sorani, کوردیی سۆرانی) written in Arabic script with the Kurdish " +
                "letters پ ژ گ چ ڤ ۆ ێ ڕ ڵ ە — never Arabic ك or ي, never Kurmanji in Latin letters"
        app.mangascribe.core.Script.JAPANESE -> "Japanese"
        else -> "${language.displayName} (${language.nativeName})"
    }

    fun build(source: String, target: String, lines: List<String>): String = buildString {
        append("Translate from ").append(source).append(" to ").append(target).append(".\n")
        append("Reply with the same numbers, one translation per line, nothing else.\n\n")
        lines.forEachIndexed { i, line ->
            append(i + 1).append(". ").append(line.replace("\n", " ")).append('\n')
        }
    }

    /** "1. text" / "1) text" / "1: text" — tolerant of the wrappers models add. */
    fun parseNumbered(reply: String, expected: Int): List<String>? {
        val found = LinkedHashMap<Int, String>()
        val regex = Regex("^\\s*(\\d{1,3})\\s*[.):\\-]\\s*(.+)$")
        for (raw in reply.lines()) {
            val m = regex.find(raw) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..expected) found[n] = m.groupValues[2].trim().trim('"')
        }
        if (found.size < expected) return null
        return (1..expected).map { found[it] ?: return null }
    }

    fun extractContent(json: String): String? = runCatching {
        val root = JSONObject(json)
        root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * Which configured engine can do which pair, without building engines or touching the
 * network — the settings screen needs the same answer the pipeline will use, and the two
 * must never disagree about Kurdish.
 */
object EngineCatalog {
    data class Entry(val id: String, val label: String, val note: String)

    val all: List<Entry> = listOf(
        Entry(
            EngineCodes.GOOGLE,
            "Google Translate",
            "Free, no key, and the best quality of the three. The only free route into Kurdish Sorani.",
        ),
        Entry(
            EngineCodes.MYMEMORY,
            "MyMemory",
            "Free, no key. Translates into Kurdish sometimes — it is a crowd memory, not a model.",
        ),
        Entry(
            EngineCodes.LIBRETRANSLATE,
            "LibreTranslate",
            "Free on your own server. No Kurdish in any published model set.",
        ),
        Entry(
            EngineCodes.LLM,
            "Model endpoint",
            "OpenAI-compatible. Best for tone and names in long dialogue; needs a key.",
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

    fun kurdishAdvice(targetLanguage: String, hasLlmKey: Boolean): String? {
        if (!EngineCodes.isSorani(targetLanguage)) return null
        return if (hasLlmKey) {
            "Kurdish Sorani is translated by Google Translate, with your model endpoint as the " +
                "second choice for tone."
        } else {
            "Kurdish Sorani works out of the box through Google Translate — no key needed. " +
                "Add a model key only if you want better tone in long dialogue. (LibreTranslate " +
                "has no Kurdish.)"
        }
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
    ): TranslationBatch {
        val ordered = engines.sortedBy { if (it.id == preferred) 0 else 1 }
        val capable = ordered.filter { it.supportsPair(sourceLanguage, targetLanguage) }
        val skipped = ordered.filterNot { it.supportsPair(sourceLanguage, targetLanguage) }
        if (capable.isEmpty()) {
            throw UnsupportedPairException(
                sourceLanguage,
                targetLanguage,
                skipped.map { it.displayName },
            )
        }
        var lastError: Throwable? = null
        val tried = mutableListOf<String>()
        for (engine in capable) {
            tried += engine.displayName
            try {
                val result = engine.translate(texts, sourceLanguage, targetLanguage)
                if (result.lines.any { it.isNotBlank() }) {
                    return result.copy(detectedSource = Languages.detect(texts.joinToString(" ")))
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        val why = lastError?.message?.takeIf { it.isNotBlank() }
            ?: UnsupportedPairException(sourceLanguage, targetLanguage, tried).message.orEmpty()
        throw TranslationException(why, lastError)
    }

    fun engineNames(): List<String> = engines.map { it.displayName }

    /** What the UI shows next to an engine's name for this pair. */
    fun coverage(sourceLanguage: String, targetLanguage: String): List<Pair<String, Boolean>> =
        engines.map { it.displayName to it.supportsPair(sourceLanguage, targetLanguage) }
}
