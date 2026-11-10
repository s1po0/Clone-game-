package app.mangascribe.live

import app.mangascribe.core.Languages
import app.mangascribe.translate.GoogleResponseParser
import app.mangascribe.translate.KeylessParsers
import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.OkHttpHttp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.system.exitProcess

/**
 * The real endpoint, from the real translator code, on every push.
 *
 * This is the piece that was missing: the app's translation path was only ever exercised in the
 * sandbox, where the network is blocked, so a wrong URL, a refused code or an echoed line could
 * only be found by installing an APK and reading a screenshot. That cost a dozen round trips.
 *
 * These tests call the same `KeylessTranslateTranslator` and the same parsers the APK uses, over
 * the real network, with the same URLs. When they fail, the message is the diagnosis.
 *
 * They are real network calls, so they are skipped unless `MANGASCRIBE_LIVE=1` — the CI workflow
 * sets it. A developer offline sees them skipped, not failed.
 */
class LiveEndpointTest {

    private val live = System.getenv("MANGASCRIBE_LIVE") == "1"

    /** Kotlin is not Python: a test that cannot run must say so, not fail. */
    private fun requireLive() = assumeTrue("set MANGASCRIBE_LIVE=1 to run", live)

    @Test
    fun `the dj=1 json shape answers in kurdish`() {
        requireLive()
        val body = kotlinx.coroutines.runBlocking {
            OkHttpHttp().get(
                "https://translate.googleapis.com/translate_a/single?client=gtx" +
                    "&sl=auto&tl=ckb&dt=t&dj=1&q=hello",
                headers = mapOf("User-Agent" to "MangaScribe/live-test"),
            )
        }
        println("LIVE dj=1: $body")
        val text = GoogleResponseParser.parseJson(body)
        assertTrue("no Kurdish in: $body", text!!.any { it in '\u0600'..'\u06FF' })
    }

    @Test
    fun `the exact url the app sends answers in kurdish`() {
        requireLive()
        val body = kotlinx.coroutines.runBlocking {
            OkHttpHttp().get(
                "https://translate.googleapis.com/translate_a/single?client=gtx" +
                    "&sl=auto&tl=ckb&dt=t&ie=UTF-8&oe=UTF-8&q=hello",
                headers = mapOf("User-Agent" to "MangaScribe/live-test"),
            )
        }
        val text = GoogleResponseParser.parse(body)
        println("LIVE google: $text")
        assertTrue("no Kurdish in: $body", text!!.any { it in '\u0600'..'\u06FF' })
    }

    @Test
    fun `the translator itself returns kurdish for an english page`() {
        requireLive()
        val engine = KeylessTranslateTranslator(OkHttpHttp(), lingvaInstances = emptyList())
        val lines = listOf("who are you?", "what is this?", "run!")
        val batch = kotlinx.coroutines.runBlocking { engine.translate(lines, "en", "ckb") }
        println("LIVE page: ${batch.lines}")
        assertTrue(
            "English came back: ${batch.lines}",
            batch.lines.none { line -> line.any { it in 'A'..'Z' || it in 'a'..'z' } },
        )
    }

    @Test
    fun `a kurdish page still translates onward instead of echoing`() {
        requireLive()
        val engine = KeylessTranslateTranslator(OkHttpHttp(), lingvaInstances = emptyList())
        val batch = kotlinx.coroutines.runBlocking {
            // the exact shape that produced an all-English page: source believed to be Kurdish
            engine.translate(listOf("what the actual fuck is this?!"), "ckb", "ckb")
        }
        println("LIVE echo-check: ${batch.lines}")
        assertTrue(
            "the source was echoed back: ${batch.lines}",
            batch.lines.none { line -> line.any { it in 'A'..'Z' || it in 'a'..'z' } },
        )
    }

    @Test
    fun `google dictionary and lingva routes are probed so a dead one is known`() {
        requireLive()
        val http = OkHttpHttp()
        val clients5 = runCatching {
            kotlinx.coroutines.runBlocking {
                http.get(
                    "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ckb&q=hello",
                )
            }
        }
        println("LIVE clients5: ${clients5.getOrNull() ?: clients5.exceptionOrNull()?.message}")
        if (clients5.isSuccess) {
            assertTrue(
                "clients5 did not return Kurdish: ${clients5.getOrNull()}",
                KeylessParsers.parseClients5(clients5.getOrThrow(), "hello")
                    .orEmpty().any { it in '\u0600'..'\u06FF' },
            )
        }
        val lingva = runCatching {
            kotlinx.coroutines.runBlocking {
                http.get("https://lingva.ml/api/v1/en/ckb/hello")
            }
        }
        println("LIVE lingva: ${lingva.getOrNull() ?: lingva.exceptionOrNull()?.message}")
        // Not asserted: Lingva is a third-party mirror that may be down or may not know ckb.
        // The point of probing it is that the log answers "why did the page take so long".
    }

    @Test
    fun `the target code is the one the endpoint expects`() {
        requireLive()
        // A cheap guard against a refactor that changes `ckb` to `ku` (Kurmanji) somewhere.
        assertTrue(Languages.of("ckb").code in setOf("ckb", "ku-Arab"))
    }
}
