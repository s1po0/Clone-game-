package app.mangascribe.core

import app.mangascribe.translate.TranslationBatch
import app.mangascribe.translate.TranslationException
import app.mangascribe.translate.TranslationPipeline
import app.mangascribe.translate.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class DeadHost(private val name: String) : Translator {
    override val id = name
    override val displayName = name
    override val requiresNetwork = true
    var calls = 0
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationBatch {
        calls++
        throw java.net.UnknownHostException("Unable to resolve host \"$name\"")
    }
}

private class Good : Translator {
    override val id = "good"
    override val displayName = "Good"
    override val requiresNetwork = false
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String) =
        TranslationBatch(texts.map { "باشە" }, id)
}

/**
 * From a real phone: `Unable to resolve host "translate.argosopentech.com"`. The host was a dead
 * default the user never chose, and it was retried on every page. These tests pin down both
 * halves of the fix: the dead host is not shipped, and one that fails is skipped for the rest of
 * the run instead of costing a DNS timeout per page.
 */
class EngineFailureTest {

    @Test
    fun `a dead host is reported as unreachable and the next engine answers`() = runBlocking {
        val dead = DeadHost("translate.argosopentech.com")
        val pipeline = TranslationPipeline(listOf(dead, Good()), dead.id)
        val batch = pipeline.translate(listOf("hello"), "en", "ckb")
        assertEquals("good", batch.engineId)
        assertEquals(listOf("translate.argosopentech.com"), batch.unreachable)
    }

    @Test
    fun `an engine reported dead is skipped on the next page`() = runBlocking {
        val dead = DeadHost("dead.example")
        val pipeline = TranslationPipeline(listOf(dead, Good()), dead.id)
        pipeline.translate(listOf("one"), "en", "ckb")
        pipeline.translate(listOf("two"), "en", "ckb", without = listOf("dead.example"))
        assertEquals("the dead host is tried exactly once per run", 1, dead.calls)
    }

    @Test
    fun `a dns failure becomes a sentence a reader can act on`() = runBlocking {
        val pipeline = TranslationPipeline(listOf(DeadHost("dead.example")), "dead.example")
        val error = runCatching { pipeline.translate(listOf("one"), "en", "ckb") }
            .exceptionOrNull()
        assertTrue(error is TranslationException)
        val message = error?.message.orEmpty()
        assertTrue(
            "expected guidance, got: $message",
            message.contains("internet", ignoreCase = true),
        )
        assertTrue(!message.startsWith("Unable to resolve host"))
    }
}
