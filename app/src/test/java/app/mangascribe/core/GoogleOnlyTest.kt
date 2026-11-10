package app.mangascribe.core

import app.mangascribe.translate.EngineCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One engine, on purpose.
 *
 * Every other route was measured against its real endpoint and failed: Microsoft refuses `ckb`
 * with HTTP 400 (its `ku` is Central Kurdish, but the keyless endpoint now only answers the
 * enterprise token flow), MyMemory has no Sorani, every public Lingva mirror rejects the
 * language code, and no published LibreTranslate model set contains Kurdish. The ones that did
 * "answer" echoed the source — which is how English ended up inside Kurdish bubbles.
 *
 * So the rotation is Google's three endpoints and nothing else, and this test is the fence that
 * keeps a dead engine from being wired back in by a helpful-looking patch.
 */
class GoogleOnlyTest {

    @Test
    fun `the catalog offers exactly one engine`() {
        assertEquals(1, EngineCatalog.all.size)
        assertEquals(EngineCodes.KEYLESS, EngineCatalog.all.single().id)
    }

    @Test
    fun `sorani still maps to the code google accepts`() {
        assertEquals("ckb", Languages.of("ckb").codeFor(EngineCodes.GOOGLE))
        assertEquals("ckb", Languages.of("ckb").codeFor(EngineCodes.KEYLESS))
        assertTrue(EngineCatalog.supports(EngineCodes.KEYLESS, "en", "ckb"))
    }

    @Test
    fun `a dead engine cannot answer any pair`() {
        assertFalse(EngineCatalog.supports(EngineCodes.MICROSOFT, "en", "ckb"))
        assertFalse(EngineCatalog.supports(EngineCodes.MYMEMORY, "en", "ckb"))
        assertFalse(EngineCatalog.supports(EngineCodes.LIBRETRANSLATE, "en", "ckb"))
        assertFalse(EngineCatalog.supports(EngineCodes.LLM, "en", "ckb"))
    }
}
