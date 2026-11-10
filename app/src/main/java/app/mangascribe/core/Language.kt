package app.mangascribe.core

import kotlin.math.max
import kotlin.math.min

/**
 * Language table. Each entry knows what to hand ML Kit, what the translators expect, and
 * whether the script is written in vertical columns — the setting that decides how a bubble
 * is typeset and how OCR post-processing cleans it up.
 */
enum class Script { LATIN, ARABIC, KURDISH, JAPANESE, CHINESE, KOREAN, CYRILLIC, DEVANAGARI, THAI, OTHER }

data class Language(
    val code: String,          // BCP-47-ish tag used across the app and for translation APIs
    val displayName: String,
    val nativeName: String,
    val script: Script,
    /** ML Kit model key, or null when ML Kit has no model and only cloud OCR can read it. */
    val ocrModel: String?,
    /** Right-to-left reading order. */
    val rtl: Boolean = false,
    /** Usually written in vertical columns (manga/manhua/manhwa). */
    val vertical: Boolean = false,
    /**
     * The code each engine wants, when it is not the app's own. Engines disagree about
     * Kurdish exactly as you would expect: some want `ckb`, some `ku`, one wants `ku-Arab`.
     * `null` means the app code is used as-is; [EngineCodes.NONE] means "no code exists" and
     * the translator must find another way round (a pivot language, or say so plainly).
     */
    val engineCodes: Map<String, String?> = emptyMap(),
) {
    fun codeFor(engineId: String): String? = when {
        engineCodes.containsKey(engineId) -> engineCodes[engineId]
        else -> code
    }
}

/**
 * Kurdish (Sorani) is the language this app was asked for first, and the one every engine
 * handles worst. This object is the single place that knows the differences, so a translator
 * never guesses a code and a UI never promises a pair that cannot work.
 */
object EngineCodes {
    /** Sentinel: the engine has no code for this language at all. */
    const val NONE: String = "--none--"

    const val GOOGLE = "google"
    const val MYMEMORY = "mymemory"
    const val LIBRETRANSLATE = "libretranslate"
    const val LLM = "openai-compatible"

    /** Pair used when an engine cannot reach Kurdish directly: go through Turkish. */
    const val KURDISH_PIVOT = "tr"

    /** Both Kurdish languages the app knows: Sorani (Arabic script) and Kurmanji (Latin). */
    fun isKurdish(code: String?): Boolean =
        code == "ckb" || code == "ku" || code.equals("ku-Arab", ignoreCase = true)

    /** Sorani specifically — the dialect with no LibreTranslate route and a strict letter set. */
    fun isSorani(code: String?): Boolean = code == "ckb" || code.equals("ku-Arab", ignoreCase = true)

    /** True when the engine has no direct route between two languages and a pivot is needed. */
    fun needsPivot(engineId: String, from: String, to: String): Boolean {
        val src = Languages.of(from).codeFor(engineId) ?: return false
        val dst = Languages.of(to).codeFor(engineId) ?: return false
        return src == NONE || dst == NONE
    }
}

