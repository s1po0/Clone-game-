package app.mangascribe.live

import app.mangascribe.core.Languages
import app.mangascribe.translate.MicrosoftResponseParser
import app.mangascribe.translate.MicrosoftTranslateTranslator
import app.mangascribe.translate.OkHttpHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Microsoft, keyless, into Sorani.
 *
 * The endpoint moved in August 2026: `edge.microsoft.com/translate/auth` now 404s, and the
 * two-step JWT flow with it. Edge's own web translator uses
 * `POST edge.microsoft.com/translate/translatetext?from=&to=&isEnterpriseClient=false` with a
 * **plain array of strings** — one translation per input, no token at all. That is what this
 * class calls; the old shape (`[{"Text":"..."}]`) is rejected with 400.
 */
class LiveMicrosoftTest {

    private val live = System.getenv("MANGASCRIBE_LIVE") == "1"

    @Test
    fun `sorani is sent as Microsoft's ku, not ckb`() {
        // Measured on the endpoint: `to=ckb` is HTTP 400, `to=ku` is `سڵاو`.
        assertEquals("ku", MicrosoftTranslateTranslator(OkHttpHttp()).codeFor(Languages.of("ckb")))
        assertEquals(null, MicrosoftTranslateTranslator(OkHttpHttp()).codeFor(Languages.of("ku")))
    }

    @Test
    fun `the reply shape is parsed`() {
        val json = """[{"translations":[{"text":"لایەن: کۆیلەی پەرستگا","to":"ckb"}]}]"""
        assertEquals("لایەن: کۆیلەی پەرستگا", MicrosoftResponseParser.parse(json))
        assertEquals(
            "ڕاکردن!",
            MicrosoftResponseParser.parse("""[{"translations":[{"text":"ڕاکردن!","to":"ckb"}]}]"""),
        )
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
            assertEquals(2, result.getOrThrow().lines.size)
        }
    }
}
