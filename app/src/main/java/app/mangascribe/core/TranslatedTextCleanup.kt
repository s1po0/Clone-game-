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
            .replace(" " + KurdishTransliteration.MARK.trim(), KurdishTransliteration.MARK)
            .replace(Regex("\\s+([؟،؛!.?])"), "$1")
            .replace(Regex("([.?!])\\s*$"), "$1")
            .trim()
    }

    /**
     * Words that are still in the source language after translation.
     *
     * Stronger than [hasLatinResidue], which only looks at the *share* of Latin letters: a line of
     * Kurdish with two English words in it passes that test ("ئەسپێکت: temple slave"). Here every
     * word of three or more Latin letters is compared with the source: a word that appears in the
     * source was simply not translated; a word that does not (a name the engine transliterated
     * back, an acronym) is left alone.
     */
    fun hasLeftoverSource(translated: String, source: String, targetIsRtl: Boolean): Boolean {
        if (!targetIsRtl) return false
        if (translated.isBlank()) return true
        // A line written in Kurdish letters is finished work, even when it is a transliteration.
        if (KurdishTransliteration.isTransliterated(translated)) return false
        val sourceWords = source.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .toSet()
        val words = translated.split(Regex("[^\\p{L}\\p{N}']+"))
        for (word in words) {
            if (word.length < 3) continue
            if (!word.all { it in 'A'..'Z' || it in 'a'..'z' }) continue
            if (word.lowercase() in sourceWords) return true
        }
        // A line made only of punctuation is not translated text, it is a leftover. In the
        // reported page the engine answered "what the !؟ actual fuck is this?!" with "!؟" —
        // a line with no letters passes every word test, so it survived and was drawn.
        return !targetHasAnyLetter(translated) && sourceHasAnyLetter(source)
    }

    /** True when the text contains at least one letter of any script. */
    fun targetHasAnyLetter(text: String): Boolean = text.any { it.isLetter() }

    /** True when the source contains at least one letter (so "!?" alone is not a failure). */
    fun sourceHasAnyLetter(text: String): Boolean = text.any { it.isLetter() }

    /**
     * The text that should be drawn for one balloon. `null` means "nothing worth drawing":
     * the caller keeps the original art instead of putting a box over a face.
     */
    fun drawable(translated: String, source: String, targetLanguage: String): String? {
        val target = Languages.of(targetLanguage)
        val clean = translated.trim()
        if (clean.isEmpty()) return null
        if (target.rtl) {
            // A line that could not be translated is **not drawn at all**.
            //
            // It used to be rescued into Kurdish letters, which is how a reader got
            // "هه نده كيان دروست" — Kurdish characters with no meaning. That is worse than the
            // original art: it looks like a translation and says nothing. Keeping the source
            // visible is honest, and the reader card says how many lines had to be left alone.
            if (KurdishTransliteration.isTransliterated(clean)) return null
            val hasLatin = KurdishTransliteration.hasLatinLetters(clean)
            if (hasLatin) {
                // The engine left a word or two in English inside a Kurdish line. Those become
                // Kurdish letters (a name, a label — the reader already knows the sound); the
                // sentence around them is real Kurdish and is kept.
                val rescued = KurdishTransliteration.rescueMixed(clean)
                if (rescued.isBlank() || KurdishTransliteration.isTransliterated(rescued)) return null
                return forRtlTarget(rescued)
            }
            return forRtlTarget(clean)
        }
        return clean
    }
}