object Languages {
    val all: List<Language> = listOf(
        Language(
            "ja", "Japanese", "日本語", Script.JAPANESE, "japanese", vertical = true,
            engineCodes = mapOf(EngineCodes.LLM to "ja"),
        ),
        Language("ko", "Korean", "한국어", Script.KOREAN, "korean"),
        // Kurdish (Kurmanji) reads Latin script; Sorani above reads Arabic script.
        Language(
            "zh-Hans", "Chinese (Simplified)", "简体中文", Script.CHINESE, "chinese",
            engineCodes = mapOf(EngineCodes.LLM to "zh"),
        ),
        Language(
            "zh-Hant", "Chinese (Traditional)", "繁體中文", Script.CHINESE, "chinese",
            engineCodes = mapOf(EngineCodes.LLM to "zh-Hant"),
        ),
        // English is free of charge on every engine, so it is also the pivot leg of last resort.
        Language("en", "English", "English", Script.LATIN, "latin"),
        Language(
            "ar", "Arabic", "العربية", Script.ARABIC, "arabic", rtl = true,
            engineCodes = mapOf(EngineCodes.LLM to "ar"),
        ),
        Language(
            code = "ckb",
            displayName = "Kurdish (Sorani)",
            nativeName = "کوردیی سۆرانی",
            script = Script.KURDISH,
            // ML Kit has no Kurdish model; it has an Arabic one, and Sorani is Arabic script
            // (پ ژ گ چ ڤ ۆ ێ ڕ ڵ ە). Recognition is close but not free — see OcrCleanup.
            ocrModel = "arabic",
            rtl = true,
            engineCodes = mapOf(
                // Google is the only free route into Sorani, and it is the one that works.
                EngineCodes.GOOGLE to "ckb",
                EngineCodes.MYMEMORY to "ckb",
                EngineCodes.LIBRETRANSLATE to EngineCodes.NONE,
                EngineCodes.LLM to "ckb",
            ),
        ),
        Language(
            code = "ku",
            displayName = "Kurdish (Kurmanji)",
            nativeName = "Kurmancî",
            // Latin script — a different language from Sorani above, and the one engines are
            // more willing to translate. Kept separate so a Sorani reader never gets Kurmanji.
            script = Script.LATIN,
            ocrModel = "latin",
            engineCodes = mapOf(
                EngineCodes.GOOGLE to "ku",
                EngineCodes.MYMEMORY to "ku",
                EngineCodes.LIBRETRANSLATE to EngineCodes.NONE,
                EngineCodes.LLM to "ku",
            ),
        ),
        Language(
            code = "fa",
            displayName = "Persian",
            nativeName = "فارسی",
            script = Script.ARABIC,
            ocrModel = "arabic",
            rtl = true,
            engineCodes = mapOf(EngineCodes.LLM to "fa"),
        ),
        Language(
            "tr", "Turkish", "Türkçe", Script.LATIN, "latin",
            // MyMemory reaches Kurdish through Turkish, so Turkish is the pivot leg.
            engineCodes = mapOf(
                EngineCodes.MYMEMORY to "tr",
                EngineCodes.LIBRETRANSLATE to "tr",
                EngineCodes.LLM to "tr",
            ),
        ),
        Language("fr", "French", "Français", Script.LATIN, "latin"),
        Language("de", "German", "Deutsch", Script.LATIN, "latin"),
        Language("es", "Spanish", "Español", Script.LATIN, "latin"),
        Language("it", "Italian", "Italiano", Script.LATIN, "latin"),
        Language("pt", "Portuguese", "Português", Script.LATIN, "latin"),
        Language("ru", "Russian", "Русский", Script.CYRILLIC, "cyrillic",
            engineCodes = mapOf(EngineCodes.LLM to "ru")),
        Language("uk", "Ukrainian", "Українська", Script.CYRILLIC, "cyrillic",
            engineCodes = mapOf(EngineCodes.LLM to "uk")),
        Language("id", "Indonesian", "Bahasa Indonesia", Script.LATIN, "latin"),
        Language("vi", "Vietnamese", "Tiếng Việt", Script.LATIN, "latin"),
        Language("th", "Thai", "ไทย", Script.THAI, null,
            engineCodes = mapOf(EngineCodes.MYMEMORY to "th", EngineCodes.LLM to "th")),
        Language("hi", "Hindi", "हिन्दी", Script.DEVANAGARI, "devanagari"),
        Language("bn", "Bengali", "বাংলা", Script.DEVANAGARI, "devanagari"),
        Language("ta", "Tamil", "தமிழ்", Script.DEVANAGARI, "devanagari"),
    )

    private val byCode = all.associateBy { it.code }

    fun of(code: String?): Language =
        byCode[code] ?: all.first { it.code == "en" }

    fun ocrModelsFor(code: String): List<String> {
        val lang = of(code)
        val base = listOfNotNull(lang.ocrModel)
        // Japanese pages carry Latin sfx and numbers, so the Latin model rides along.
        return when (lang.script) {
            Script.JAPANESE, Script.KOREAN, Script.CHINESE, Script.CYRILLIC -> base + "latin"
            else -> base.ifEmpty { listOf("latin") }
        }
    }

