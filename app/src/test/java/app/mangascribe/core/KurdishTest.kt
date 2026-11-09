package app.mangascribe.core

import app.mangascribe.translate.ChatPrompt
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
        assertNull(app.mangascribe.translate.EngineCatalog.kurdishAdvice("ku", hasLlmKey = false))
        assertNotNull(app.mangascribe.translate.EngineCatalog.kurdishAdvice("ckb", hasLlmKey = false))
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

class KurdishEngineTest {

    @Test
    fun `the model endpoint is the reliable kurdish route`() {
        assertTrue(EngineCatalog.supports(EngineCodes.LLM, "en", "ckb"))
        assertTrue(EngineCatalog.supports(EngineCodes.LLM, "ja", "ckb"))
        assertTrue(EngineCatalog.supports(EngineCodes.LLM, "ckb", "en"))
    }

    @Test
    fun `libretranslate has no kurdish and the catalog says so`() {
        assertFalse(EngineCatalog.supports(EngineCodes.LIBRETRANSLATE, "ja", "ckb"))
        // but it is fine for the common pairs
        assertTrue(EngineCatalog.supports(EngineCodes.LIBRETRANSLATE, "ja", "en"))
    }

    @Test
    fun `mymemory advertises kurdish rather than silently dropping it`() {
        assertTrue(EngineCatalog.supports(EngineCodes.MYMEMORY, "en", "ckb"))
        assertFalse(EngineCatalog.supports(EngineCodes.MYMEMORY, "ckb", "ckb"))
    }

    @Test
    fun `capable list puts the preferred engine first`() {
        val list = EngineCatalog.capable(EngineCodes.LLM, "en", "ckb")
        assertEquals(EngineCodes.LLM, list.first().id)
    }

    @Test
    fun `advice names the missing key instead of promising a translation`() {
        val withoutKey = EngineCatalog.kurdishAdvice("ckb", hasLlmKey = false)
        assertNotNull(withoutKey)
        assertTrue(withoutKey!!.contains("model key"))
        val withKey = EngineCatalog.kurdishAdvice("ckb", hasLlmKey = true)
        assertTrue(withKey!!.contains("model endpoint"))
        // no advice noise for a pair that just works
        assertNull(EngineCatalog.kurdishAdvice("en", hasLlmKey = false))
    }

    @Test
    fun `the prompt asks for sorani in its own letters, not kurmanji`() {
        val instruction = ChatPrompt.languageInstruction(Languages.of("ckb"))
        assertTrue(instruction.contains("Sorani"))
        assertTrue(instruction.contains("پ ژ گ چ"))
        // the prompt must rule out Kurmanji explicitly
        assertTrue(instruction.contains("never"))
    }

    @Test
    fun `kurdish source is named the same way in the other direction`() {
        val instruction = ChatPrompt.languageInstruction(Languages.of("ckb"))
        val prompt = ChatPrompt.build(instruction, "English (English)", listOf("سڵاو"))
        assertTrue(prompt.contains("1. سڵاو"))
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
