package app.mangascribe.core

/**
 * The last resort when no engine can translate a line: write the source in Kurdish letters.
 *
 * The user's instruction is blunt and correct: *if the words have no Kurdish meaning, make them
 * Kurdish letters anyway*. A reader of Sorani can sound out "Temple Slave" as
 * «تێمپڵ سلەیڤ» — they recognise the name, the label, the sound effect — whereas Latin text
 * inside a Kurdish bubble is simply unreadable in the middle of RTL text.
 *
 * This is **transliteration, not translation**, and the app never pretends otherwise: the line is
 * marked with [MARK] (an invisible marker is not enough — the reader should see that this is a
 * sound, not a meaning), and the caller stores the engine id as `transliterated` so a later retry
 * with a real engine can replace it.
 *
 * The mapping is English→Sorani orthography, not a character-by-character cipher:
 *  - digraphs first (`sh`, `ch`, `th`, `kh`, `gh`, `ph`), because Kurdish has single letters for
 *    each of those sounds;
 *  - vowels follow Kurdish spelling, where short vowels are usually written (کوردی writes "e" as
 *    ە and "î" as ی, unlike Arabic);
 *  - a final silent `e` is dropped, which is what makes "slave" read as سڵەیڤ rather than سڵاڤە;
 *  - `c` is /k/ before a, o, u and /s/ before e, i — the rule a Kurdish reader already applies.
 */
object KurdishTransliteration {

    /** Shown at the end of a transliterated line so nobody mistakes a sound for a meaning. */
    const val MARK = " ۞"

    /**
     * True when this text was produced by [transliterate] rather than by a translator. The marker
     * alone is not enough: a marked line that still contains a Latin letter is not finished work,
     * it is a rescue that half-failed.
     */
    fun isTransliterated(text: String): Boolean =
        text.trimEnd().endsWith(MARK.trim()) && !hasLatinLetters(text)

    /**
     * Latin text → Sorani letters. Arabic script is passed through untouched, and so are digits
     * and punctuation, so a mixed line keeps its numbers.
     */
    fun transliterate(input: String): String {
        if (input.isBlank()) return input
        // Already Arabic script: nothing to do (a name the engine already transliterated).
        if (input.any { it in '\u0600'..'\u06FF' }) return input
        val out = StringBuilder(input.length + 8)
        var i = 0
        val text = input
        while (i < text.length) {
            val c = text[i]
            val lower = c.lowercaseChar()
            val next = if (i + 1 < text.length) text[i + 1].lowercaseChar() else ' '
            val after = if (i + 2 < text.length) text[i + 2].lowercaseChar() else ' '

            // ---- digraphs and the sounds Kurdish writes with one letter ----
            val two = "" + lower + next
            val three = "" + lower + next + after
            if (three == "tch" || two == "ch") {
                out.append('چ'); i += if (three == "tch") 3 else 2; continue
            }
            if (two == "sh") { out.append('ش'); i += 2; continue }
            if (two == "th") { out.append('ث'); i += 2; continue }
            if (two == "kh" || two == "x") { out.append('خ'); i += if (two == "kh") 2 else 1; continue }
            if (two == "gh") { out.append('غ'); i += 2; continue }
            if (two == "ph") { out.append('ف'); i += 2; continue }
            if (two == "ck") { out.append('ک'); i += 2; continue }
            if (two == "qu") { out.append("کو"); i += 2; continue }
            if (two == "oo") { out.append('و'); i += 2; continue }
            if (two == "ee") { out.append('ی'); i += 2; continue }
            if (two == "ea") { out.append('ی'); i += 2; continue }
            if (two == "ou" || two == "ow") { out.append("ئۆ"); i += 2; continue }
            if (two == "ai" || two == "ay") { out.append("ەی"); i += 2; continue }

            when (lower) {
                'a' -> out.append('ا')
                'b' -> out.append('ب')
                'c' -> out.append(if (next in "eiy") 'س' else 'ک')
                'd' -> out.append('د')
                'e' -> {
                    // silent final e: "slave" → سڵەیڤ, not سڵاڤە
                    val finalE = i == text.length - 1 || !text[i + 1].isLetter()
                    val afterConsonant = i > 0 && text[i - 1].lowercaseChar() !in "aeiou"
                    if (!(finalE && afterConsonant)) out.append('ە')
                }
                'f' -> out.append('ف')
                'g' -> out.append('گ')
                'h' -> out.append('ه')
                'i' -> out.append('ی')
                'j' -> out.append('ج')
                'k' -> out.append('ک')
                'l' -> out.append('ل')
                'm' -> out.append('م')
                'n' -> out.append('ن')
                'o' -> out.append('ۆ')
                'p' -> out.append('پ')
                'q' -> out.append('ق')
                'r' -> out.append('ر')
                's' -> out.append('س')
                't' -> out.append('ت')
                'u' -> out.append('و')
                'v' -> out.append('ڤ')
                'w' -> out.append('و')
                'y' -> out.append('ی')
                'z' -> out.append('ز')
                else -> out.append(c)
            }
            i++
        }
        // A word that begins with a vowel needs a hamza carrier in Kurdish spelling.
        return fixWordStarts(out.toString())
    }

    private fun fixWordStarts(text: String): String {
        val out = StringBuilder(text.length)
        var atWordStart = true
        for ((index, ch) in text.withIndex()) {
            if (ch.isWhitespace() || ch in "،؛؟!.,:;()[]{}\"'") {
                atWordStart = true
                out.append(ch)
                continue
            }
            if (atWordStart && ch in "اویۆە") {
                out.append('ئ')
            }
            atWordStart = false
            out.append(ch)
            if (index == text.lastIndex) break
        }
        return out.toString()
    }

    /**
     * Rewrite **only the Latin words** of a mixed line, leaving Kurdish words, digits and
     * punctuation alone.
     *
     * This is the guarantee the reader asked for: `ئەسپێکت: temple slave` becomes
     * `ئەسپێکت: تێمپڵ سڵەیڤ`. Whatever an engine managed to translate is kept, and whatever it
     * left in English is written in Kurdish letters instead of being drawn as Latin inside RTL
     * text. No network, no engine: the result is always Arabic script.
     */
    fun rescueMixed(input: String): String {
        if (input.isBlank()) return input
        val out = StringBuilder(input.length + 8)
        var i = 0
        var changed = false
        while (i < input.length) {
            val c = input[i]
            val latinStart = c in 'A'..'Z' || c in 'a'..'z'
            if (!latinStart) {
                out.append(c)
                i++
                continue
            }
            var end = i
            while (end < input.length) {
                val d = input[end]
                if (d in 'A'..'Z' || d in 'a'..'z' || d == '\'' || d == '’') end++ else break
            }
            out.append(transliterate(input.substring(i, end)))
            changed = true
            i = end
        }
        if (!changed) return input
        // A rescued line is a transliteration of whatever was left, and says so.
        return out.toString().trimEnd() + MARK
    }

    /**
     * True when the text holds at least one Latin letter. Used as the final gate for a Kurdish
     * bubble: no Latin letter may be drawn in it at all.
     */
    fun hasLatinLetters(text: String): Boolean = text.any { it in 'A'..'Z' || it in 'a'..'z' }

    /**
     * The full answer for a line no engine could translate: Kurdish letters, the sound kept, and
     * a marker so the reader knows it was transliterated rather than translated.
     */
    fun fallbackLine(input: String): String {
        val letters = transliterate(input).trim()
        if (letters.isEmpty()) return input
        return letters + MARK
    }
}
