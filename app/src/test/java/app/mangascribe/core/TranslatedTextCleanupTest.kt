package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From a real page: a Kurdish target with the English line left in the bubble
 * ("what the ! چ؟ actual fuck is this?!"). A user reads that as "the translation is broken",
 * which it is — the engine returned source text and the renderer drew it with a Kurdish font.
 */
class TranslatedTextCleanupTest {

    @Test
    fun `english left in a kurdish bubble is detected`() {
        assertTrue(
            TranslatedTextCleanup.hasLatinResidue(
                "what the ! ? actual fuck is this?!", targetIsRtl = true,
            )
        )
    }

    @Test
    fun `kurdish with a character name is not residue`() {
        assertFalse(
            TranslatedTextCleanup.hasLatinResidue("بڵێ بەهاکانی سێیل بەقەد تەحدا هاوەسەنگ دەبن.", true)
        )
        assertFalse(TranslatedTextCleanup.hasLatinResidue("گۆکو دەگەڕێتەوە", true))
    }

    @Test
    fun `latin targets are never treated as residue`() {
        assertFalse(TranslatedTextCleanup.hasLatinResidue("Hello there", targetIsRtl = false))
    }

    @Test
    fun `shouted capitals become ordinary letters for a kurdish target`() {
        assertEquals(
            "ئەمە چییە بەڕاستی؟",
            TranslatedTextCleanup.forRtlTarget("ئەمە  چییە   بەڕاستی ؟"),
        )
    }

    @Test
    fun `stray space before kurdish punctuation is removed`() {
        assertEquals("چی؟", TranslatedTextCleanup.forRtlTarget("چی ؟"))
    }

    @Test
    fun `an untranslated line falls back to the original instead of english in a kurdish bubble`() {
        assertEquals(
            "What the actual fuck is this?!",
            TranslatedTextCleanup.drawable(
                "what the actual fuck is this?!",
                "What the actual fuck is this?!",
                "ckb",
            ),
        )
    }

    @Test
    fun `an empty translation draws nothing`() {
        assertNull(TranslatedTextCleanup.drawable("   ", "What?!", "ckb"))
    }

    @Test
    fun `a kurdish translation is drawn as it is`() {
        assertEquals("چی؟!", TranslatedTextCleanup.drawable("چی؟!", "What?!", "ckb"))
    }
}
