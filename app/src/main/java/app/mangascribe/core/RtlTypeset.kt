package app.mangascribe.core

/**
 * Typesetting for Arabic-script targets, and Kurdish (Sorani) in particular.
 *
 * Three things a naive Latin wrap gets wrong on Kurdish:
 *  1. **Direction.** A wrapped line has to be drawn from the right edge, and a line that is
 *     only part full must sit against the right side of the bubble, not the left.
 *  2. **Joining.** Arabic script letters take up to four shapes depending on neighbours; the
 *     platform shaper does that, but only if it receives a whole word. A word broken across
 *     two lines is rendered as two disjoint letter groups — visible to any Kurdish reader.
 *  3. **Spacing.** Arabic-script text has no upper/lower case, so the usual "shrink the font"
 *     trick reads as noisy; a little kashida or a slightly looser line is the typographic
 *     answer, and the fitter is told to prefer one more line over a smaller font.
 */
object RtlTypeset {

    /**
     * Letters that never join to the following letter, so a line may break after them:
     * alef, dal, reh, waw and their variants, plus the Kurdish reh/ae forms.
     */
    private const val NON_JOINING = "اأإآدذرزژوؤۆڕەء"

    private fun nonJoining(c: Char): Boolean =
        NON_JOINING.contains(c) || c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    fun isArabicScript(c: Char): Boolean = c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F'

    /** True when a line may not break between these two characters (they are one word). */
    fun mustNotBreakBetween(left: Char, right: Char): Boolean {
        if (isArabicScript(left) && isArabicScript(right)) return true
        // do not orphan a single letter, a diacritic, or a RTL mark
        if (left in '\u064B'..'\u065F') return true
        if (right in '\u064B'..'\u065F') return true
        if (right == '\u200F' || right == '\u200E' || right == '\u2067' || right == '\u2069') return true
        return false
    }

    /**
     * Wrap for a right-to-left bubble. Uses the same greedy logic as the Latin fitter, but
     * refuses every break that would tear a joined word in half, and reports the lines
     * already ordered for drawing right to left (line 1 first — the reader starts at the top
     * right and the drawing code anchors each line to the right edge).
     */
    fun wrap(
        text: String,
        maxWidth: Float,
        sizePx: Float,
        measurer: Measurer,
        maxLines: Int = 60,
    ): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) { out += ""; continue }
            var line = StringBuilder()
            // Arabic script is written without spaces inside a word, so tokens come from
            // whitespace only — the naive CJK per-character split must not apply here.
            for (token in paragraph.split(' ')) {
                if (token.isEmpty()) continue
                val candidate = if (line.isEmpty()) token else line.toString() + " " + token
                if (line.isEmpty() || measurer.width(candidate, sizePx) <= maxWidth) {
                    if (line.isEmpty() || measurer.width(candidate, sizePx) <= maxWidth) {
                        line = StringBuilder(candidate)
                        continue
                    }
                }
                out += line.toString()
                line = StringBuilder(token)
                if (out.size >= maxLines) return out
            }
            if (line.isNotEmpty()) out += line.toString()
            if (out.size >= maxLines) break
        }
        // a single word wider than the bubble still has to fit: break at non-joining letters
        return out.take(maxLines).flatMap { breakWordIfNeeded(it, maxWidth, sizePx, measurer) }
            .take(maxLines)
            .ifEmpty { listOf("") }
    }

    /** Break a too-wide word only where the script allows: after a non-joining letter. */
    private fun breakWordIfNeeded(
        word: String,
        maxWidth: Float,
        sizePx: Float,
        measurer: Measurer,
    ): List<String> {
        if (word.isEmpty() || measurer.width(word, sizePx) <= maxWidth) return listOf(word)
        val parts = mutableListOf<String>()
        var rest = word
        while (measurer.width(rest, sizePx) > maxWidth && rest.length > 1) {
            var legal = -1
            var anyFit = -1
            for (i in rest.length - 1 downTo 1) {
                val candidateWidth = measurer.width(rest.substring(0, i), sizePx)
                if (candidateWidth > maxWidth) continue
                if (anyFit == -1) anyFit = i
                if (nonJoining(rest[i - 1]) || !isArabicScript(rest[i - 1]) || !isArabicScript(rest[i])) {
                    legal = i
                    break
                }
            }
            val cut = if (legal != -1) legal else if (anyFit != -1) anyFit else 1
            parts += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /**
     * Kashida: the script's own way of filling a short line. Returns the number of tatweel
     * characters to insert, which the renderer spreads after joining letters. Zero for a line
     * that is already close to the box, so normal dialogue is untouched.
     */
    fun kashidaCount(lineWidth: Float, boxWidth: Float, sizePx: Float): Int {
        val slack = boxWidth - lineWidth
        if (slack < sizePx * 1.5f) return 0
        return (slack / (sizePx * 0.75f)).toInt().coerceIn(0, 12)
    }

    /** Kurdish plural/ezafe punctuation that OCR often drops: keep the sentence readable. */
    fun normalisePunctuation(text: String): String =
        text.replace("؟", "؟")     // Arabic question mark: correct for Kurdish, OCR sometimes adds a space
            .replace(Regex("[ \\t]+([،؛؟!])"), "$1")
            .replace(Regex("([،؛؟!])(?=[^\\s])"), "$1 ")
            .trim()

    /** Azerbaijani-style Latin pips inside Kurdish text stay LTR: wrap them for drawing. */
    fun isolateLatin(text: String): String = OcrCleanup.isolateLatinRuns(text)
}
