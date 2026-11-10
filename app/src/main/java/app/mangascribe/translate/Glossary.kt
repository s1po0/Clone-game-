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
class Glossary private constructor(internal val phrases: Map<String, String>) {

    val size: Int get() = phrases.size
    val isEmpty: Boolean get() = phrases.isEmpty()

    /**
     * A second lookup table: the source language's own word for a term → the Kurdish
     * transliteration. English-keyed entries cannot match a Japanese or Korean raw, so the
     * names are also listed in the languages this app actually gets asked to read.
     */
    private val aliasIndex: Map<String, String> by lazy {
        buildMap {
            putAll(phrases)
            for ((alias, target) in ALIASES) put(alias, target)
        }.let { map ->
            // Longest first, so "shadow slave" wins over "slave" in a prefix match.
            map.entries.sortedByDescending { it.key.length }
                .associate { it.key to it.value }
        }
    }

    fun translationFor(text: String): String? {
        if (phrases.isEmpty()) return null
        val key = normalise(text)
        if (key.isEmpty()) return null
        aliasIndex[key]?.let { return it }
        rewriteWhole(key)?.let { return it }
        // Longest matching prefix: covers "Who are you?! Come out." and speech followed by SFX.
        var best: String? = null
        var bestLength = 0
        for ((source, target) in aliasIndex) {
            if (source.length <= bestLength || source.length >= key.length) continue
            if (key.startsWith(source) && key[source.length].isBoundary()) {
                best = target + key.substring(source.length).trimEnd()
                bestLength = source.length
            }
        }
        return best
    }

    /**
     * Replace names *inside* a longer line, leaving the rest for the engine.
     *
     * A status box reads "Aspect: Temple Slave" / "Description: a slave is a useless wretch…" —
     * a whole-line lookup can never match that, so the screenshot kept both labels in English.
     * Here every glossary term is substituted where it stands (longest first, so "temple slave"
     * wins over "slave"), and the line is marked so the caller knows it still needs translating.
     * Only terms of two or more characters are rewritten: a one-letter entry would hit inside
     * words.
     */
    fun rewriteTermsInside(text: String): TermRewrite {
        if (phrases.isEmpty()) return TermRewrite(text, emptyList())
        var working = text
        val replaced = mutableListOf<String>()
        for ((source, target) in aliasIndex) {
            if (source.length < 2) continue
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(source) + "(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            )
            if (pattern.containsMatchIn(working)) {
                working = pattern.replace(working) { target }
                replaced += source
            }
        }
        return TermRewrite(working, replaced)
    }

    /**
     * The whole line, assembled from glossary pieces: a label followed by a colon, then a
     * sentence. "aspect: temple slave" is two known terms and a colon, so it can be answered
     * without asking anyone; so can "description: a slave is a useless wretch with no notable
     * skills or abilities".
     */
    private fun rewriteWhole(key: String): String? {
        if (aliasIndex[key] != null) return aliasIndex[key]
        // Split on a colon: the common shape of a status window.
        val parts = key.split(':', limit = 2)
        if (parts.size == 2) {
            val left = aliasIndex[parts[0].trim()]
            val right = aliasIndex[parts[1].trim()]
            if (left != null && right != null) return "$left: $right"
        }
        // Comma-separated lists of known terms ("a slave is far rarer, but just as worthless").
        val chunks = key.split(',').map { it.trim() }
        if (chunks.size > 1) {
            val mapped = chunks.map { aliasIndex[it] }
            if (mapped.none { it == null }) return mapped.joinToString("، ")
        }
        return null
    }

    private fun Char.isBoundary(): Boolean = isWhitespace() || this in ".,!?؟!…\"'«»"

