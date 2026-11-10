package app.mangascribe.translate

import app.mangascribe.core.Language
import app.mangascribe.core.Languages
import org.json.JSONObject

/**
 * A termbase: your own exact phrases and character names, which always win over any engine.
 *
 * Why it exists: every free engine translates short exclamations badly and inconsistently
 * ("What?!" comes back as "چی؟" on one page and "چیە؟" on the next), and names drift between
 * spellings across a chapter. A glossary fixes both for free: a hit costs zero requests, is
 * stable for the whole book, and cannot be rate-limited.
 *
 * Format is the one the Kurd-man pipeline used, so a glossary can move between the two:
 *
 * ```json
 * { "phrases": { "Who are you?!": "تۆ کێیت؟!" } }
 * ```
 *
 * Matching is case-insensitive for Latin sources, whitespace-normalised, and tried in this
 * order for each line: whole line, then the longest prefix that matches (so `Who are you?!`
 * still wins inside `Who are you?! Come out.`).
 */
class Glossary private constructor(private val phrases: Map<String, String>) {

    val size: Int get() = phrases.size
    val isEmpty: Boolean get() = phrases.isEmpty()

    fun translationFor(text: String): String? {
        if (phrases.isEmpty()) return null
        val key = normalise(text)
        if (key.isEmpty()) return null
        phrases[key]?.let { return it }
        // Longest matching prefix: covers "Who are you?! Come out." and speech followed by SFX.
        var best: String? = null
        var bestLength = 0
        for ((source, target) in phrases) {
            if (source.length <= bestLength || source.length >= key.length) continue
            if (key.startsWith(source) && key[source.length].isBoundary()) {
                best = target + key.substring(source.length).trimEnd()
                bestLength = source.length
            }
        }
        return best
    }

    private fun Char.isBoundary(): Boolean = isWhitespace() || this in ".,!?؟!…\"'«»"

    companion object {
        val EMPTY = Glossary(emptyMap())

        private fun normalise(text: String): String =
            text.lowercase().replace(Regex("\\s+"), " ").trim()

        fun parse(json: String?): Glossary {
            if (json.isNullOrBlank()) return EMPTY
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return EMPTY
            val phrases = root.optJSONObject("phrases") ?: root
            val out = HashMap<String, String>()
            for (key in phrases.keys()) {
                val target = phrases.optString(key).trim()
                val source = normalise(key)
                // A blank target would silently delete dialogue; refuse it instead.
                if (source.isNotEmpty() && target.isNotEmpty()) out[source] = target
            }
            return if (out.isEmpty()) EMPTY else Glossary(out)
        }

        /** The lexicon is per target language: Kurdish phrases are not English ones. */
        fun builtIn(targetLanguage: String): Glossary {
            val code = Languages.of(targetLanguage).code
            return when (code) {
                "ckb", "ku" -> KURDISH
                else -> EMPTY
            }
        }

        /**
         * Sorani for the lines that appear in almost every chapter, written as a Kurmanji-
         * free Sorani speaker would write them. Kept short on purpose: a wrong glossary entry
         * is worse than no entry, because it overrides a correct translation.
         */
        private val KURDISH = Glossary(
            mapOf(
                "what?!" to "چی؟!",
                "what?" to "چی؟",
                "what!" to "چی!",
                "who are you?!" to "تۆ کێیت؟!",
                "who are you?" to "تۆ کێیت؟",
                "where am i?" to "من لە کوێم؟",
                "what is this?" to "ئەمە چییە؟",
                "what's this?" to "ئەمە چییە؟",
                "why?!" to "بۆچی؟!",
                "why?" to "بۆچی؟",
                "how?!" to "چۆن؟!",
                "how?" to "چۆن؟",
                "no!" to "نا!",
                "no way..." to "مەحاڵە...",
                "no way!" to "مەحاڵە!",
                "impossible!" to "مەحاڵە!",
                "run!" to "ڕابە!",
                "stop!" to "بوەستە!",
                "wait!" to "چاوەڕێ بکە!",
                "help!" to "یارمەتی!",
                "help me!" to "یارمەتیم بدە!",
                "thank you." to "سوپاس.",
                "thank you!" to "سوپاس!",
                "sorry." to "ببوورە.",
                "forgive me." to "لێم ببوورە.",
                "i'm sorry." to "ببوورە.",
                "let's go." to "با بڕۆین.",
                "let's go!" to "با بڕۆین!",
                "goodbye." to "خوا حافیز.",
                "it hurts!" to "ئازارم دەدات!",
                "i will protect you!" to "من تۆ دەپارێزم!",
                "do not touch my sister." to "دەست لە خوشکەکەم مەدە.",
                "i finally returned after ten years." to "دواجار دوای دە ساڵ گەڕامەوە.",
                "this sword will decide everything." to "ئەم شمشێرە هەموو شتێک یەکلایی دەکاتەوە.",
                "i will never forgive you!" to "هەرگیز لێت خۆش نابم!",
                "leave me alone!" to "بەجێم بهێڵە!",
                "don't come any closer!" to "نزیکتر مەوە!",
            )
        )
    }
}

/**
 * Applies a glossary to a finished batch: a known line replaces whatever the engine said,
 * everything else is left exactly as translated. Done as an overlay rather than as a wrapper
 * around the engine so the engine still sees the whole page — batching, retries and the
 * chapter-level request count stay untouched.
 */
fun Glossary.overlay(source: List<String>, translated: List<String>): List<String> =
    source.mapIndexed { index, text ->
        translationFor(text) ?: translated.getOrNull(index) ?: text
    }
