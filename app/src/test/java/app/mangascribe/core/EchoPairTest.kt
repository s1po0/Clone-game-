package app.mangascribe.core

import app.mangascribe.translate.Http
import app.mangascribe.translate.KeylessTranslateTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why Arabic worked and Sorani did not.
 *
 * The endpoint has one silent failure mode: when the source and target languages are the same it
 * returns the input unchanged, with HTTP 200 and no error. An Arabic page has a real pair (ar→ckb)
 * so it always translated; a page the app *believed* was Kurdish was sent as ckb→ckb and came
 * back as English — an untranslated page that looked like a translation failure.
 */
class EchoPairTest {

    /** Behaves like the real endpoint: same language in and out means "return the text". */
    private class EchoAwareGoogle : Http {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            urls += url
            val sl = url.substringAfter("sl=").substringBefore("&")
            val tl = url.substringAfter("tl=").substringBefore("&")
            val query = url.substringAfter("q=").substringBefore("&")
            if (sl == tl) {
                // exactly what Google does: the source text, unchanged
                return """[[["$query","$query",null,null,5]]]"""
            }
            return """[[["کوردی","$query",null,null,3]]]"""
        }

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
            get(url, headers)
    }

    @Test
    fun `a kurdish-to-kurdish pair is sent as auto instead of echoing`() = runBlocking {
        val http = EchoAwareGoogle()
        val engine = KeylessTranslateTranslator(http, lingvaInstances = emptyList())
        val batch = engine.translate(listOf("what is this?"), "ckb", "ckb")
        assertTrue("expected the target language, got ${batch.lines}", batch.lines.all { it == "کوردی" })
        assertTrue(http.urls.none { it.contains("sl=ckb") })
    }

    @Test
    fun `an arabic page still translates normally`() = runBlocking {
        val http = EchoAwareGoogle()
        val engine = KeylessTranslateTranslator(http, lingvaInstances = emptyList())
        val batch = engine.translate(listOf("ما هذا؟"), "ar", "ckb")
        assertEquals(listOf("کوردی"), batch.lines)
    }

    @Test
    fun `an english page keeps its declared source`() = runBlocking {
        val http = EchoAwareGoogle()
        KeylessTranslateTranslator(http, lingvaInstances = emptyList())
            .translate(listOf("hello"), "en", "ckb")
        assertTrue(http.urls.any { it.contains("sl=en") })
    }

    @Test
    fun `a mostly latin page is detected as english, not kurdish`() {
        // The screenshot's status box, with one Kurdish letter in it.
        val box = "Aspect: Temple Slave - a slave is a useless wretch with no notable skills ە"
        assertEquals("en", Languages.detect(box))
    }

    @Test
    fun `real kurdish text is still detected as kurdish`() {
        assertEquals("ckb", Languages.detect("من تۆ دەپارێزم، ڕابە"))
    }
}
