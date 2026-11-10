package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kurdish spelling repair for generated text, separate from the Arabic→Kurdish letter fixes
 * applied to OCR output.
 *
 * From the user's page: `دەڵێن بەهاکانی` is right, but a translator that types the final "e" as
 * Arabic heh (ه) instead of Kurdish ae (ە) turns one word into another, and the reader sees a
 * misspelling in a sentence that is otherwise perfect.
 */
class KurdishSpellingTest {

    @Test
    fun `word-final arabic heh becomes kurdish ae`() {
        // "خانە" (house) typed with a final heh instead of ae
        assertEquals("خانە", OcrCleanup.normaliseKurdishSpelling("خانه"))
    }

    @Test
    fun `arabic teh marbuta becomes kurdish ae`() {
        assertEquals("چیرۆکە", OcrCleanup.normaliseKurdishSpelling("چیرۆکة"))
    }

    @Test
    fun `a medial heh is left alone because it is a real letter`() {
        // هەموو really does contain heh followed by ae; the first heh must not be rewritten.
        val word = "هەموو"
        assertEquals(word, OcrCleanup.normaliseKurdishSpelling(word))
        assertEquals("هەڵە", OcrCleanup.normaliseKurdishSpelling("هەڵە"))
    }

    @Test
    fun `latin and already-correct kurdish pass through unchanged`() {
        val correct = "دەڵێن بەهاکانی سێیل بەقەد تەحدا هاوەسەنگ دەبن."
        assertEquals(correct, OcrCleanup.normaliseKurdishSpelling(correct))
        assertEquals("ok", OcrCleanup.normaliseKurdishSpelling("ok"))
        assertEquals("", OcrCleanup.normaliseKurdishSpelling(""))
    }

    @Test
    fun `clean applies both kurdish repairs to ocr text`() {
        // Arabic kaf/yeh and a final heh in one line: both have to be fixed.
        assertEquals(
            "کوردیی سۆرانییە",
            OcrCleanup.clean("كورديي سۆرانييه", "ckb", stripFurigana = false),
        )
    }
}
