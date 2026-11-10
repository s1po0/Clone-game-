package app.mangascribe.core

import app.mangascribe.translate.TranslationBatch
import app.mangascribe.translate.TranslationPipeline
import app.mangascribe.translate.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class Recording : Translator {
    override val id = "rec"
    override val displayName = "Recording"
    override val requiresNetwork = false
    var seen: List<String> = emptyList()
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationBatch {
        seen = texts
        return TranslationBatch(texts.map { "T($it)" }, id)
    }
}

/**
 * Repeated bubbles on one page are the norm in comics — the same shout twice, the same system
 * label on every page. Kurd-man's `translate_many` dedupes them before spending a request; the
 * mapping back to balloon order is the part that must not slip.
 */
class DedupeTest {

    @Test
    fun `a repeated line is sent once and mapped back to every balloon`() = runBlocking {
        val engine = Recording()
        val pipeline = TranslationPipeline(listOf(engine), engine.id)
        val page = listOf("What?!", "Run!", "What?!")

        val unique = page.distinct()
        val batch = pipeline.translate(unique, "en", "ckb")
        assertEquals(listOf("What?!", "Run!"), engine.seen)

        val index = unique.withIndex().associate { (i, t) -> t to i }
        val lines = page.map { batch.lines[index.getValue(it)] }
        assertEquals(listOf("T(What?!)", "T(Run!)", "T(What?!)"), lines)
    }

    @Test
    fun `a page with no repeats sends every line`() = runBlocking {
        val engine = Recording()
        val pipeline = TranslationPipeline(listOf(engine), engine.id)
        val page = listOf("One", "Two", "Three")
        pipeline.translate(page, "en", "ckb")
        assertEquals(page, engine.seen)
    }
}
