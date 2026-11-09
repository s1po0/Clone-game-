package app.mangascribe.core

import kotlin.math.max
import kotlin.math.min

/**
 * Language table. Each entry knows what to hand ML Kit, what the translators expect, and
 * whether the script is written in vertical columns — the setting that decides how a bubble
 * is typeset and how OCR post-processing cleans it up.
 */
enum class Script { LATIN, JAPANESE, CHINESE, KOREAN, ARABIC, CYRILLIC, DEVANAGARI, THAI, OTHER }

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
)

object Languages {
    val all: List<Language> = listOf(
        Language("ja", "Japanese", "日本語", Script.JAPANESE, "japanese", vertical = true),
        Language("ko", "Korean", "한국어", Script.KOREAN, "korean"),
        Language("zh-Hans", "Chinese (Simplified)", "简体中文", Script.CHINESE, "chinese"),
        Language("zh-Hant", "Chinese (Traditional)", "繁體中文", Script.CHINESE, "chinese"),
        Language("en", "English", "English", Script.LATIN, "latin"),
        Language("ar", "Arabic", "العربية", Script.ARABIC, "arabic", rtl = true),
        Language("ckb", "Kurdish (Sorani)", "کوردی", Script.ARABIC, "arabic", rtl = true),
        Language("fa", "Persian", "فارسی", Script.ARABIC, "arabic", rtl = true),
        Language("tr", "Turkish", "Türkçe", Script.LATIN, "latin"),
        Language("fr", "French", "Français", Script.LATIN, "latin"),
        Language("de", "German", "Deutsch", Script.LATIN, "latin"),
        Language("es", "Spanish", "Español", Script.LATIN, "latin"),
        Language("it", "Italian", "Italiano", Script.LATIN, "latin"),
        Language("pt", "Portuguese", "Português", Script.LATIN, "latin"),
        Language("ru", "Russian", "Русский", Script.CYRILLIC, "cyrillic"),
        Language("uk", "Ukrainian", "Українська", Script.CYRILLIC, "cyrillic"),
        Language("id", "Indonesian", "Bahasa Indonesia", Script.LATIN, "latin"),
        Language("vi", "Vietnamese", "Tiếng Việt", Script.LATIN, "latin"),
        Language("th", "Thai", "ไทย", Script.THAI, null),
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

    /** Auto-detect the source language from the characters actually recognised. */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
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

    /** One block's text, in reading order, cleaned for translation. */
    fun clean(raw: String, languageCode: String, stripFurigana: Boolean): String {
        var t = repairLineBreaks(raw)
        if (stripFurigana && Languages.of(languageCode).script == Script.JAPANESE) {
            t = stripFurigana(t)
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
