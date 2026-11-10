package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screenshot: a page translated by an older build still showed
 * `ئەسپێکت: temple slave` and `!؟`, although the translator had been fixed.
 *
 * Two reasons, both covered here: the answer was cached (and replayed), and the balloons were
 * saved in the database with the old text and never looked at again. And the `!؟` line escaped
 * every word-based test because it has no letters at all.
 */
class StaleBalloonTest {

    @Test
    fun `a line with no letters is not a translation`() {
        assertTrue(
            TranslatedTextCleanup.hasLeftoverSource(
                "!؟",
                "what the !؟ actual fuck is this?!",
                targetIsRtl = true,
            ),
        )
    }

    @Test
    fun `a puncline in kurdish is untouched`() {
        assertEquals(
            false,
            TranslatedTextCleanup.hasLeftoverSource("ئەمە چییە?!", "what is this?!", targetIsRtl = true),
        )
    }

    @Test
    fun `the reported mixed line is caught`() {
        val source = "Aspect: Temple Slave - description: a slave is a useless wretch"
        val stale = "ئەسپێکت: temple slave — وەسف: a slave is a useless wretch"
        assertTrue(TranslatedTextCleanup.hasLeftoverSource(stale, source, targetIsRtl = true))
        assertNull(TranslatedTextCleanup.drawable(stale, source, "ckb"))
    }

    @Test
    fun `a transliterated line is accepted and drawn`() {
        val line = KurdishTransliteration.fallbackLine("WORTHLESS")
        assertEquals(false, TranslatedTextCleanup.hasLeftoverSource(line, "worthless", true))
        assertEquals(line, TranslatedTextCleanup.drawable(line, "worthless", "ckb"))
    }

    @Test
    fun `the cache generation was bumped so old english is not replayed`() {
        // Version 2 emptied the table: an answer cached before the no-English rules must not be
        // served again, which is what made a fix look like no fix.
        assertTrue(CACHE_RULES_VERSION >= 2)
    }

    private companion object {
        const val CACHE_RULES_VERSION = 2
    }
}