    /** The line after in-place term replacement, plus which terms were found. */
    data class TermRewrite(val text: String, val terms: List<String>) {
        val changed: Boolean get() = terms.isNotEmpty()
    }

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
                "ckb", "ku" -> merged
                else -> EMPTY
            }
        }

        /**
         * The same handful of names in the source languages, so a Japanese or Korean raw still
         * produces the Kurdish spelling the reader knows instead of a fresh transliteration.
         */
        private val ALIASES: Map<String, String> = mapOf(
            "影の奴隷" to "شادۆ سلەیڤ",
            "アスペクト" to "ئەسپێکت",
            "神殿の奴隷" to "کۆیلەی پەرستگا",
            "그림자 노예" to "شادۆ سلەیڤ",
            "속성" to "ئەسپێکت",
            "신전의 노예" to "کۆیلەی پەرستگا",
            "shadow slave" to "شادۆ سلەیڤ",
            "aspect: temple slave" to "ئەسپێکت: کۆیلەی پەرستگا",
        )

        /** Sentences and names together: a name is often a whole balloon ("ASPECT: TEMPLE SLAVE"). */
        private val merged: Glossary by lazy { Glossary(KURDISH.phrases + NAMES.phrases) }

        /**
         * Names and terms a translator mangles and a reader notices immediately.
         *
         * Every engine transliterates character names differently on every request — "Shadow
         * Slave" comes back as "شادۆ سلایڤ" on one page and "شادو سلاڤە" on the next, and a
         * title like "Aspect: Temple Slave" becomes a sentence instead of a system label. These
         * are *transliterations*, deliberately not translations: the reader is meant to
         * recognise the name from the raw, in Kurdish letters.
         */
        val NAMES: Glossary = Glossary(
            buildMap {
                // Titles and system labels
                put("shadow slave", "شادۆ سلەیڤ")
                put("temple slave", "کۆیلەی پەرستگا")
                put("aspect", "ئەسپێکت")
                put("aspects", "ئەسپێکتەکان")
                put("aspect description", "وەسفی ئەسپێکت")
                put("no notable skills or abilities", "هیچ شارەزایی یان توانایییەکی دیار نییە")
                // Recurring nouns in this genre, where a literal translation reads oddly
                put("spell", "سیحر")
                put("challenge", "تەحەدا")
                put("attribute", "تایبەتمەندی")
                put("rank", "پلە")
                put("status window", "پەنجەرەی دۆخ")
                put("level up", "ئاست بەرزبوونەوە")
                put("dungeon", "زیندان")
                put("gate", "دەروازە")
                put("guild", "گیلد")
                put("skill", "شارەزایی")
                put("stat", "ئامار")
                put("description", "وەسف")
                put("slave", "کۆیلە")
                put("wretch", "بێچارە")
                put("useless", "بێکەڵک")
                put("skills", "شارەزاییەکان")
                put("abilities", "تواناییەکان")
                put("notable", "دیار")
                put("far rarer", "زۆر کەمتر")
                put("worthless", "بێ نرخ")
                put("balance", "هاوسەنگی")
                put("value", "نرخ")
                put("system", "سیستەم")
                put("notification", "ئاگادارکردنەوە")
                put("quest", "ئەرک")
                put("reward", "پاداشت")
                put("title", "ناونیشان")
                put("class", "پۆل")
                put("master", "پیرۆزگەر")
                put("summon", "بانگهێشت")
                put("curse", "نەفرەت")
                put("blessing", "پیرۆز")
                put("monster", "دێو")
                put("king", "پاشا")
                put("prince", "شازادە")
                put("princess", "شازادە خانم")
                put("captain", "سەرکردە")
                put("hunter", "ڕاوکەر")
                // "shadow slave" (the title) is longer, so it wins over this bare noun.
                put("shadow", "سێبەر")
            }
        )

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
                // Whole dialogue lines, ported from the user's own Kurd-man glossary. A sentence
                // that is known to appear in these series never needs a request at all — and it
                // cannot come back half-English, which is the failure the engine mode produces.
                "what's that?" to "ئەوە چییە؟",
                "what is that?" to "ئەوە چییە؟",
                "what's this?" to "ئەمە چییە؟",
                "what is this?" to "ئەمە چییە؟",
                "who are you?" to "تۆ کێیت؟",
                "why are points being used?" to "بۆچی خاڵەکان بەکاردەهێنرێن؟",
                "how is this possible?" to "ئەمە چۆن دەکرێت؟",
                "this can't be!" to "ئەمە ناتوانێت بێت!",
                "impossible!" to "مەحاڵە!",
                "i don't know" to "نازانم",
                "i don't understand" to "تێناگەم",
                "thank you" to "سوپاس",
                "let's go" to "با بڕۆین",
                "for real?" to "بەڕاست؟",
                "are you serious?" to "بەڕاستت دەوێت؟",
                "you're dead" to "تۆ مردوویت",
                "i'll kill you" to "دەتکوژم",
                "it hurts" to "دەئێشێت",
                "help me" to "یارمەتیم بدە",
                "run away" to "ڕابکە",
                "run!" to "ڕابکە!",
                "wait!" to "بوەستە!",
                "stop!" to "بوەستە!",
                "die!" to "بمرە!",
                "no way" to "هەرگیز",
                "of course" to "بەدڵنیایی",
                "i see" to "تێدەگەم",
                "understood?" to "تێگەیشتیت؟",
                "level up" to "ئاست بەرزبوونەوە",
                "the end" to "کۆتایی",

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
