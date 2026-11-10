package app.mangascribe.core

import app.mangascribe.translate.Glossary
import app.mangascribe.translate.TranslationBatch
import app.mangascribe.translate.TranslationPipeline
import app.mangascribe.translate.Translator
import app.mangascribe.translate.overlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glossary and the OCR repair path, both taken from a working Kurdish pipeline
 * (s1po0/Kurd-man) and re-tested here.
 *
 * The glossary's promise: a known line is *stable* — the same Kurdish sentence for the whole
 * chapter, with no request — while everything else is passed through untouched. The repair's
 * promise: a shape-only code point never reaches the translator or the renderer.
 */
class GlossaryTest {

    @Test
    fun `a known line wins over the engine`() {
        val glossary = Glossary.parse("""{"phrases":{"Who are you?!":"تۆ کێیت؟!"}}""")
        assertEquals("تۆ کێیت؟!", glossary.translationFor("Who are you?!"))
    }

    @Test
    fun `matching ignores case and extra whitespace`() {
        val glossary = Glossary.parse("""{"phrases":{"who   are you?!":"تۆ کێیت؟!"}}""")
        assertEquals("تۆ کێیت؟!", glossary.translationFor("  WHO ARE YOU?!  "))
    }

    @Test
    fun `a known line followed by more speech still uses the known part`() {
        val glossary = Glossary.parse("""{"phrases":{"who are you?!":"تۆ کێیت؟!"}}""")
        assertEquals("تۆ کێیت؟! دەرەوە!", glossary.translationFor("Who are you?! دەرەوە!"))
    }

    @Test
    fun `a different sentence is not a hit`() {
        val glossary = Glossary.parse("""{"phrases":{"who are you?!":"تۆ کێیت؟!"}}""")
        assertNull(glossary.translationFor("Where are you?!"))
    }

    @Test
    fun `a blank translation in the file is ignored instead of erasing dialogue`() {
        val glossary = Glossary.parse("""{"phrases":{"Where are you?!":"   "}}""")
        assertTrue(glossary.isEmpty)
    }

    @Test
    fun `broken json degrades to no glossary rather than crashing a chapter`() {
        assertTrue(Glossary.parse("{not json").isEmpty)
        assertTrue(Glossary.parse(null).isEmpty)
    }

    @Test
    fun `the built in kurdish glossary covers the lines every chapter repeats`() {
        val kurdish = Glossary.builtIn("ckb")
        assertEquals("چی؟!", kurdish.translationFor("What?!"))
        assertEquals("ڕابە!", kurdish.translationFor("Run!"))
        // No glossary for other targets: an English phrase list must not rewrite a page.
        assertTrue(Glossary.builtIn("ar").isEmpty)
        assertTrue(Glossary.builtIn("ja").isEmpty)
    }

    @Test
    fun `overlay replaces hits and leaves ordinary lines exactly as translated`() {
        val glossary = Glossary.parse("""{"phrases":{"Run!":"ڕابە!"}}""")
        val source = listOf("Run!", "The bridge is out.")
        val engine = listOf("ڕابەوە", "پردەکە خراوەتەوە")
        assertEquals(
            listOf("ڕابە!", "پردەکە خراوەتەوە"),
            glossary.overlay(source, engine),
        )
    }

    @Test
    fun `a glossary entry also survives a retranslate of an already stored page`() {
        val glossary = Glossary.parse("""{"phrases":{"No way...":"مەحاڵە..."}}""")
        val out = glossary.overlay(listOf("No way..."), listOf("هیچ ڕێگەیەک نییە..."))
        assertEquals(listOf("مەحاڵە..."), out)
    }
}

/** A stand-in engine: records what it was asked and returns a marked string. */
private class EchoEngine : Translator {
    override val id = "echo"
    override val displayName = "Echo"
    override val requiresNetwork = false
    var seen: List<String> = emptyList()
    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatch {
        seen = texts
        return TranslationBatch(texts.map { "EN[$it]" }, id)
    }
}

class GlossaryPipelineTest {

    @Test
    fun `the engine still sees every line, and the glossary replaces only known ones`() = runBlocking {
        val engine = EchoEngine()
        val pipeline = TranslationPipeline(listOf(engine), engine.id)
        val source = listOf("What?!", "The bridge is out.")
        val batch = pipeline.translate(source, "en", "ckb")
        // Batching never changes because a glossary exists: one request, all lines.
        assertEquals(source, engine.seen)
        val lines = Glossary.builtIn("ckb").overlay(source, batch.lines)
        assertEquals(listOf("چی؟!", "EN[The bridge is out.]"), lines)
    }

    @Test
    fun `a page with no glossary hits is untouched`() = runBlocking {
        val engine = EchoEngine()
        val pipeline = TranslationPipeline(listOf(engine), engine.id)
        val source = listOf("The bridge is out.", "Two hours until dawn.")
        val batch = pipeline.translate(source, "en", "ckb")
        assertEquals(batch.lines, Glossary.builtIn("ckb").overlay(source, batch.lines))
    }
}

class OcrJunkTest {

    @Test
    fun `lettering read off the artwork is dropped`() {
        // "PY", "SFX", "GRR" — sound effects and logos, not dialogue. Kurd-man's pipeline
        // refused to typeset exactly this class of fragment onto a character's face.
        listOf("PY", "SFX", "GRR", "HMPH", "ZZZZ").forEach {
            assertTrue("'$it' should be junk", OcrCleanup.isJunk(it))
        }
    }

    @Test
    fun `real short dialogue survives`() {
        listOf("No!", "OK", "OMG", "AHH", "HMM", "Why?", "?!", "Run!").forEach {
            assertFalse("'$it' must not be dropped", OcrCleanup.isJunk(it))
        }
    }

    @Test
    fun `kurdish short words are never treated as artwork`() {
        listOf("نا", "بەڵێ", "چی؟", "ڕابە").forEach {
            assertFalse("'$it' must not be dropped", OcrCleanup.isJunk(it))
        }
    }

    @Test
    fun `japanese and long latin lines are untouched`() {
        assertFalse(OcrCleanup.isJunk("待って！"))
        assertFalse(OcrCleanup.isJunk("The bridge is out."))
        assertTrue(OcrCleanup.isJunk("  "))
        assertTrue(OcrCleanup.isJunk("12"))
    }
}

class OcrRepairTest {

    @Test
    fun `presentation forms are folded back to base letters`() {
        // U+FE91 is the medial form of beh; a translator cannot use it, a shaper can once it
        // is a plain base letter again.
        val broken = "\uFE91\uFEF3"
        val repaired = OcrCleanup.repairPresentationalForms(broken)
        assertEquals("بي", repaired)
        assertFalse(repaired.any { it in '\uFB50'..'\uFEFF' })
    }

    @Test
    fun `private use glyphs are dropped instead of being typeset as empty boxes`() {
        val broken = "من \uE012\uE013 تۆ دەپارێزم"
        assertEquals("من  تۆ دەپارێزم", OcrCleanup.repairPresentationalForms(broken))
    }

    @Test
    fun `ordinary kurdish text is untouched`() {
        val text = "من تۆ دەپارێزم!"
        assertEquals(text, OcrCleanup.repairPresentationalForms(text))
    }

    @Test
    fun `clean applies the repair before kurdish normalisation`() {
        val broken = "\uFE91\uFEF3 \uE010 ك"
        val cleaned = OcrCleanup.clean(broken, "ckb", stripFurigana = false)
        // The shapes are folded to ب ي, the PUA glyph is gone, and the Arabic kaf becomes ک.
        assertEquals("بي  ک", cleaned)
    }
}
