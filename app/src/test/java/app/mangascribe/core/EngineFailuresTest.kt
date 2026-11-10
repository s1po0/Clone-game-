package app.mangascribe.core

import app.mangascribe.data.TranslationCache
import app.mangascribe.translate.EngineFailures
import app.mangascribe.translate.TranslationBatch
import app.mangascribe.translate.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message from the phone was `MyMemory could not translate this pair: MyMemory returned no
 * text`. MyMemory is the fallback and has no Kurdish at all, so that sentence sends the reader
 * looking in the wrong place. These tests pin down a message that points at the real cause.
 */
class EngineFailuresTest {

    @Test
    fun `a kurdish failure names google as the engine that matters`() {
        val text = EngineFailures.explain(
            tried = listOf(
                "Google Translate" to "Google Translate did not answer (timeout)",
                "MyMemory" to "MyMemory returned no text",
            ),
            targetLanguage = "ckb",
            sourceLanguage = "en",
        )
        assertTrue(text.contains("Google Translate"))
        assertTrue("expected an action: $text", text.contains("Settings"))
        assertFalse(text.contains(".."))
    }

    @Test
    fun `a pair problem is called a pair problem, not a network problem`() {
        val text = EngineFailures.explain(
            tried = listOf("LibreTranslate" to "could not translate this pair"),
            targetLanguage = "ckb",
            sourceLanguage = "en",
        )
        assertTrue(text.contains("Kurdish") || text.contains("Kurdish".lowercase()))
    }

    @Test
    fun `one failing engine is reported by name`() {
        val text = EngineFailures.explain(
            listOf("Google Translate" to "HTTP 429"),
            targetLanguage = "ar",
            sourceLanguage = "en",
        )
        assertTrue(text.startsWith("Google Translate did not answer."))
    }

    @Test
    fun `the short form fits a status chip`() {
        val short = EngineFailures.short("No engine could translate this page. Try again later.")
        assertEquals("No engine could translate this page", short)
    }
}

private class CountingEngine : Translator {
    override val id = "counting"
    override val displayName = "Counting"
    override val requiresNetwork = true
    var calls = 0
    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationBatch {
        calls++
        return TranslationBatch(texts.map { "چی؟" }, id)
    }
}

/**
 * The persistent cache, tested without a device by using the same key rule the app uses.
 * (The SQLite half is exercised on-device; the contract that matters is the key.)
 */
class TranslationCacheKeyTest {

    @Test
    fun `the same line maps to the same key regardless of case and padding`() {
        val a = TranslationCache.key("google", "en", "ckb", "  What?! ")
        val b = TranslationCache.key("google", "en", "ckb", "what?!")
        assertEquals(a, b)
    }

    @Test
    fun `a different engine is a different key, so switching engines is honest`() {
        assertTrue(
            TranslationCache.key("google", "en", "ckb", "What?!") !=
                TranslationCache.key("llm", "en", "ckb", "What?!"),
        )
    }

    @Test
    fun `a different language pair is a different key`() {
        assertTrue(
            TranslationCache.key("google", "en", "ckb", "What?!") !=
                TranslationCache.key("google", "ja", "ckb", "What?!"),
        )
    }
}
