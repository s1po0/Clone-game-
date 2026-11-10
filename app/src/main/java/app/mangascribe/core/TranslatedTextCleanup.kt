package app.mangascribe.core

/**
 * Cleaning rules for text that came back from a translator rather than from OCR.
 *
 * A translation engine is not a typesetter: given `WHAT?!\nWHAT THE ACTUAL FUCK IS THIS?!` it
 * cheerfully returns both lines, in the source's capitalisation, and the renderer then draws
 * English capitals with an Arabic font. Two rules keep that out of a Kurdish bubble:
 *
 *  - **Latin residue**: if the "translation" is mostly Latin letters, it was not translated.
 *    Falling back to the source text is honest; drawing English with the Kurdish font is not.
 *  - **Shouted capitals**: engines preserve `ALLCAPS` from comic lettering, which in Kurdish
 *    reads as a spelling error because the script has no case at all.
 */
object TranslatedTextCleanup {

    /** Latin letters as a share of all letters, above which a result is probably untranslated. */
    const val LATIN_RESIDUE_RATIO = 0.6f

    fun hasLatinResidue(text: String, targetIsRtl: Boolean): Boolean {
        if (!targetIsRtl) return false
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return false
        val latin = letters.count { it in 'A'..'Z' || it in 'a'..'z' }
        return latin.toFloat() / letters.length >= LATIN_RESIDUE_RATIO
    }

    /**
     * Reduce ALL-CAPS runs to lower case so the shaper draws ordinary letters, and strip the
     * stray spaces OCR leaves before punctuation. Called only for RTL targets: a Latin target
     * needs its capitals.
     */
    fun forRtlTarget(text: String): String {
        val lowered = text.split(' ').joinToString(" ") { token ->
            val letters = token.filter { it.isLetter() }
            if (letters.length >= 2 && letters.all { it in 'A'..'Z' }) token.lowercase() else token
        }
        return lowered
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([؟،؛!.?])"), "$1")
            .replace(Regex("([.?!])\\s*$"), "$1")
            .trim()
    }

    /**
     * The text that should be drawn for one balloon. `null` means "nothing worth drawing":
     * the caller keeps the original art instead of putting a box over a face.
     */
    fun drawable(translated: String, source: String, targetLanguage: String): String? {
        val target = Languages.of(targetLanguage)
        val clean = translated.trim()
        if (clean.isEmpty()) return null
        if (target.rtl && hasLatinResidue(clean, targetIsRtl = true)) {
            // The engine echoed the source. Do not paint English in a Kurdish bubble.
            return source.trim().takeIf { it.isNotEmpty() }
        }
        return if (target.rtl) forRtlTarget(clean) else clean
    }
}
