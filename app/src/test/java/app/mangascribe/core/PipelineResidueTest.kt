package app.mangascribe.core

import app.mangascribe.translate.TranslationBatch
import app.mangascribe.translate.TranslationPipeline
import app.mangascribe.translate.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An engine that echoes the source, the way a free endpoint does when it has no pair. */
private class EchoSource : Translator {
    override val id = "echo-source"
    override val displayName = "Echo source"
    override val requiresNetwork = false
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String) =
        TranslationBatch(texts.map { it.uppercase() }, id)
}

private class RealKurdish : Translator {
    override val id = "real"
    override val displayName = "Real"
    override val requiresNetwork = false
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String) =
        TranslationBatch(texts.map { "چی؟!" }, id)
}

/**
 * From the user's page: `WORTHLESS.` was drawn inside a Kurdish bubble. An engine that returns
 * the source has not translated, so the chain must try the next engine instead of accepting it.
 */
class PipelineResidueTest {

    @Test
    fun `an engine that echoes the source is skipped in favour of one that translates`() = runBlocking {
        val pipeline = TranslationPipeline(listOf(EchoSource(), RealKurdish()), "echo-source")
        val batch = pipeline.translate(listOf("Worthless."), "en", "ckb")
        assertEquals(listOf("چی؟!"), batch.lines)
        assertEquals("real", batch.engineId)
    }

    @Test
    fun `if every engine echoes, the page keeps the text instead of failing`() = runBlocking {
        val pipeline = TranslationPipeline(listOf(EchoSource()), "echo-source")
        val batch = pipeline.translate(listOf("Worthless."), "en", "ckb")
        // The renderer refuses to paint this into a bubble; the pipeline does not throw, so a
        // page with real art and one bad line is still readable.
        assertTrue(TranslatedTextCleanup.hasLatinResidue(batch.lines.first(), true))
    }

    @Test
    fun `prose lines are never flagged as residue`() = runBlocking {
        val pipeline = TranslationPipeline(listOf(RealKurdish()), "real")
        val batch = pipeline.translate(listOf("They say the spell values balance."), "en", "ckb")
        assertEquals(listOf("چی؟!"), batch.lines)
    }
}

class NameGlossaryTest {

    private val glossary = app.mangascribe.translate.Glossary.builtIn("ckb")

    @Test
    fun `a system label is transliterated, not translated as a sentence`() {
        assertEquals("ئەسپێکت", glossary.translationFor("Aspect"))
        assertEquals("کۆیلەی پەرستگا", glossary.translationFor("Temple Slave"))
        assertEquals("شادۆ سلەیڤ", glossary.translationFor("Shadow Slave"))
    }

    @Test
    fun `a name keeps one spelling wherever it appears`() {
        assertEquals(
            "شادۆ سلەیڤ",
            glossary.translationFor("SHADOW SLAVE"),
        )
    }

    @Test
    fun `the same name in japanese or korean still lands on the kurdish spelling`() {
        assertEquals("شادۆ سلەیڤ", glossary.translationFor("影の奴隷"))
        assertEquals("شادۆ سلەیڤ", glossary.translationFor("그림자 노예"))
    }

    @Test
    fun `ordinary dialogue is not swallowed by the name table`() {
        assertEquals(null, glossary.translationFor("The bridge is out and the river is rising."))
    }
}
