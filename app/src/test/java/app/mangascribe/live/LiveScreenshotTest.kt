package app.mangascribe.live

import app.mangascribe.core.KurdishTransliteration
import app.mangascribe.core.TranslatedTextCleanup
import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.OkHttpHttp
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * The exact page from the reader's screenshot: a status box whose text is ghosted behind the
 * bubble, so OCR reads both layers and the engine is handed a line with the English *and* a
 * partially translated line above it.
 *
 * The failure this reproduces: every bubble came out in Kurdish letters with no meaning — the
 * rescue path transliterated sentences the engine had failed to translate. Printing the raw
 * engine answer next to the final line shows which step lost the meaning.
 */
class LiveScreenshotTest {

    private val live = System.getenv("MANGASCRIBE_LIVE") == "1"

    // What ML Kit plausibly returns on that box: the label, the body, and the ghosted art text.
    private val page = listOf(
        "ASPECT: TEMPLE SLAVE",
        "ASPECT DESCRIPTION: A SLAVE IS A USELESS WRETCH WITH NO NOTABLE SKILLS OR ABILITIES. " +
            "A TEMPLE SLAVE IS FAR RARER, BUT JUST AS WORTHLESS.",
        "what the !؟ actual fuck is this?!",
    )

    @Test
    fun `the status box from the screenshot is translated, not transliterated`() {
        assumeTrue(live)
        val engine = KeylessTranslateTranslator(OkHttpHttp(), lingvaInstances = emptyList())
        val batch = runBlocking { engine.translate(page, "en", "ckb") }
        println("RAW-ENGINE:")
        batch.lines.forEach { println("   $it") }
        println("SYNTH:")
        batch.lines.zip(page).forEach { (line, source) ->
            val after = KurdishTransliteration.rescueMixed(line)
            println("   EN  | $source")
            println("   KU  | $line")
            println("   FIN | $after")
            println("       | leftover=${TranslatedTextCleanup.hasLeftoverSource(line, source, true)}")
        }
    }

    @Test
    fun `the same page with auto source is translated, not transliterated`() {
        assumeTrue(live)
        val engine = KeylessTranslateTranslator(OkHttpHttp(), lingvaInstances = emptyList())
        val batch = runBlocking { engine.translate(page, "auto", "ckb") }
        println("RAW-AUTO:")
        batch.lines.forEach { println("   $it") }
    }
}
