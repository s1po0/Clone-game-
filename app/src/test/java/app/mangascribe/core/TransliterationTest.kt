package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last resort, as instructed: if a word has no Kurdish meaning, write it in Kurdish letters
 * rather than leaving English in an RTL bubble. It is transliteration, it is marked, and it is
 * never presented as a translation.
 */
class TransliterationTest {

    @Test
    fun `a name keeps its sound in kurdish letters`() {
        val out = KurdishTransliteration.transliterate("Temple Slave")
        assertTrue("got: $out", out.any { it in '\u0600'..'\u06FF' })
        assertFalse(out.any { it in 'A'..'Z' || it in 'a'..'z' })
        // س ل ی ڤ are the sounds of "slave": the final silent e is not written.
        assertTrue(out.contains("سڵەیڤ") || out.contains("سلەیڤ"))
    }

    @Test
    fun `digraphs become one kurdish letter`() {
        assertTrue(KurdishTransliteration.transliterate("shadow").contains('ش'))
        assertTrue(KurdishTransliteration.transliterate("check").contains('چ'))
        assertTrue(KurdishTransliteration.transliterate("khan").contains('خ'))
    }

    @Test
    fun `a vowel at the start of a word gets a hamza carrier`() {
        val out = KurdishTransliteration.transliterate("aspect")
        assertTrue("got: $out", out.startsWith("ئ"))
    }

    @Test
    fun `digits and punctuation survive`() {
        val out = KurdishTransliteration.transliterate("Level 3!")
        assertTrue(out.contains("3"))
        assertTrue(out.contains("!"))
    }

    @Test
    fun `already kurdish text is not touched`() {
        val kurdish = "من تۆ دەپارێزم"
        assertEquals(kurdish, KurdishTransliteration.transliterate(kurdish))
    }

    @Test
    fun `a fallback line is marked so nobody reads a sound as a meaning`() {
        val line = KurdishTransliteration.fallbackLine("WORTHLESS")
        assertTrue(KurdishTransliteration.isTransliterated(line))
        assertFalse(KurdishTransliteration.isTransliterated("چی؟!"))
    }
}
