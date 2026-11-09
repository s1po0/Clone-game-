package app.mangascribe.translate

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
    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch
}

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

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        val src = Languages.of(sourceLanguage).code.substringBefore('-')
        val tgt = Languages.of(targetLanguage).code.substringBefore('-')
        val results = texts.map { line ->
            if (line.isBlank()) {
                line
            } else {
                val url = "https://api.mymemory.translated.net/get?q=" +
                    java.net.URLEncoder.encode(line.take(480), "UTF-8") +
                    "&langpair=$src|$tgt"
                val body = http.get(url)
                MyMemoryParser.parse(body) ?: throw TranslationException("MyMemory returned no text")
            }
        }
        return TranslationBatch(results, id)
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
        val batches = BatchPlanner.plan(texts)
        val out = texts.toMutableList()
        for (batch in batches) {
            val payload = JSONObject().apply {
                put("q", JSONArray(batch.map { texts[it] }))
                put("source", Languages.of(sourceLanguage).code.substringBefore('-'))
                put("target", Languages.of(targetLanguage).code.substringBefore('-'))
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
        val batches = BatchPlanner.plan(texts)
        val out = texts.toMutableList()
        val srcName = Languages.of(sourceLanguage).displayName
        val tgtName = Languages.of(targetLanguage).displayName
        for (batch in batches) {
            val request = ChatPrompt.build(srcName, tgtName, batch.map { texts[it] })
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
        var lastError: Throwable? = null
        for (engine in ordered) {
            try {
                val result = engine.translate(texts, sourceLanguage, targetLanguage)
                if (result.lines.any { it.isNotBlank() }) {
                    return result.copy(detectedSource = Languages.detect(texts.joinToString(" ")))
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw TranslationException(
            lastError?.message ?: "No translation engine is available",
            lastError,
        )
    }

    fun engineNames(): List<String> = engines.map { it.displayName }
}