    /**
     * Auto-detect the source language from the characters actually recognised.
     * Kurdish (Sorani) is checked before Arabic: they share a script, but Sorani's own letters
     * (پ ژ گ چ ڤ ۆ ێ ڕ ڵ ە) are enough to tell them apart on anything longer than a word.
     */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        if (OcrCleanup.looksKurdish(text)) return "ckb"
        if (OcrCleanup.looksKurmanji(text)) return "ku"
        val hasKana = text.any { it in '\u3040'..'\u30FF' }
        val hasHan = text.any { it in '\u4E00'..'\u9FFF' }
        val hasHangul = text.any { it in '\uAC00'..'\uD7AF' }
        val hasArabic = text.any { it in '\u0600'..'\u06FF' }
        val hasThai = text.any { it in '\u0E00'..'\u0E7F' }
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        val hasCyrillic = text.any { it in '\u0400'..'\u04FF' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when {
            hasKana -> "ja"
            hasHangul -> "ko"
            hasHan -> "zh-Hans"
            hasThai -> "th"
            hasDevanagari -> "hi"
            hasArabic -> "ar"
            hasCyrillic -> "ru"
            latin > 0 -> "en"
            else -> null
        }
    }
}

/**
 * Cleaning rules that generic OCR gets wrong on comic lettering. Each rule is a small pure
 * function so it can be unit-tested without a device.
 */
object OcrCleanup {

    /** Latin model punctuates Japanese columns; Japanese model keeps note glyphs. */
    private val NOISE = setOf('\u00A9', '\u00AE', '\u00B0', '\u00B7', '|', '~', '`', '_')

