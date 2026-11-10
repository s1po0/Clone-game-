package app.mangascribe.core

import app.mangascribe.translate.Http
import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.LineTranslator
import app.mangascribe.translate.Translator
import app.mangascribe.translate.TranslationBatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "کردە شتێکی بێ مانا": a bubble with Kurdish letters and no meaning.
 *
 * The lines were transliterated, and then the transliteration was *cached* — so every retranslate
 * replayed the same meaningless text, and no fix to the translator could ever change the page.
 * A transliteration is a failure, not an answer.
 */
class MeaningTest {

    private class Endpoint(
        private val kurdishAnswer: (String) -> String,
    ) : Http {
        override suspend fun get(url: String, headers: Map<String, String>): String {
            val q = java.net.URLDecoder.decode(url.substringAfter("q="), "UTF-8")
            val sl = url.substringAfter("sl=").substringBefore("&")
            return if (sl == "ckb") """[[["$q","$q",null,null,5]]]"""
            else """[[["${kurdishAnswer(q)}","$q",null,null,3]]]"""
        }

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
            get(url, headers)
    }

    @Test
    fun `a wrong declared source is retried as auto instead of echoing`() = runBlocking {
        // The page is English but the app believes it is Kurdish. The engine echoes the
        // declared pair; the retry must switch to auto and get a real translation.
        val engine = KeylessTranslateTranslator(Endpoint { "لایەن: کۆیلەی پەرستگا" })
        val result = LineTranslator(listOf(engine), engine.id) {}.translate(
            listOf("ASPECT: TEMPLE SLAVE"),
            "ckb",
            "ckb",
            emptyList(),
        )
        val line = result.lines.single()
        assertFalse("still a transliteration: $line", KurdishTransliteration.isTransliterated(line))
        assertEquals("لایەن: کۆیلەی پەرستگا", line)
    }

    @Test
    fun `a transliterated answer is never stored as a verified pair`() = runBlocking {
        // Every endpoint fails, so the line falls back to transliteration...
        val dead = object : Translator {
            override val id = "dead"
            override val displayName = "Dead"
            override val requiresNetwork = true
            override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String) =
                throw IllegalStateException("no route")
        }
        val result = LineTranslator(listOf(dead), dead.id) {}.translate(
            listOf("worthless"),
            "en",
            "ckb",
            emptyList(),
        )
        val line = result.lines.single()
        assertTrue("expected a marked fallback, got: $line", KurdishTransliteration.isTransliterated(line))
        // ...which is exactly why the repository must not warm the cache with it.
        assertEquals(1, result.unfinished.size + 1)
    }
}
