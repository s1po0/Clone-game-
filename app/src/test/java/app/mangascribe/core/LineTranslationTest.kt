package app.mangascribe.core

import app.mangascribe.translate.Glossary
import app.mangascribe.translate.LineTranslator
import app.mangascribe.translate.TranslationBatch
import app.mangascribe.translate.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From the screenshot: `ئەسپێکت: temple slave` and `description: a slave is a useless wretch…`.
 * The engine had translated part of each line and left English inside it, and the glossary could
 * not help because it only matched whole lines.
 */
class LeftoverSourceTest {

    @Test
    fun `english words that were in the source and are still there are leftovers`() {
        assertTrue(
            TranslatedTextCleanup.hasLeftoverSource(
                "ئەسپێکت: temple slave",
                "ASPECT: TEMPLE SLAVE",
                targetIsRtl = true,
            )
        )
        assertTrue(
            TranslatedTextCleanup.hasLeftoverSource(
                "description: a slave is a useless wretch with no notable skills or abilities",
                "Aspect description: a slave is a useless wretch with no notable skills or abilities",
                targetIsRtl = true,
            )
        )
    }

    @Test
    fun `a fully kurdish line has nothing left over even with a latin name in it`() {
        assertFalse(
            TranslatedTextCleanup.hasLeftoverSource(
                "ئەسپێکت: کۆیلەی پەرستگا",
                "Aspect: Temple Slave",
                targetIsRtl = true,
            )
        )
        // A name the engine transliterated back is not a leftover: it is not a source word.
        assertFalse(
            TranslatedTextCleanup.hasLeftoverSource("گۆکو دەگەڕێتەوە", "Goku returns", true)
        )
    }

    @Test
    fun `latin targets are never inspected`() {
        assertFalse(TranslatedTextCleanup.hasLeftoverSource("Hello world", "Hello world", false))
    }

    @Test
    fun `a half english line is not drawn at all`() {
        assertNull(
            TranslatedTextCleanup.drawable(
                "ئەسپێکت: temple slave",
                "Aspect: Temple Slave",
                "ckb",
            )
        )
    }
}

class GlossaryInsideLineTest {

    private val glossary = Glossary.builtIn("ckb")

    @Test
    fun `a label and a term around a colon are answered without an engine`() {
        assertEquals("ئەسپێکت: کۆیلەی پەرستگا", glossary.translationFor("Aspect: Temple Slave"))
    }

    @Test
    fun `terms are rewritten inside a longer sentence`() {
        val rewrite = glossary.rewriteTermsInside(
            "Aspect: Temple Slave. A slave is a useless wretch.",
        )
        assertTrue(rewrite.changed)
        assertTrue(rewrite.text.contains("کۆیلەی پەرستگا"))
        assertFalse(rewrite.text.contains("Temple Slave"))
    }

    @Test
    fun `a rewrite never edits inside a word`() {
        val rewrite = glossary.rewriteTermsInside("Backgrounds and rankers")
        assertFalse(rewrite.text.contains("پلە"))
    }
}

/** An engine that translates the first half of a line and returns the rest untouched. */
private class HalfEngine : Translator {
    override val id = "half"
    override val displayName = "Half"
    override val requiresNetwork = false
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String) =
        TranslationBatch(texts.map { "کوردی ${it.substringAfterLast(' ')}" }, id)
}

private class WholeEngine : Translator {
    override val id = "whole"
    override val displayName = "Whole"
    override val requiresNetwork = false
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String) =
        TranslationBatch(texts.map { "هەموو دێرەکە وەرگێڕدرا" }, id)
}

/**
 * A line with source words left in it is asked again, on its own, through the other engines —
 * twice — and only then reported as unfinished.
 */
class LineTranslatorTest {

    private val source = "Aspect description: a slave is a useless wretch"

    @Test
    fun `a partial answer is retried and the good engine wins`() = runBlocking {
        val translator = LineTranslator(listOf(HalfEngine(), WholeEngine()), "half")
        val result = translator.translate(listOf(source), "en", "ckb")
        assertEquals(listOf("هەموو دێرەکە وەرگێڕدرا"), result.lines)
        assertEquals("whole", result.engineId)
        assertTrue(result.unfinished.isEmpty())
    }

    @Test
    fun `a line nobody can finish is reported, not silently kept`() = runBlocking {
        val translator = LineTranslator(listOf(HalfEngine()), "half")
        val result = translator.translate(listOf(source), "en", "ckb")
        assertTrue(result.unfinished.isNotEmpty())
    }

    @Test
    fun `an ordinary sentence is not retried`() = runBlocking {
        class Counted : Translator {
            override val id = "counted"
            override val displayName = "Counted"
            override val requiresNetwork = false
            var calls = 0
            override suspend fun translate(texts: List<String>, s: String, t: String): TranslationBatch {
                calls++
                return TranslationBatch(texts.map { "باشە" }, id)
            }
        }
        val engine = Counted()
        LineTranslator(listOf(engine), engine.id).translate(listOf("The bridge is out."), "en", "ckb")
        assertEquals("one batch only", 1, engine.calls)
    }
}
