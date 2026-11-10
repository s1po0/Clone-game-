package app.mangascribe.core

import app.mangascribe.translate.Http
import app.mangascribe.translate.KeylessTranslateTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * The reader's screenshot: Kurdish letters with no meaning.
 *
 * Cause: OCR detected the page as Kurdish (one stray ە on the artwork is enough), so the pair sent
 * to the endpoint was ckb→ckb. The endpoint echoes that, the echo is "not translated", the
 * no-Latin rescue writes the English in Kurdish letters — and a real sentence becomes a sound.
 *
 * These tests fail on that path and pass only when the pair is resolved to `auto` before it
 * reaches the endpoint, and when a broken packed answer is retried line by line.
 */
class SamePairTest {

    /** Echoes ckb→ckb exactly like the real endpoint; translates anything else. */
    private class Endpoint : Http {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            urls += url
            val sl = url.substringAfter("sl=").substringBefore("&")
            val tl = url.substringAfter("tl=").substringBefore("&")
            val q = java.net.URLDecoder.decode(url.substringAfter("q="), "UTF-8")
            if (sl == tl) return """[[["$q","$q",null,null,5]]]"""
            // the realistic answer: one segment per line, in Kurdish
            val kurdish = q.split('\n').joinToString("\n") { "کوردی" }
            return """[[["$kurdish","$q",null,null,3]]]"""
        }

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
            get(url, headers)
    }

    @Test
    fun `a kurdish source with a kurdish target is asked as auto`() = runBlocking {
        val http = Endpoint()
        val engine = KeylessTranslateTranslator(http)
        val batch = engine.translate(listOf("ASPECT: TEMPLE SLAVE"), "ckb", "ckb")
        assertEquals(listOf("کوردی"), batch.lines)
        assertTrue("sent ckb→ckb: ${http.urls}", http.urls.none { it.contains("sl=ckb") })
    }

    @Test
    fun `an answered page is never transliterated`() = runBlocking {
        val http = Endpoint()
        val engine = KeylessTranslateTranslator(http)
        val page = listOf("THEY SAY THE SPELL VALUES BALANCE AS MUCH AS CHALLENGE.", "ASPECT: TEMPLE SLAVE")
        val batch = engine.translate(page, "ckb", "ckb")
        assertTrue(
            "a real answer was turned into a sound: ${batch.lines}",
            batch.lines.none { KurdishTransliteration.isTransliterated(it) },
        )
    }

    @Test
    fun `a broken packed answer is retried line by line instead of given up`() = runBlocking {
        // This endpoint collapses a multi-line request into one line: the packer cannot split it,
        // so the translator must ask again per line rather than return the source.
        val http = object : Http {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                val q = java.net.URLDecoder.decode(url.substringAfter("q="), "UTF-8")
                return if (q.contains('\n')) {
                    """[[["کوردی","x",null,null,1]]]"""
                } else {
                    """[[["کوردی","x",null,null,1]]]"""
                }
            }

            override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
                get(url, headers)
        }
        val engine = KeylessTranslateTranslator(http)
        val batch = engine.translate(listOf("one", "two", "three"), "en", "ckb")
        assertEquals(3, batch.lines.count { it.isNotBlank() })
        assertTrue(batch.lines.none { it == "one" || it == "two" || it == "three" })
    }
}
