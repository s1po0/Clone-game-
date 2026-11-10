package app.mangascribe.core

import app.mangascribe.translate.EngineCatalog
import app.mangascribe.translate.GoogleCodes
import app.mangascribe.translate.GoogleResponseParser
import app.mangascribe.translate.GoogleTranslateTranslator
import app.mangascribe.translate.Http
import app.mangascribe.translate.TranslationException
import app.mangascribe.translate.TranslationPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The response is a nested array, so the parser is where a working engine is won or lost. */
class GoogleResponseParserTest {

    @Test
    fun `parses a single segment`() {
        val json = """[[["これから行くのか？","Are you going?",null,null,10]],null,"ja"]"""
        assertEquals("これから行くのか？", GoogleResponseParser.parse(json))
    }

    @Test
    fun `joins every segment instead of keeping the first`() {
        val json = """[[["Hello there. ","a",null,null,10],["How are you?","b",null,null,10]],null,"en"]"""
        assertEquals("Hello there. How are you?", GoogleResponseParser.parse(json))
    }

    @Test
    fun `unescapes html entities Google leaves in the text`() {
        val json = """[[["He said &quot;stop&quot; &amp; left","x",null,null,10]],null,"en"]"""
        assertEquals("He said \"stop\" & left", GoogleResponseParser.parse(json))
    }

    @Test
    fun `a kurdish translation comes back in its own letters`() {
        val json = """[[["دەمەوێت بچم","I want to go",null,null,10]],null,"en"]"""
        assertEquals("دەمەوێت بچم", GoogleResponseParser.parse(json))
    }

    @Test
    fun `an error page is not a translation`() {
        assertNull(GoogleResponseParser.parse("<html>sorry</html>"))
        assertNull(GoogleResponseParser.parse(""))
        assertNull(GoogleResponseParser.parse("[]"))
    }

    @Test
    fun `blank segments are dropped`() {
        val segments = GoogleResponseParser.parseSegments("""[[["a","x"],["", "y"],["b","z"]]]""")
        assertEquals(listOf("a", "b"), segments)
    }
}

class GoogleCodesTest {

    @Test
    fun `sorani keeps its own code`() {
        assertEquals("ckb", GoogleCodes.forLanguage(Languages.of("ckb")))
    }

    @Test
    fun `kurmanji stays distinct from sorani`() {
        assertEquals("ku", GoogleCodes.forLanguage(Languages.of("ku")))
        // and the language table keeps them apart too
        assertTrue(EngineCodes.isKurdish("ckb") && EngineCodes.isKurdish("ku"))
        assertTrue(EngineCodes.isSorani("ckb"))
        assertFalse(EngineCodes.isSorani("ku"))
    }

    @Test
    fun `chinese scripts map to google's regional codes`() {
        assertEquals("zh-CN", GoogleCodes.forLanguage(Languages.of("zh-Hans")))
        assertEquals("zh-TW", GoogleCodes.forLanguage(Languages.of("zh-Hant")))
    }

    @Test
    fun `plain languages fall through to their own code`() {
        assertNull(GoogleCodes.forLanguage(Languages.of("ja")))
        assertNull(GoogleCodes.forLanguage(Languages.of("en")))
    }

    @Test
    fun `google is offered for every pair the app shows, kurdish included`() {
        assertTrue(EngineCatalog.supports(EngineCodes.GOOGLE, "ja", "ckb"))
        assertTrue(EngineCatalog.supports(EngineCodes.GOOGLE, "ckb", "en"))
        assertTrue(EngineCatalog.supports(EngineCodes.GOOGLE, "ja", "en"))
        // and it leads the capable list by default
        assertEquals(EngineCodes.GOOGLE, EngineCatalog.capable(EngineCodes.GOOGLE, "ja", "ckb").first().id)
    }
}

private class FakeHttp(private val answer: (String) -> String) : Http {
    var calls = 0
    override suspend fun get(url: String, headers: Map<String, String>): String {
        calls++
        return answer(url)
    }
    override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
        answer(body)
}

class GoogleEngineBehaviourTest {

    private val ok = """[[["translated","src",null,null,10]],null,"ja"]"""

    @Test
    fun `translates every line and keeps the order`() = runBlocking {
        val http = FakeHttp { ok }
        val engine = GoogleTranslateTranslator(http)
        val batch = engine.translate(listOf("a", "b", "c"), "ja", "en")
        assertEquals(3, batch.lines.size)
        assertTrue(batch.lines.all { it == "translated" })
        assertEquals(3, http.calls)
    }

    @Test
    fun `blank lines are not sent and stay blank`() = runBlocking {
        val http = FakeHttp { ok }
        val engine = GoogleTranslateTranslator(http)
        val batch = engine.translate(listOf("a", "   ", "c"), "ja", "en")
        assertEquals("   ", batch.lines[1])
        assertEquals(2, http.calls)
    }

    @Test
    fun `a repeated line is cached and not requested twice`() = runBlocking {
        val http = FakeHttp { ok }
        val engine = GoogleTranslateTranslator(http)
        engine.translate(listOf("同じ", "同じ"), "ja", "en")
        assertEquals(1, http.calls)
    }

    @Test
    fun `an engine that returns nothing throws so the next one is tried`() = runBlocking {
        val http = FakeHttp { "not json" }
        val engine = GoogleTranslateTranslator(http)
        var threw = false
        try {
            engine.translate(listOf("hello"), "ja", "en")
        } catch (t: TranslationException) {
            threw = true
            assertTrue(t.message!!.contains("did not answer"))
        }
        assertTrue(threw)
    }

    @Test
    fun `a 429 is retried before giving up`() = runBlocking {
        var attempts = 0
        val http = FakeHttp {
            attempts++
            if (attempts < 3) throw TranslationException("HTTP 429: rate limited") else ok
        }
        val engine = GoogleTranslateTranslator(http)
        val batch = engine.translate(listOf("once"), "ja", "en")
        assertEquals("translated", batch.lines[0])
        assertEquals(3, attempts)
    }

    @Test
    fun `the pipeline falls through google to another engine`() = runBlocking {
        val google = GoogleTranslateTranslator(FakeHttp { "broken" })
        val fallback = object : app.mangascribe.translate.Translator {
            override val id = "fallback"
            override val displayName = "fallback"
            override val requiresNetwork = true
            override suspend fun translate(
                texts: List<String>, sourceLanguage: String, targetLanguage: String,
            ): app.mangascribe.translate.TranslationBatch =
                app.mangascribe.translate.TranslationBatch(texts.map { "fallback:$it" }, id)
        }
        val pipeline = TranslationPipeline(listOf(google, fallback), EngineCodes.GOOGLE)
        val batch = pipeline.translate(listOf("line"), "ja", "en")
        assertEquals("fallback:line", batch.lines.first())
    }
}