    /**
     * Remove furigana: small kana-only runs that sit on top of a kanji column.
     * ML Kit merges them into one line, giving strings like "皇帝こうてい". We drop any run of
     * kana that is at least 2 long and immediately follows kanji, when the run is short
     * relative to the surrounding kanji count.
     */
    fun stripFurigana(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isKanji(c)) { out.append(c); i++; continue }
            // consume the run of kanji
            val kStart = i
            while (i < text.length && isKanji(text[i])) { out.append(text[i]); i++ }
            val kanjiRun = i - kStart
            // consume a following kana run; if short relative to the kanji, it is furigana
            val kanaStart = i
            while (i < text.length && isKana(text[i])) i++
            val kanaRun = i - kanaStart
            if (kanaRun in 2..4 && kanjiRun >= 1 && i < text.length && isKanji(text[i])) {
                // furigana between two kanji groups: drop it
                continue
            }
            // otherwise it is real text — put it back
            out.append(text, kanaStart, i)
        }
        return out.toString()
    }

    /**
     * Undo the line-break damage OCR does inside a balloon: a soft hyphen, a trailing hyphen,
     * and Latin words split across two lines ("trans- lation").
     */
    fun repairLineBreaks(text: String): String =
        text.replace(Regex("([A-Za-z])-\\s*\\n\\s*([a-z])"), "$1$2")
            .replace(Regex("[ \\t]*\\n[ \\t]*"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    /**
     * Drop the page furniture OCR picks up: standalone page numbers, watermark fragments and
     * strings that are only punctuation or only digits shorter than 3 characters.
     */
    fun isJunk(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length <= 3 && t.all { it.isDigit() }) return true
        val meaningful = t.count { it.isLetterOrDigit() && !it.isWhitespace() }
        return meaningful == 0
    }

    // ---- Kurdish (Sorani) -----------------------------------------------------------------
    //
    // Sorani is written in Arabic script with letters Arabic does not have. ML Kit's Arabic
    // model reads most of them, but it systematically swaps the three it has no glyph for,
    // and it drops the RTL mark that keeps a Latin word (a character name, a number) from
    // flipping to the wrong end of the line. Both are repaired here instead of in the UI.

    private val KURDISH_ONLY = setOf('پ', 'ژ', 'گ', 'چ', 'ڤ', 'ۆ', 'ێ', 'ڕ', 'ڵ', 'ە')

    /** Characters that only exist in Kurdish-in-Arabic-script writing. */
    fun looksKurdish(text: String): Boolean {
        val hits = text.count { it in KURDISH_ONLY }
        // Arabic has none of these, Persian has چ پ ژ گ only. Two distinct hits is decisive;
        // one hit needs a second signal (ۆ ێ ە ڵ ڕ are Persian-free).
        if (hits >= 2) return true
        if (hits == 1 && text.any { it in setOf('ۆ', 'ێ', 'ە', 'ڵ', 'ڕ', 'ڤ') }) return true
        // ە (U+06D5, Kurdish/Persian ae) against ه (U+0647) is the cheapest tell of all.
        return text.contains('ە') && text.any { it in KURDISH_ONLY }
    }

    /**
     * Kurmanji is Latin script with letters that are not Turkish, English or German: ç ê î ş û.
     * Four of them in one line, or a Kurdish-only word like "ez"/"û", is enough to name it.
     */
    fun looksKurmanji(text: String): Boolean {
        val marks = text.count { it in "êîûşçÊÎÛŞÇ" }
        if (marks >= 3) return true
        val words = text.lowercase().split(Regex("[^a-zçêîşû]+")).filter { it.length > 1 }
        if (words.isEmpty()) return false
        val kurdishWords = setOf("ez", "tu", "ew", "û", "ji", "li", "ne", "ye", "bû", "kir")
        return words.count { it in kurdishWords } * 4 >= words.size
    }

    /**
     * Fix what the Arabic OCR model consistently gets wrong on Kurdish:
     *  - ك (Arabic kaf) is read where Kurdish writes ک (keheh);
     *  - ي/ى (Arabic yeh) where Kurdish writes ی;
     *  - ھ (heh doachashmee) where Kurdish writes ه;
     *  - the Arabic yeh at a word's end is almost always the Kurdish ezafe or a plural.
     */
    fun normaliseKurdish(text: String): String =
        text.replace('ك', 'ک')
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ھ', 'ه')
            .replace("\u0640", "") // tatweel: OCR padding inside justified Arabic script

    /**
     * Undo OCR damage that turns Arabic-script letters into shapes instead of letters.
     *
     * Two distinct cases, both seen in the wild and both fatal to a translation:
     *  - **Arabic Presentation Forms** (`U+FB50–FDFF`, `U+FE70–FEFF`): the isolated/initial/
     *    medial/final shapes as separate code points. Unicode compatibility decomposition folds
     *    them back onto their base letters, after which the platform shaper joins them properly
     *    again; NFKC does exactly that, so no 500-entry table is hand-written here.
     *  - **Private Use Area** (`U+E000–F8FF`): some OCR models were trained on "cmap hack"
     *    fonts that put Kurdish/Arabic glyphs there. Those code points have no meaning outside
     *    that font: a translator receives gibberish and the renderer draws empty boxes. They are
     *    dropped, and the surrounding text is left intact.
     *
     * The check is cheap on Latin text (a single pass, no allocation) so it can run on every
     * block before either translation or typesetting.
     */
    fun repairPresentationalForms(text: String): String {
        val suspicious = text.any {
            it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' || it in '\uE000'..'\uF8FF'
        }
        if (!suspicious) return text
        val stripped = text.filterNot { it in '\uE000'..'\uF8FF' }
        return java.text.Normalizer.normalize(stripped, java.text.Normalizer.Form.NFKC)
    }

    /**
     * Insert the RTL mark around runs of Latin letters and digits so mixed lines keep their
     * order when drawn: "Goku ٣" must not render as "٣ Goku" in an RTL paragraph.
     */
    fun isolateLatinRuns(text: String): String {
        val regex = Regex("[A-Za-z0-9][A-Za-z0-9 .,:/\\-]*[A-Za-z0-9]|[A-Za-z0-9]")
        return regex.replace(text) { m -> "\u2067" + m.value + "\u2069" }
    }

    /** One block's text, in reading order, cleaned for translation. */
    fun clean(raw: String, languageCode: String, stripFurigana: Boolean): String {
        var t = repairPresentationalForms(raw)
        t = repairLineBreaks(t)
        if (stripFurigana && Languages.of(languageCode).script == Script.JAPANESE) {
            t = stripFurigana(t)
        }
        if (Languages.of(languageCode).script == Script.KURDISH) {
            t = normaliseKurdish(t)
        }
        t = t.dropWhile { it in NOISE || it.isWhitespace() }
            .dropLastWhile { it in NOISE || it.isWhitespace() }
        return t.trim()
    }

    private fun isKanji(c: Char): Boolean = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isKana(c: Char): Boolean = c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'
}

