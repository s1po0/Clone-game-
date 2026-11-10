package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's rule, as a test: whatever happens upstream, a Kurdish bubble is drawn with **no
 * Latin letter in it**. Arabic works because Google has a direct Arabic model; Sorani does not,
 * so the app has to guarantee the output itself instead of trusting the engine.
 */
class NoLatinTest {

    private fun drawn(text: String, target: String = "ckb"): String =
        TranslatedTextCleanup.drawable(text, "", target) ?: ""

    @Test
    fun `the reported aspect bubble loses every latin letter`() {
        val stale = "ئەسپێکت: temple slave — وەسف: a slave is a useless wretch"
        val out = drawn(stale)
        assertFalse("got: $out", KurdishTransliteration.hasLatinLetters(out))
        // the Kurdish part is untouched
        assertTrue(out.contains("ئەسپێکت"))
        assertTrue(out.contains("وەسف"))
    }

    @Test
    fun `a fully latin answer is rescued, not dropped and not drawn in english`() {
        val out = drawn("what the actual fuck is this?!")
        assertTrue(out.isNotBlank())
        assertFalse(KurdishTransliteration.hasLatinLetters(out))
        assertTrue(out.contains("۞"))
    }

    @Test
    fun `digits and punctuation are kept`() {
        val out = drawn("ئاست 3")
        assertTrue(out.contains("3"))
    }

    @Test
    fun `an empty answer draws nothing`() {
        assertEquals(null, TranslatedTextCleanup.drawable("   ", "hello", "ckb"))
    }

    @Test
    fun `a clean kurdish line is untouched`() {
        val clean = "من تۆ دەپارێزم!"
        assertEquals(clean, drawn(clean))
    }

    @Test
    fun `latin targets are not transliterated`() {
        assertEquals("Hello world", drawn("Hello world", target = "en"))
    }

    @Test
    fun `a marked line with latin inside does not count as finished`() {
        assertFalse(KurdishTransliteration.isTransliterated("ئەسپێکت temple slave ۞"))
        assertTrue(KurdishTransliteration.isTransliterated("ئەسپێکت تێمپڵ سڵەیڤ ۞"))
    }
}
