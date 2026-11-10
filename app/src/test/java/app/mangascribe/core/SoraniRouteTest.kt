package app.mangascribe.core

import app.mangascribe.translate.Http
import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.TranslationBatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sorani on free endpoints, done the way Kurd-man does it: one request at a time, a raw line, and
 * an endpoint that is allowed to answer in Kurdish. The endpoint in these tests is a fake, but it
 * behaves like the real one — it echoes English lines back unless it is asked politely, one at a
 * time, with the target `ckb`.
 */
class SoraniRouteTest {

    /** Translates only when the request carries `tl=ckb` and no more than one is in flight. */
    private class StrictGoogle : Http {
        var inFlight = 0
        var maxInFlight = 0
        val seen = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                seen += url
                val isSorani = url.contains("tl=ckb")
                val query = url.substringAfter("q=").substringBefore("&")
                return if (isSorani) {
                    """[[["کوردی: $query","x",null,null,1]]]"""
                } else {
                    // what a rate-limited/echoing endpoint returns: the line itself
                    """[[["$query","x",null,null,1]]]"""
                }
            } finally {
                inFlight--
            }
        }

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
            get(url, headers)
    }

    @Test
    fun `a kurdish page comes back kurdish, one request at a time`() = runBlocking {
        val http = StrictGoogle()
        val engine = KeylessTranslateTranslator(http, lingvaInstances = emptyList())
        val lines = (1..6).map { "line $it" }
        val batch: TranslationBatch = engine.translate(lines, "en", "ckb")

        assertEquals(1, http.maxInFlight)
        assertTrue(
            "expected Kurdish, got: ${batch.lines}",
            batch.lines.all { line -> line.any { it in '\u0600'..'\u06FF' } },
        )
    }

    @Test
    fun `an endpoint that echoes english is not accepted for sorani`() = runBlocking {
        val http = StrictGoogle()
        val engine = KeylessTranslateTranslator(http, lingvaInstances = emptyList())
        val batch = engine.translate(listOf("what is this"), "en", "ckb")
        // Even the echo path must not leave Latin in a Kurdish line.
        assertTrue(
            "got: ${batch.lines}",
            batch.lines.none { line -> KurdishTransliteration.hasLatinLetters(line) },
        )
    }

    @Test
    fun `the request carries the browser client and the kurdish target`() = runBlocking {
        val http = StrictGoogle()
        KeylessTranslateTranslator(http, lingvaInstances = emptyList())
            .translate(listOf("hello"), "en", "ckb")
        assertTrue(http.seen.isNotEmpty())
        assertTrue(http.seen.any { it.contains("client=gtx") })
        assertTrue(http.seen.any { it.contains("tl=ckb") })
    }
}