/**
 * Groups OCR fragments that belong to one balloon, and orders them for reading.
 *
 * ML Kit returns one block per text line; a balloon with three lines is three blocks. Two
 * blocks join when their boxes are close (gap under [maxGapFactor] x the line height) and their
 * tilt matches. Vertical Japanese columns read right-to-left, Arabic right-to-left, Latin
 * top-to-bottom.
 */
object BlockMerger {

    data class MergeOptions(
        val languageCode: String = "ja",
        val maxGapFactor: Float = 1.1f,
        val joinFurigana: Boolean = true,
    )

    fun merge(blocks: List<TextBlock>, options: MergeOptions = MergeOptions()): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val parent = IntArray(blocks.size) { it }

        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in blocks.indices) {
            for (j in i + 1 until blocks.size) {
                if (shouldJoin(blocks[i], blocks[j], options)) union(i, j)
            }
        }

        val groups = blocks.indices.groupBy { find(it) }.values
        return groups.mapIndexed { index, members ->
            if (members.size == 1) {
                blocks[members[0]].copy(id = index)
            } else {
                val ordered = orderForReading(members.map { blocks[it] }, options.languageCode)
                val text = ordered.joinToString("\n") { it.text }
                val polygon = RectF.bounding(ordered.flatMap { it.polygon })
                TextBlock(
                    id = index,
                    text = text,
                    polygon = listOf(
                        PointF(polygon.left, polygon.top),
                        PointF(polygon.right, polygon.top),
                        PointF(polygon.right, polygon.bottom),
                        PointF(polygon.left, polygon.bottom),
                    ),
                    languageTag = ordered.firstOrNull { it.languageTag != null }?.languageTag,
                    angleDegrees = ordered.map { it.angleDegrees }.average().toFloat(),
                )
            }
        }
    }

    fun shouldJoin(a: TextBlock, b: TextBlock, options: MergeOptions): Boolean {
        val ra = a.bounds
        val rb = b.bounds
        val lineHeight = min(ra.height, rb.height)
        val gapLimit = max(6f, lineHeight * options.maxGapFactor)
        val angleClose = kotlin.math.abs(a.angleDegrees - b.angleDegrees) < 8f
        if (!angleClose) return false
        if (ra.overlapRatio(rb) > 0.15f) return true
        val vertical = a.vertical && b.vertical
        return if (vertical) {
            // neighbouring columns: vertical overlap, horizontal gap under the limit
            val vGap = max(0f, max(ra.top, rb.top) - min(ra.bottom, rb.bottom))
            val hGap = max(0f, max(ra.left, rb.left) - min(ra.right, rb.right))
            vGap < gapLimit * 2f && hGap < gapLimit * 1.6f
        } else {
            val vGap = max(0f, max(ra.top, rb.top) - min(ra.bottom, rb.bottom))
            val hGap = max(0f, max(ra.left, rb.left) - min(ra.right, rb.right))
            vGap < gapLimit && hGap < gapLimit * 1.2f
        }
    }

    /** Reading order for a balloon's fragments. */
    fun orderForReading(blocks: List<TextBlock>, languageCode: String): List<TextBlock> {
        val lang = Languages.of(languageCode)
        val vertical = blocks.count { it.vertical } * 2 >= blocks.size
        return when {
            // vertical Japanese and Chinese read in columns, right to left
            vertical && (lang.script == Script.JAPANESE || lang.script == Script.CHINESE) ->
                blocks.sortedWith(
                    compareByDescending<TextBlock> { it.bounds.centerX }.thenBy { it.bounds.centerY }
                )
            lang.rtl -> blocks.sortedWith(
                compareByDescending<TextBlock> { it.bounds.centerX }.thenBy { it.bounds.centerY }
            )
            else -> blocks.sortedWith(
                compareBy({ it.bounds.centerY }, { it.bounds.centerX })
            )
        }
    }
}
