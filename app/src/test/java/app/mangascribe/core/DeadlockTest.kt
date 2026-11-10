package app.mangascribe.core

import app.mangascribe.translate.HostHealth
import app.mangascribe.translate.Http
import app.mangascribe.translate.KeylessTranslateTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Reading the page` that never finished, with nothing translated.
 *
 * The keyless engine took its semaphore twice per request — once around the line and once inside
 * the request. With three permits and a page of four or more lines, three coroutines held the
 * permits and waited for a fourth: a textbook deadlock inside a `coroutineScope`, so the page
 * never returned and the reader saw a spinner forever.
 *
 * This test fails on the old code by timing out.
 */
class DeadlockTest {

    private class OkHttp : Http {
        override suspend fun get(url: String, headers: Map<String, String>): String =
            """[[["ڕابە!","Run!",null,null,1]]]"""

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
            get(url, headers)
    }

    @Test
    fun `a page with more lines than the concurrency limit still finishes`() = runBlocking {
        HostHealth.reset()
        KeylessTranslateTranslator.resetHosts()
        val engine = KeylessTranslateTranslator(OkHttp(), maxConcurrent = 3)
        val page = (1..12).map { "line $it" }
        val batch = withTimeout(10_000) { engine.translate(page, "en", "ckb") }
        assertEquals(page.size, batch.lines.size)
        assertEquals(List(12) { "ڕابە!" }, batch.lines)
    }
}
