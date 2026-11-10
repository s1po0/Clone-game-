package app.mangascribe.translate

import app.mangascribe.core.TranslatedTextCleanup

/**
 * Translates a page's lines and does not accept half-finished work.
 *
 * The screenshot that started this: a status box came back as
 * `ئەسپێکت: temple slave` / `description: a slave is a useless wretch with no notable skills…`.
 * The engine had translated *part* of a line and left English inside it. Every engine in this
 * app can do that: Google splits a long line into segments and drops one, a mirror returns the
 * query plus a fragment, a model answers two of four sentences.
 *
 * So a line is only finished when it has no Latin words left that the source did not have. If it
 * does, the line is asked again — the whole line, on its own (so nothing else in the batch can
 * eat its budget), through whichever engine answered last and then the others in turn. Two
 * rounds. A line that still fails keeps its **source** text and is flagged, which the renderer
 * shows instead of a half-English bubble.
 */
class LineTranslator(
    private val engines: List<Translator>,
    private val preferred: String,
    private val onDiagnostic: (String) -> Unit = {},
) {

    data class Result(
        val lines: List<String>,
        val engineId: String,
        /** Lines that never came back fully in the target language. */
        val unfinished: List<Int>,
    )

    /**
     * @param source lines in the source language, in the order the caller wants them back.
     */
    suspend fun translate(
        source: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        without: Collection<String> = emptyList(),
    ): Result {
        if (source.isEmpty()) return Result(emptyList(), preferred, emptyList())
        val targetIsRtl = app.mangascribe.core.Languages.of(targetLanguage).rtl

        // 1. One batch, deduplicated: a repeated bubble costs one request.
        val unique = source.map { it.trim() }.distinct().filter { it.isNotEmpty() }
        val pipeline = TranslationPipeline(engines, preferred)
        val first = runCatching {
            pipeline.translate(unique, sourceLanguage, targetLanguage, without)
        }.getOrElse { throw it }

        val bySource = HashMap<String, String>(unique.size)
        unique.forEachIndexed { index, text ->
            bySource[text] = first.lines.getOrNull(index) ?: text
        }

        // 2. Anything still carrying source-language words gets a second, private attempt.
        val engineOrder = engines.sortedBy { if (it.id == preferred) 0 else 1 }.map { it.id }
        val stillBad = unique.filter { text ->
            TranslatedTextCleanup.hasLeftoverSource(bySource[text].orEmpty(), text, targetIsRtl)
        }
        val repaired = mutableMapOf<String, String>()
        var usedEngine = first.engineId
        for (text in stillBad) {
            val fixed = retryLine(text, sourceLanguage, targetLanguage, engineOrder)
            if (fixed != null) {
                repaired[text] = fixed.second
                usedEngine = fixed.first
            }
        }

        // 3. Whatever is still not in the target language gets written in Kurdish letters. The
        // instruction was explicit: if a word has no Kurdish meaning, make it Kurdish letters
        // rather than leave English in an RTL bubble. It is marked as transliteration, and the
        // renderer shows the ۞ so nobody reads a sound as a meaning.
        val lines = source.map { raw ->
            val key = raw.trim()
            val candidate = repaired[key] ?: bySource[key] ?: raw
            if (targetIsRtl &&
                TranslatedTextCleanup.hasLeftoverSource(candidate, raw, targetIsRtl = true)
            ) {
                app.mangascribe.core.KurdishTransliteration.rescueMixed(candidate)
            } else {
                candidate
            }
        }
        val unfinished = source.indices.filter { index ->
            TranslatedTextCleanup.hasLeftoverSource(lines[index], source[index], targetIsRtl)
        }.filterNot { index ->
            // A transliterated line is not "unfinished": it is in Kurdish letters, which is what
            // was asked for when no engine could do better.
            app.mangascribe.core.KurdishTransliteration.isTransliterated(lines[index])
        }
        if (unfinished.isNotEmpty()) {
            onDiagnostic(
                "${unfinished.size}/${source.size} lines still had source-language words after two " +
                    "attempts (engine ${first.engineId})",
            )
        }
        return Result(lines, usedEngine, unfinished)
    }

    /** Ask one line at a time: nothing else in the batch can be blamed for a partial answer. */
    private suspend fun retryLine(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        engineOrder: List<String>,
    ): Pair<String, String>? {
        // `auto` is the second attempt's whole point: when the declared source is wrong (OCR
        // said Kurdish on an English page), asking again with the same wrong source just gets the
        // same echo. The endpoint detects the language itself when told `auto`.
        val orders = listOf(sourceLanguage, "auto").distinct()
        for (round in orders.indices) {
            val source = orders[round]
            for (engine in engineOrder) {
                val candidate = engines.firstOrNull { it.id == engine } ?: continue
                if (!candidate.supportsPair(source, targetLanguage)) continue
                val attempt = runCatching {
                    candidate.translate(listOf(text), source, targetLanguage)
                }.getOrNull() ?: continue
                val line = attempt.lines.firstOrNull().orEmpty()
                if (line.isNotBlank() &&
                    !TranslatedTextCleanup.hasLeftoverSource(
                        line,
                        text,
                        app.mangascribe.core.Languages.of(targetLanguage).rtl,
                    )
                ) {
                    return attempt.engineId to line
                }
            }
            if (round == 0) kotlinx.coroutines.delay(350)
        }
        return null
    }
}
