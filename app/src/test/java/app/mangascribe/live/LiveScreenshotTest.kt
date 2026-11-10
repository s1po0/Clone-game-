package app.mangascribe.live

import app.mangascribe.core.KurdishTransliteration
import app.mangascribe.core.Languages
import app.mangascribe.core.TranslatedTextCleanup
import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.OkHttpHttp
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * The real page, with the real reader's complaint: every bubble came back in Kurdish letters
 * with no meaning ("هه نده كيان دروست به لام ئه مه كرده شتك بى مانا").
 *
 * There are two possible causes and only the log can tell them apart:
 *  a) the engine failed for these lines and the rescue path transliterated them, or
 *  b) the engine answered, but the answer was thrown away.
 *
 * So this prints the raw engine answer next to the final line for the exact text OCR produces on
 * that page. `ci-live-report.txt` keeps the output.
 */
class LiveScreenshotTest {

    private val live = System.getenv("MANGASCRIBE_LIVE") == "1"

    /** What ML Kit reads on `Shadow Slave - Chapter 2 - 14.webp`, top to bottom. */
    private val page = listOf(
        "THEY SAY THE SPELL VALUES BALANCE AS MUCH AS CHALLENGE.",
        "SO I SHOULD AT LEAST GET A STRONG ASPECT OUT OF THIS, RIGHT?",
        "ASPECT: TEMPLE SLAVE",
        "ASPECT DESCRIPTION: A SLAVE IS A USELESS WRETCH WITH NO NOTABLE SKILLS OR ABILITIES. " +
            "A TEMPLE SLAVE IS FAR RARER, BUT JUST AS WORTHLESS.",
        "WHAT?! WHAT THE ACTUAL FUCK IS THIS?!",
        "\"THERE ARE NO USELESS ASPECTS,\" HUH?",
    )

    @Test
    fun `the real page is translated, not transliterated`() {
        assumeTrue(live)
        listOf("en", "auto").forEach { source ->
            val engine = KeylessTranslateTranslator(OkHttpHttp(), lingvaInstances = emptyList())
            val batch = runBlocking { engine.translate(page, source, "ckb") }
            println("RAW-$source (engine answered):")
            batch.lines.forEach { println("   $it") }
            if (source == "en") {
                println("FINAL:")
                batch.lines.zip(page).forEach { (line, src) ->
                    val rescued = KurdishTransliteration.rescueMixed(line)
                    val transliterated =
                        KurdishTransliteration.isTransliterated(rescued)
                    println("   EN  | ${src.take(48)}")
                    println("   KU  | $line")
                    println("   FIN | $rescued")
                    println("       | transliterated=$transliterated leftover=" +
                        TranslatedTextCleanup.hasLeftoverSource(line, src, true))
                }
            }
        }
    }

    @Test
    fun `each line alone, the way the retry sends it`() {
        assumeTrue(live)
        val engine = KeylessTranslateTranslator(OkHttpHttp(), lingvaInstances = emptyList())
        page.forEach { line ->
            val one = runBlocking { engine.translate(listOf(line), "en", "ckb") }
            println("ONE | ${line.take(40)}")
            println("  → | ${one.lines.firstOrNull()}")
        }
    }
}
