package app.mangascribe.core

import app.mangascribe.translate.HostHealth
import app.mangascribe.translate.Http
import app.mangascribe.translate.KeylessParsers
import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.TranslationPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyless rotation.
 *
 * The failure that motivated it: a ten-page chapter exhausted the single Google endpoint, and the
 * app had nothing else for Kurdish — MyMemory and LibreTranslate have no Kurdish, so the page
 * ended with a message about the wrong engine. These tests pin the behaviour that fixes it: try
 * the next host, remember a dead or parked one, never treat a captcha page as a translation.
 */
class KeylessParsersTest {

    @Test
    fun `google web shape is joined from its segments`() {
        val body = """[[["ڕابە!","Run!",null,null,10],["ئێستا","now",null,null,3]],null,"en"]"""
        assertEquals("ڕابە!ئێستا", KeylessParsers.parseClients5(body))
    }

    @Test
    fun `the dictionary endpoint answers with sentences`() {
        val body = """{"sentences":[{"trans":"چی؟!","orig":"What?!"}],"src":"en"}"""
        assertEquals("چی؟!", KeylessParsers.parseClients5(body))
    }

    @Test
    fun `the dictionary endpoint can also answer with a flat pair list`() {
        assertEquals("ڕابە!", KeylessParsers.parseClients5("""[["ڕابە!","en"]]"""))
    }

    @Test
    fun `a captcha or consent page is recognised instead of being read as text`() {
        assertTrue(KeylessParsers.looksLikeHtml("<!DOCTYPE html><html>…"))
        assertTrue(KeylessParsers.looksLikeHtml("  <html><body>captcha</body></html>"))
        assertFalse(KeylessParsers.looksLikeHtml("""{"translation":"x"}"""))
        assertFalse(KeylessParsers.looksLikeHtml("""[[["x","y"]]]"""))
    }

    @Test
    fun `garbage is not a translation`() {
        assertNull(KeylessParsers.parseClients5(""))
    }
}

/** An HTTP fake that answers per host, so the rotation can be tested without a network. */
private class HostFake(
    private val answers: Map<String, () -> String>,
) : Http {
    val calls = mutableListOf<String>()
    override suspend fun get(url: String, headers: Map<String, String>): String {
        calls += url
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val answer = answers[host] ?: throw java.net.UnknownHostException("Unable to resolve host $host")
        return answer()
    }

    override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
        get(url, headers)
}

private fun rateLimited(): String = throw app.mangascribe.translate.TranslationException("HTTP 429: too many requests")

class KeylessRotationTest {

    @Test
    fun `when google is rate limited the dictionary endpoint answers`() = runBlocking {
        HostHealth.reset()
        KeylessTranslateTranslator.resetHosts()
        val http = HostFake(
            mapOf(
                "translate.googleapis.com" to { rateLimited() },
                "clients5.google.com" to { """{"sentences":[{"trans":"چی؟!"}]}""" },
            )
        )
        val engine = KeylessTranslateTranslator(http)
        val batch = engine.translate(listOf("What?!"), "en", "ckb")
        assertEquals(listOf("چی؟!"), batch.lines)
        assertTrue(http.calls.any { it.contains("clients5.google.com") })
    }

    @Test
    fun `one blocked google host does not stop the page`() = runBlocking {
        HostHealth.reset()
        KeylessTranslateTranslator.resetHosts()
        val http = HostFake(
            mapOf(
                "translate.googleapis.com" to { throw java.net.UnknownHostException("blocked") },
                "clients5.google.com" to { """{"sentences":[{"trans":"ڕابە!"}]}""" },
            )
        )
        val engine = KeylessTranslateTranslator(http)
        val batch = engine.translate(listOf("Run!"), "en", "ckb")
        assertEquals(listOf("ڕابە!"), batch.lines)
    }

    @Test
    fun `a dead host is not retried for the rest of the run`() = runBlocking {
        HostHealth.reset()
        KeylessTranslateTranslator.resetHosts()
        val http = HostFake(
            mapOf(
                "translate.googleapis.com" to { throw java.net.UnknownHostException("blocked") },
                "clients5.google.com" to { """{"sentences":[{"trans":"باشە"}]}""" },
            )
        )
        val engine = KeylessTranslateTranslator(http)
        engine.translate(listOf("one"), "en", "ckb")
        val firstRound = http.calls.size
        engine.translate(listOf("two"), "en", "ckb")
        // The Google hosts failed at the transport level on the first line and must be skipped
        // on the second, so the second line costs exactly one request.
        assertEquals(1, http.calls.size - firstRound)
    }

    @Test
    fun `every host failing is an actionable message, not a DNS string`() = runBlocking {
        HostHealth.reset()
        KeylessTranslateTranslator.resetHosts()
        val engine = KeylessTranslateTranslator(HostFake(emptyMap()))
        val error = runCatching { engine.translate(listOf("What?!"), "en", "ckb") }.exceptionOrNull()
        val message = error?.message.orEmpty()
        assertTrue("got: $message", message.contains("free endpoint", ignoreCase = true))
        assertTrue("must tell the reader what to do: $message", message.contains("retry", ignoreCase = true)
            || message.contains("Settings", ignoreCase = true))
    }

    @Test
    fun `the pipeline accepts the rotation for kurdish`() = runBlocking {
        HostHealth.reset()
        KeylessTranslateTranslator.resetHosts()
        val http = HostFake(mapOf("translate.googleapis.com" to {
            """[[["سڵاو","Hello",null,null,1]]]"""
        }))
        val engine = KeylessTranslateTranslator(http)
        assertEquals("ckb", engine.codeFor(Languages.of("ckb")))
        val batch = TranslationPipeline(listOf(engine), engine.id)
            .translate(listOf("Hello"), "en", "ckb")
        assertEquals(listOf("سڵاو"), batch.lines)
    }
}
