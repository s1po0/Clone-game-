package app.mangascribe.core

import app.mangascribe.translate.EngineCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kurdish (Sorani) is the reason most of the language plumbing exists, so it gets its own
 * tests: detection against Arabic and Persian, OCR letter repair, translation routing, and
 * the RTL typesetting rules.
 */
class KurdishLanguageTest {

    private val ckb = Languages.of("ckb")

    @Test
    fun `kurdish is recognised as its own script, not generic arabic`() {
        assertEquals(Script.KURDISH, ckb.script)
        assertTrue(ckb.rtl)
        assertEquals("کوردیی سۆرانی", ckb.nativeName)
    }

    @Test
    fun `kurdish is detected before arabic on a real sentence`() {
        val sentence = "من دەمەوێت بچم بۆ کوردستان"
        assertEquals("ckb", Languages.detect(sentence))
    }

    @Test
    fun `arabic is not mistaken for kurdish`() {
        assertEquals("ar", Languages.detect("مرحبا بكم في عالم القصص المصورة"))
    }

    @Test
    fun `persian with only shared letters is not called kurdish`() {
        // Persian has چ پ ژ گ but not ۆ ێ ە ڵ — one shared letter is not enough.
        assertEquals("ar", Languages.detect("سلام دنیا"))
    }

    @Test
    fun `kurmanji is its own language, not sorani`() {
        val ku = Languages.of("ku")
        assertEquals(Script.LATIN, ku.script)
        assertFalse(ku.rtl)
        // Sorani rules must not leak into Kurmanji
        assertEquals("ku", Languages.detect("Ez ji te hez dikim û tu jî dizanî"))
    }

    @Test
    fun `sorani advice does not fire for kurmanji`() {
        assertNull(app.mangascribe.translate.EngineCatalog.kurdishAdvice("ku"))
        assertNotNull(app.mangascribe.translate.EngineCatalog.kurdishAdvice("ckb"))
    }

    @Test
    fun `kurdish ocr model exists and brings latin along`() {
        assertEquals("arabic", ckb.ocrModel)
        assertEquals(listOf("arabic", "latin"), Languages.ocrModelsFor("ckb"))
    }

    @Test
    fun `arabic ocr output is repaired into kurdish letters`() {
        // Arabic kaf, yeh and heh doachashmee as an Arabic model would return them
        val broken = "كتێبى من لة ھەموو كوردستان گەورەترە"
        val fixed = OcrCleanup.normaliseKurdish(broken)
        assertFalse(fixed.contains('ك'))
        assertFalse(fixed.contains('ي'))
        assertFalse(fixed.contains('ھ'))
        assertTrue(fixed.contains('ک'))
        assertTrue(fixed.contains('ی'))
        assertTrue(fixed.contains('ه'))
    }

    @Test
    fun `tatweel padding from ocr is removed`() {
        assertEquals("سڵاو", OcrCleanup.normaliseKurdish("ســـڵاو"))
    }

    @Test
    fun `latin runs in kurdish text are isolated so they keep their order`() {
        val mixed = "ناوم Goku ه"
        val isolated = OcrCleanup.isolateLatinRuns(mixed)
        assertTrue(isolated.contains('\u2067'))
        assertTrue(isolated.contains('\u2069'))
    }

    @Test
    fun `clean applies kurdish repair only for kurdish`() {
        val raw = "كتێب"
        assertEquals("کتێب", OcrCleanup.clean(raw, "ckb", stripFurigana = true))
        // Arabic is left alone: ك is a correct Arabic letter and must not be "fixed"
        assertEquals("كتێب", OcrCleanup.clean(raw, "ar", stripFurigana = false))
    }

    @Test
    fun `clean does not strip furigana logic from kurdish`() {
        val text = "کتێبی من"
        assertEquals(text, OcrCleanup.stripFurigana(text))
    }
}

/**
 * Google is the only engine. These assertions used to be a table of which engine could reach
 * Sorani; every row but Google's is now a known dead end, so the test asserts the removal
 * instead of the matrix — a route that is gone cannot quietly come back.
 */
class KurdishEngineTest {

    @Test
    fun `google reaches sorani in both directions`() {
        assertTrue(EngineCatalog.supports(EngineCodes.KEYLESS, "en", "ckb"))
        assertTrue(EngineCatalog.supports(EngineCodes.KEYLESS, "ja", "ckb"))
        assertTrue(EngineCatalog.supports(EngineCodes.KEYLESS, "ckb", "en"))
    }

    @Test
    fun `the catalog lists one engine and it is the keyless google route`() {
        assertEquals(1, EngineCatalog.all.size)
        assertEquals(EngineCodes.KEYLESS, EngineCatalog.all.single().id)
    }

    @Test
    fun `the removed engines are not offered for any pair`() {
        assertFalse(EngineCatalog.supports(EngineCodes.LIBRETRANSLATE, "ja", "en"))
        assertFalse(EngineCatalog.supports(EngineCodes.MYMEMORY, "en", "ckb"))
        assertFalse(EngineCatalog.supports(EngineCodes.LLM, "en", "ckb"))
        assertFalse(EngineCatalog.supports(EngineCodes.MICROSOFT, "en", "ckb"))
    }

    @Test
    fun `advice promises nothing that needs a key`() {
        val advice = EngineCatalog.kurdishAdvice("ckb")
        assertNotNull(advice)
        assertTrue(advice!!.contains("Google"))
        assertFalse(advice.contains("model key"))
        assertFalse(advice.contains("API key"))
        // no advice noise for a pair that just works
        assertNull(EngineCatalog.kurdishAdvice("en"))
    }
}

class KurdishTypesetTest {

    private val mono = object : Measurer {
        override fun width(text: String, sizePx: Float): Float = text.length * sizePx * 0.6f
        override fun lineHeight(sizePx: Float): Float = sizePx * 1.2f
    }

    @Test
    fun `rtl wrap keeps whole words together`() {
        val lines = RtlTypeset.wrap("من دەمەوێت بچم بۆ کوردستان", 20 * 10f * 0.6f, 10f, mono)
        assertTrue(lines.size > 1)
        lines.forEach { line -> assertFalse(line.startsWith(" ")) }
    }

    @Test
    fun `a whole word that fits is never split`() {
        val lines = RtlTypeset.wrap("کوردستان", 20 * 10f * 0.6f, 10f, mono)
        assertEquals(listOf("کوردستان"), lines)
    }

    @Test
    fun `word breaking prefers a non-joining letter when the word cannot fit`() {
        // 3 characters fit; "دارستان" must break after the alef (non-joining), yielding "دا"
        val lines = RtlTypeset.wrap("دارستان", 3 * 10f * 0.6f, 10f, mono)
        assertTrue(lines.size > 1)
        assertEquals("دا", lines.first())
    }

    @Test
    fun `latin words inside kurdish do not break`() {
        assertTrue(RtlTypeset.mustNotBreakBetween('G', 'o'))
        assertTrue(RtlTypeset.mustNotBreakBetween('ک', 'و'))
    }

    @Test
    fun `kashida is only added when there is real slack`() {
        assertEquals(0, RtlTypeset.kashidaCount(100f, 110f, 20f))
        assertTrue(RtlTypeset.kashidaCount(100f, 300f, 20f) > 0)
    }

    @Test
    fun `punctuation spacing is normalised for arabic script`() {
        val normalised = RtlTypeset.normalisePunctuation("سڵاو ،  چۆنی؟")
        assertFalse(normalised.contains(" ، "))
        assertTrue(normalised.contains("،"))
    }
}
