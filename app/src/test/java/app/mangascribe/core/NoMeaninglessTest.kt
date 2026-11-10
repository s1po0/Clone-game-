package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "هیچ شتێکی بەمانا نییە" — a bubble full of Kurdish letters and no meaning.
 *
 * Two rules come out of that screenshot, and both are absolute:
 *  1. a line that was not translated is **never drawn**. Kurdish letters standing in for English
 *     words look like a translation and say nothing, which is worse than the original artwork.
 *  2. a real Kurdish line with a name or label inside keeps the Kurdish and turns only the
 *     leftover words into Kurdish letters — it never becomes a transliteration of the whole line.
 */
class NoMeaninglessTest {

    @Test
    fun `a transliterated line is not drawn at all`() {
        val line = KurdishTransliteration.fallbackLine("WHAT THE ACTUAL FUCK IS THIS")
        assertTrue(KurdishTransliteration.isTransliterated(line))
        assertNull("a transliteration reached the page", TranslatedTextCleanup.drawable(line, "x", "ckb"))
    }

    @Test
    fun `a real translation is drawn`() {
        val kurdish = "لایەن: کۆیلەی پەرستگا"
        assertEquals(kurdish, TranslatedTextCleanup.drawable(kurdish, "Aspect: Temple Slave", "ckb"))
    }

    @Test
    fun `a kurdish line keeps a latin name but turns it into kurdish letters`() {
        val mixed = "ئەمە Shadow Slave ـە، ناوی تەواوی ئیست"
        val out = TranslatedTextCleanup.drawable(mixed, "this is Shadow Slave", "ckb")
        assertTrue("nothing drawn", out != null)
        assertFalse("latin survived: $out", KurdishTransliteration.hasLatinLetters(out!!))
        assertTrue("the Kurdish part was lost: $out", out.contains("ئەمە"))
    }

    @Test
    fun `an untranslated english line is not drawn as a sound`() {
        // What the old rescue did: turn it into Kurdish letters. Now: draw nothing.
        assertNull(
            TranslatedTextCleanup.drawable(
                KurdishTransliteration.rescueMixed("WHAT THE ACTUAL FUCK IS THIS"),
                "WHAT THE ACTUAL FUCK IS THIS",
                "ckb",
            ),
        )
    }

    @Test
    fun `a clean kurdish line is passed through`() {
        val clean = "دەڵێن بەهاکانی سپێڵ بەقەد تەحەدا هاوسەنگ دەبن."
        assertEquals(clean, TranslatedTextCleanup.drawable(clean, "x", "ckb"))
    }
}
