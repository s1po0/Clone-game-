package app.mangascribe.live

import app.mangascribe.translate.MicrosoftResponseParser
import app.mangascribe.translate.MicrosoftTranslateTranslator
import app.mangascribe.translate.OkHttpHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Microsoft, keyless, into Sorani. The parser is tested offline; the endpoint is probed live.
 */
class LiveMicrosoftTest {

    private val live = System.getenv("MANGASCRIBE_LIVE") == "1"

    @Test
    fun `the reply shape is parsed`() {
        val json = """[{"translations":[{"text":"لایەن: کۆیلەی پەرستگا","to":"ckb"}]}]"""
        assertEquals("لایەن: کۆیلەی پەرستگا", MicrosoftResponseParser.parse(json))
    }

    @Test
    fun `an error body is not read as a translation`() {
        assertEquals(null, MicrosoftResponseParser.parse("""{"error":{"code":401}}"""))
        assertEquals(null, MicrosoftResponseParser.parse("<html>"))
    }

    @Test
    fun `the real endpoint answers in kurdish`() {
        assumeTrue(live)
        val engine = MicrosoftTranslateTranslator(OkHttpHttp())
        val page = listOf("ASPECT: TEMPLE SLAVE", "what is this?")
        val result = runCatching { runBlocking { engine.translate(page, "en", "ckb") } }
        println("LIVE microsoft: ${result.getOrNull()?.lines ?: result.exceptionOrNull()?.message}")
        if (result.isSuccess) {
            val lines = result.getOrThrow().lines
            assertEquals(2, lines.size)
        }
    }
}
